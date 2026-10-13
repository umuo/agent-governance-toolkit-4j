package io.agt.core;

import io.agt.core.approval.ApprovalRequest;
import io.agt.core.approval.ApprovalResponse;
import io.agt.core.audit.AuditRecord;
import io.agt.core.data.DataLimits;
import io.agt.core.data.DataOperation;
import io.agt.core.data.DataResource;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 会话计数属于宿主，策略本身无运行状态。
 *
 * <p>安全拒绝是不可恢复的会话终止。维护预算配额、干预点判定、异步任务跟踪及审计记录派发。
 */
public final class HostSession implements AutoCloseable {
    private final AgentControl control;
    private final TrustedContext context;
    private final String id = UUID.randomUUID().toString();
    private final long started = System.nanoTime();
    private final HostSession previous;
    private final Thread owner = Thread.currentThread();
    private final boolean detached;
    private final Set<Future<?>> pending = ConcurrentHashMap.newKeySet();
    private boolean closed;
    private boolean revoked;
    private boolean failed;
    private boolean runActive;
    private boolean asyncRun;
    private final Map<String, Consumer<String>> asyncCalls = new ConcurrentHashMap<>();
    private ScheduledFuture<?> deadline;
    private int modelCalls;
    private int toolCalls;
    private int dataCalls;
    private int reservedModels;
    private int reservedTools;
    private int reservedData;
    private String failureCode;

    private record DataAuditInfo(String operation, String resourceIdentity) {}

    private final Map<String, DataAuditInfo> dataAudit = new ConcurrentHashMap<>();

    void registerDataAudit(String actionId, Object input) {
        var target = (Map<?, ?>) input;
        String operation = DataOperation.valueOf((String) target.get("operation")).name();
        dataAudit.put(
                actionId,
                new DataAuditInfo(operation, JsonValues.digest(DataResource.resource(input))));
    }

    private long tokens;
    private boolean tokensKnown = true;

    HostSession(AgentControl control, TrustedContext context, HostSession previous) {
        this(control, context, previous, false);
    }

    HostSession(
            AgentControl control, TrustedContext context, HostSession previous, boolean detached) {
        this.detached = detached;
        this.control = control;
        this.context = context;
        this.previous = previous;
    }

    public String sessionId() {
        return id;
    }

    public TrustedContext context() {
        return context;
    }

    public AgentControl control() {
        return control;
    }

    public synchronized BudgetSnapshot budgetSnapshot() {
        return new BudgetSnapshot(
                modelCalls,
                toolCalls,
                tokensKnown ? tokens : null,
                closed,
                revoked,
                failed,
                dataCalls);
    }

    /**
     * 会话预算快照，记录当前调用次数、token 消耗及会话状态。
     *
     * <p>用于监控与检查会话的资源使用情况。
     */
    public record BudgetSnapshot(
            int modelCalls,
            int toolCalls,
            Long tokens,
            boolean closed,
            boolean revoked,
            boolean failed,
            int dataCalls) {
        public BudgetSnapshot(
                int modelCalls,
                int toolCalls,
                Long tokens,
                boolean closed,
                boolean revoked,
                boolean failed) {
            this(modelCalls, toolCalls, tokens, closed, revoked, failed, 0);
        }
    }

    public synchronized void checkActive() {
        control.checkRuntime();
        if (closed) {
            throw new GovernanceException("session_closed");
        }
        if (revoked) {
            throw new GovernanceException("session_revoked");
        }
        if (failed) {
            throw new GovernanceException(
                    "session_expired".equals(failureCode) ? "session_expired" : "session_failed");
        }
        if (remainingMillis() <= 0) {
            failed = true;
            failureCode = "session_expired";
            releaseReservations();
            throw new GovernanceException("session_expired");
        }
    }

    private long remainingMillis() {
        return control.budget.maxDurationMillis()
                - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    public SessionScope bind() {
        return new SessionScope(control, this);
    }

    void startDeadline() {
        deadline =
                control.deadlines.schedule(
                        () -> refuse("session_expired"),
                        Math.max(1, remainingMillis()),
                        TimeUnit.MILLISECONDS);
    }

    public void revoke() {
        synchronized (this) {
            revoked = true;
            releaseReservations();
        }
        terminatePending("session_revoked");
    }

    public synchronized String failureCode() {
        return failureCode;
    }

    public GovernanceException refuse(String code) {
        synchronized (this) {
            if (!failed) {
                failureCode = code;
            }
            failed = true;
            releaseReservations();
        }
        terminatePending(code);
        return new GovernanceException(code);
    }

    private void terminatePending(String code) {
        for (var callback : List.copyOf(asyncCalls.values())) {
            callback.accept(code);
        }
        cancelPending();
    }

    void registerAsync(String id, Consumer<String> abort) {
        asyncCalls.put(id, abort);
    }

    void unregisterAsync(String id) {
        asyncCalls.remove(id);
    }

    /**
     * 将适配器的交付 promise 关联到会话终止；不预占预算，也不治理 promise 内的任意操作。
     *
     * <p>先注册后检查，避免关闭与注册交错时留下永不完成的交付 future。
     */
    public <T> CompletableFuture<T> watchTermination(CompletableFuture<T> result) {
        Objects.requireNonNull(result);
        String id = UUID.randomUUID().toString();
        Consumer<String> abort =
                code -> {
                    try (var scope = bind()) {
                        result.completeExceptionally(new GovernanceException(code));
                    }
                };
        registerAsync(id, abort);
        result.whenComplete((value, error) -> unregisterAsync(id));
        try {
            checkActive();
        } catch (GovernanceException error) {
            abort.accept(error.code());
        }
        return result;
    }

    void track(Future<?> future) {
        pending.add(future);
        boolean inactive;
        synchronized (this) {
            inactive = closed || revoked || failed;
        }
        if (inactive) {
            future.cancel(true);
        }
    }

    void untrack(Future<?> future) {
        pending.remove(future);
    }

    synchronized void enterRun(boolean async) {
        checkActive();
        if (runActive) {
            throw new GovernanceException("concurrent_run_unsupported");
        }
        runActive = true;
        asyncRun = async;
    }

    synchronized void leaveRun() {
        runActive = false;
        asyncRun = false;
    }

    public synchronized boolean isAsyncRun() {
        return runActive && asyncRun;
    }

    /**
     * 仅传播框架 I/O 的上下文与取消。
     *
     * <p>不声称这是数据访问授权。
     */
    public <T> CompletableFuture<T> supplyAsync(Supplier<? extends CompletionStage<T>> execute) {
        try {
            return new AsyncInvocation<Object, T>(
                            this,
                            false,
                            false,
                            null,
                            null,
                            Function.identity(),
                            v -> execute.get(),
                            v -> null,
                            (v, e) -> v)
                    .bridge()
                    .start();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public <T> CompletableFuture<T> runAsync(
            Object input,
            Function<Object, ? extends CompletionStage<T>> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        try {
            return new AsyncInvocation<Object, T>(
                            this,
                            true,
                            false,
                            null,
                            input,
                            Function.identity(),
                            execute,
                            project,
                            replace)
                    .start();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                    fail(
                            InterventionPoint.INPUT,
                            e instanceof GovernanceException g
                                    ? g.code()
                                    : "async_execution_failed"));
        }
    }

    public <E, T> CompletableFuture<T> operationAsyncPrepared(
            boolean tool,
            String name,
            Object input,
            Function<Object, E> prepare,
            Function<E, ? extends CompletionStage<T>> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        try {
            return new AsyncInvocation<E, T>(
                            this, false, tool, name, input, prepare, execute, project, replace)
                    .start();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                    fail(
                            tool
                                    ? InterventionPoint.PRE_TOOL_CALL
                                    : InterventionPoint.PRE_MODEL_CALL,
                            e instanceof GovernanceException g
                                    ? g.code()
                                    : "async_execution_failed"));
        }
    }

    /**
     * 适配器在解析或投影失败时留下不含原文的错误记录。
     *
     * <p>记录失败审计事件并返回治理异常。
     */
    public GovernanceException fail(InterventionPoint point, String code) {
        GovernanceException failure = refuse(code);
        emit(
                point,
                UUID.randomUUID().toString(),
                "ERROR",
                code,
                false,
                "not_requested",
                "not_executed",
                null,
                null,
                0,
                List.of());
        return failure;
    }

    private void cancelPending() {
        pending.forEach(f -> f.cancel(true));
    }

    public synchronized void recordTokens(Integer count) {
        if (count == null) {
            tokensKnown = false;
        } else if (count < 0) {
            throw refuse("invalid_token_usage");
        } else {
            tokens = Math.addExact(tokens, count.longValue());
        }
    }

    synchronized void reserve(boolean tool) {
        checkActive();
        if (tool) {
            if (toolCalls >= control.budget.maxToolCalls()) {
                throw new GovernanceException("tool_budget_exceeded");
            }
            toolCalls++;
            reservedTools++;
        } else {
            if (modelCalls >= control.budget.maxModelCalls()) {
                throw new GovernanceException("model_budget_exceeded");
            }
            modelCalls++;
            reservedModels++;
        }
    }

    synchronized void release(boolean tool) {
        if (tool && reservedTools > 0) {
            reservedTools--;
            toolCalls--;
        } else if (!tool && reservedModels > 0) {
            reservedModels--;
            modelCalls--;
        }
    }

    synchronized void commit(boolean tool) {
        checkActive();
        if (tool) {
            if (reservedTools < 1) {
                throw new GovernanceException("budget_state_invalid");
            }
            reservedTools--;
        } else {
            if (reservedModels < 1) {
                throw new GovernanceException("budget_state_invalid");
            }
            reservedModels--;
        }
    }

    private void releaseReservations() {
        // 所有未执行预占先原子释放，再通知任何终止 future，避免观察到陈旧计数。
        dataCalls -= reservedData;
        reservedData = 0;
        modelCalls -= reservedModels;
        toolCalls -= reservedTools;
        reservedModels = 0;
        reservedTools = 0;
    }

    synchronized void reserveData() {
        checkActive();
        if (dataCalls >= control.dataLimits.maxCalls()) {
            throw new GovernanceException("data_budget_exceeded");
        }
        dataCalls++;
        reservedData++;
    }

    synchronized void commitData() {
        checkActive();
        if (reservedData < 1) {
            throw new GovernanceException("budget_state_invalid");
        }
        reservedData--;
    }

    synchronized void releaseData() {
        if (reservedData > 0) {
            reservedData--;
            dataCalls--;
        }
    }

    public DataLimits dataLimits() {
        return control.dataLimits;
    }

    public <E, T> T dataOperationPrepared(
            DataResource resource,
            DataOperation operation,
            Object input,
            Function<Object, E> prepare,
            Function<E, T> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        return executePrepared(
                false,
                true,
                null,
                InterventionPoint.PRE_DATA_ACCESS,
                InterventionPoint.POST_DATA_ACCESS,
                resource.target(operation, input),
                effective -> prepare.apply(resource.value(effective, operation)),
                execute,
                result -> resource.target(operation, project.apply(result)),
                (result, effective) -> replace.apply(result, resource.value(effective, operation)));
    }

    public <E, T> CompletableFuture<T> dataOperationAsyncPrepared(
            DataResource resource,
            DataOperation operation,
            Object input,
            Function<Object, E> prepare,
            Function<E, ? extends CompletionStage<T>> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        try {
            return new AsyncInvocation<E, T>(
                            this,
                            false,
                            false,
                            null,
                            resource.target(operation, input),
                            effective -> prepare.apply(resource.value(effective, operation)),
                            execute,
                            result -> resource.target(operation, project.apply(result)),
                            (result, effective) ->
                                    replace.apply(result, resource.value(effective, operation)))
                    .dataAccess()
                    .start();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                    fail(
                            InterventionPoint.PRE_DATA_ACCESS,
                            e instanceof GovernanceException g ? g.code() : "data_access_failed"));
        }
    }

    public Object evaluate(
            InterventionPoint point, String actionId, String toolName, Object target) {
        checkActive();
        return evaluateInternal(point, actionId, toolName, target);
    }

    private Object evaluateInternal(
            InterventionPoint point, String actionId, String toolName, Object target) {
        long start = System.nanoTime();
        PolicyRequest request;
        try {
            request = new PolicyRequest(point, context, id, actionId, toolName, target);
        } catch (RuntimeException e) {
            throw fail(point, e instanceof GovernanceException g ? g.code() : "invalid_request");
        }
        String inputIdentity;
        try {
            inputIdentity = identity(request);
        } catch (RuntimeException e) {
            throw fail(point, e instanceof GovernanceException g ? g.code() : "digest_failed");
        }
        PolicyDecision decision = null;
        Object effective = request.target();
        String approvalOutcome = "not_requested";
        try {
            control.requirePoints(Set.of(point));
            decision = Objects.requireNonNull(control.evaluator.evaluate(request));
            if (control.mode == EnforcementMode.ENFORCE) {
                if (decision.kind() == PolicyDecision.Kind.TRANSFORM) {
                    if (point == InterventionPoint.AGENT_STARTUP
                            || point == InterventionPoint.AGENT_SHUTDOWN) {
                        throw new GovernanceException("transform_not_applicable");
                    }
                    effective = decision.replacement();
                    control.evaluator.validateEffective(request.withTarget(effective));
                }
                if (decision.kind() == PolicyDecision.Kind.DENY && !decision.approvalRequired()) {
                    throw new GovernanceException(decision.reasonCode());
                }
                if (decision.approvalRequired()
                        || control.evaluator.requiresApproval(request.withTarget(effective))) {
                    approve(request.withTarget(effective));
                    approvalOutcome = "approved";
                }
            }
            if (point != InterventionPoint.AGENT_SHUTDOWN) {
                checkActive();
            }
            emit(
                    point,
                    actionId,
                    decision.kind().name(),
                    decision.reasonCode(),
                    control.mode == EnforcementMode.ENFORCE
                            && decision.kind() == PolicyDecision.Kind.TRANSFORM,
                    approvalOutcome,
                    "not_executed",
                    inputIdentity,
                    identity(request.withTarget(effective)),
                    System.nanoTime() - start,
                    decision.warnings());
            return effective;
        } catch (Exception e) {
            String code =
                    e instanceof GovernanceException g ? g.code() : "policy_evaluation_failed";
            boolean policyDenied =
                    decision != null
                            && decision.kind() == PolicyDecision.Kind.DENY
                            && !decision.approvalRequired()
                            && code.equals(decision.reasonCode());
            try {
                emit(
                        point,
                        actionId,
                        policyDenied ? "DENY" : "ERROR",
                        code,
                        false,
                        code.startsWith("approval") ? code : approvalOutcome,
                        "not_executed",
                        inputIdentity,
                        inputIdentity,
                        System.nanoTime() - start,
                        List.of());
            } finally {
                refuse(code);
            }
            throw new GovernanceException(code);
        }
    }

    private String identity(PolicyRequest r) {
        return JsonValues.digest(
                Map.of(
                        "point",
                        r.point().wireName(),
                        "sessionId",
                        id,
                        "actionId",
                        r.actionId(),
                        "toolName",
                        r.toolName() == null ? "" : r.toolName(),
                        "context",
                        Map.of(
                                "agent",
                                context.agentId(),
                                "tenant",
                                context.tenantId(),
                                "principal",
                                context.principalId(),
                                "permissions",
                                context.permissions().stream().sorted().toList()),
                        "policyVersion",
                        control.version,
                        "target",
                        Collections.singletonList(r.target())));
    }

    private void approve(PolicyRequest action) throws Exception {
        if (control.approvalResolver == null) {
            throw new GovernanceException("approval_unavailable");
        }
        long waitMillis = Math.min(control.approvalTimeoutMillis, remainingMillis());
        if (waitMillis <= 0) {
            throw new GovernanceException("approval_timeout");
        }
        String identity = identity(action);
        ApprovalRequest request =
                new ApprovalRequest(
                        UUID.randomUUID().toString(),
                        identity,
                        control.version,
                        action,
                        System.currentTimeMillis() + waitMillis);
        // 同步 resolve 也放入有限线程池，防止宿主错误实现阻塞调用线程无限等待。
        Future<ApprovalResponse> task;
        try {
            task =
                    control.approvals.submit(
                            () -> {
                                var stage = control.approvalResolver.resolve(request);
                                if (stage == null) {
                                    throw new GovernanceException("approval_failed");
                                }
                                CompletableFuture<ApprovalResponse> future =
                                        stage.toCompletableFuture();
                                pending.add(future);
                                try {
                                    return future.get(waitMillis, TimeUnit.MILLISECONDS);
                                } finally {
                                    pending.remove(future);
                                    future.cancel(true);
                                }
                            });
        } catch (RejectedExecutionException e) {
            throw new GovernanceException("approval_capacity_exceeded");
        }
        pending.add(task);
        try {
            ApprovalResponse response = task.get(waitMillis, TimeUnit.MILLISECONDS);
            if (System.currentTimeMillis() > request.expiresAtEpochMillis()) {
                throw new GovernanceException("approval_timeout");
            }
            checkActive();
            if (response == null
                    || !request.requestId().equals(response.requestId())
                    || !identity.equals(response.actionIdentity())
                    || !identity.equals(identity(action))) {
                throw new GovernanceException("approval_identity_mismatch");
            }
            if (!response.approved()) {
                throw new GovernanceException("approval_denied");
            }
            control.evaluator.validateEffective(action);
        } catch (TimeoutException e) {
            throw new GovernanceException("approval_timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GovernanceException("approval_cancelled");
        } catch (CancellationException e) {
            throw new GovernanceException("approval_cancelled");
        } catch (ExecutionException e) {
            throw new GovernanceException("approval_failed");
        } finally {
            pending.remove(task);
            task.cancel(true);
        }
    }

    public <T> T run(
            Object input,
            Function<Object, T> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        try {
            enterRun(false);
        } catch (GovernanceException e) {
            throw refuse(e.code());
        }
        String actionId = UUID.randomUUID().toString();
        try {
            Object effective = evaluate(InterventionPoint.INPUT, actionId, null, input);
            T result = execute.apply(effective);
            checkActive();
            Object output =
                    evaluate(InterventionPoint.OUTPUT, actionId, null, project.apply(result));
            T replaced = replace.apply(result, output);
            execution(InterventionPoint.OUTPUT, actionId, "succeeded");
            return replaced;
        } catch (RuntimeException e) {
            refuse(e instanceof GovernanceException g ? g.code() : "agent_execution_failed");
            execution(InterventionPoint.OUTPUT, actionId, "failed");
            throw e;
        } finally {
            leaveRun();
        }
    }

    public <T> T operation(
            boolean tool,
            String name,
            Object input,
            Function<Object, T> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        return operationPrepared(tool, name, input, Function.identity(), execute, project, replace);
    }

    /**
     * 准备并执行治理操作。
     *
     * <p>prepare 只做形状恢复，不执行 delegate；失败时释放预占额度。
     */
    public <E, T> T operationPrepared(
            boolean tool,
            String name,
            Object input,
            Function<Object, E> prepare,
            Function<E, T> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        return executePrepared(tool, false, name, input, prepare, execute, project, replace);
    }

    private <E, T> T executePrepared(
            boolean tool,
            boolean data,
            String name,
            Object input,
            Function<Object, E> prepare,
            Function<E, T> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        return executePrepared(
                tool,
                data,
                name,
                tool ? InterventionPoint.PRE_TOOL_CALL : InterventionPoint.PRE_MODEL_CALL,
                tool ? InterventionPoint.POST_TOOL_CALL : InterventionPoint.POST_MODEL_CALL,
                input,
                prepare,
                execute,
                project,
                replace);
    }

    private <E, T> T executePrepared(
            boolean tool,
            boolean data,
            String name,
            InterventionPoint pre,
            InterventionPoint post,
            Object input,
            Function<Object, E> prepare,
            Function<E, T> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        String actionId = UUID.randomUUID().toString();
        if (data) {
            registerDataAudit(actionId, input);
        }
        long operationStart = System.nanoTime();
        boolean reserved = false;
        boolean startedExecution = false;
        try {
            if (data) {
                reserveData();
            } else {
                reserve(tool);
            }
            reserved = true;
            Object effective = evaluate(pre, actionId, name, input);
            E prepared = prepare.apply(effective);
            if (data) {
                commitData();
            } else {
                commit(tool);
            }
            startedExecution = true;
            T result = execute.apply(prepared);
            checkActive();
            Object output = evaluate(post, actionId, name, project.apply(result));
            T replaced = replace.apply(result, output);
            execution(
                    post,
                    actionId,
                    "succeeded",
                    "execution_result",
                    System.nanoTime() - operationStart);
            return replaced;
        } catch (RuntimeException e) {
            refuse(e instanceof GovernanceException g ? g.code() : "operation_failed");
            execution(
                    post,
                    actionId,
                    startedExecution ? "failed_after_execution" : "refused_before_execution",
                    e instanceof GovernanceException g ? g.code() : "operation_failed",
                    System.nanoTime() - operationStart);
            throw e;
        } finally {
            if (reserved && !startedExecution) {
                if (data) {
                    releaseData();
                } else {
                    release(tool);
                }
            }
        }
    }

    private void execution(InterventionPoint point, String actionId, String outcome) {
        execution(point, actionId, outcome, "execution_result", 0);
    }

    void execution(
            InterventionPoint point, String actionId, String outcome, String reason, long elapsed) {
        emit(
                point,
                actionId,
                "EXECUTION",
                reason,
                false,
                "not_requested",
                outcome,
                null,
                null,
                elapsed,
                List.of());
    }

    private void emit(
            InterventionPoint point,
            String actionId,
            String verdict,
            String reason,
            boolean transformed,
            String approval,
            String outcome,
            String input,
            String effective,
            long nanos,
            List<String> warnings) {
        var info = dataAudit.get(actionId);
        boolean dataPoint =
                point == InterventionPoint.PRE_DATA_ACCESS
                        || point == InterventionPoint.POST_DATA_ACCESS;
        try {
            control.emit(
                    new AuditRecord(
                            dataPoint ? 2 : 1,
                            Instant.now(),
                            id,
                            context.agentId(),
                            actionId,
                            point,
                            control.mode,
                            control.version,
                            verdict,
                            reason,
                            transformed,
                            approval,
                            outcome,
                            input,
                            effective,
                            nanos,
                            warnings,
                            info == null ? null : info.operation(),
                            info == null ? null : info.resourceIdentity()));
        } catch (GovernanceException e) {
            throw refuse(e.code());
        } finally {
            if ("EXECUTION".equals(verdict)) {
                dataAudit.remove(actionId);
            }
        }
    }

    @Override
    public void close() {
        if (!detached && Thread.currentThread() != owner) {
            throw new GovernanceException("session_owner_required");
        }
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            releaseReservations();
        }
        if (deadline != null) {
            deadline.cancel(false);
        }
        terminatePending("session_closed");
        try {
            // 失败/撤销会话仍记录终止干预点，但不允许恢复已终止的操作。
            evaluateInternal(
                    InterventionPoint.AGENT_SHUTDOWN,
                    "shutdown",
                    null,
                    Map.of(
                            "modelCalls",
                            modelCalls,
                            "toolCalls",
                            toolCalls,
                            "dataCalls",
                            dataCalls,
                            "failed",
                            failed,
                            "revoked",
                            revoked));
        } finally {
            synchronized (this) {
                closed = true;
                cancelPending();
            }
            control.detach(this, previous);
        }
    }
}
