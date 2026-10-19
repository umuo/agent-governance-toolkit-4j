package io.agt.core;

import io.agt.core.approval.ApprovalRequest;
import io.agt.core.approval.ApprovalResponse;
import io.agt.core.audit.AuditRecord;
import io.agt.core.data.DataFragment;
import io.agt.core.data.DataLimits;
import io.agt.core.data.DataOperation;
import io.agt.core.data.DataResource;
import io.agt.core.data.FragmentProjection;
import io.agt.core.delegation.AuthorityFrame;
import io.agt.core.delegation.DelegationQuota;
import io.agt.core.delegation.DelegationScope;
import io.agt.core.delegation.SessionAuthority;
import io.agt.core.execution.*;
import io.agt.core.flow.DataFlowState;
import io.agt.core.usage.*;
import java.time.Instant;
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
    private final java.util.concurrent.ConcurrentMap<String, String> toolExecutions =
            new java.util.concurrent.ConcurrentHashMap<>();

    void enterToolAction(String actionId, String name) {
        checkActive();
        if (name != null) toolExecutions.put(actionId, name);
    }

    void leaveToolAction(String actionId) {
        toolExecutions.remove(actionId);
    }

    public void checkEffectAdmission(
            String actionId, String tool, io.agt.core.effects.EffectRule rule) {
        checkActive();
        if (control.mode != EnforcementMode.ENFORCE
                || !rule.equals(control.evaluator.effectRule(tool))
                || !Objects.equals(toolExecutions.get(actionId), tool))
            throw new GovernanceException("effect_tool_admission_required");
        validateToolBinding(tool, control.reviewedTool(tool));
    }

    /** 已提交副作用的版本指纹；进入持久请求摘要，版本变更不能重放旧意图。 */
    public String effectToolBinding(
            String actionId, String tool, io.agt.core.effects.EffectRule rule) {
        checkEffectAdmission(actionId, tool, rule);
        return control.evaluator.toolBinding(tool);
    }

    private final AgentControl control;
    private final TrustedContext context;
    private final ExecutionContext execution;
    private final HostSession delegationParent;
    private final int delegationDepth;
    private final TaskBudget taskBudget;
    private final DelegationScope delegationScope;
    private final TrustedContext scopeOwner;
    private final SessionAuthority authority;
    private final boolean ownsAuthority;
    private boolean taskAttached;
    private final Set<HostSession> children = ConcurrentHashMap.newKeySet();
    private final Set<ModelUsageReservation> pendingUsage = ConcurrentHashMap.newKeySet();
    private final String id = UUID.randomUUID().toString();
    private final long started = System.nanoTime();
    private final HostSession previous;
    private final Thread owner = Thread.currentThread();
    private final boolean detached;
    private final Set<Future<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile boolean revoked;
    private volatile boolean failed;
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
        this(control, context, previous, detached, null);
    }

    HostSession(
            AgentControl control,
            TrustedContext context,
            HostSession previous,
            boolean detached,
            ExecutionContext execution) {
        this(control, context, previous, detached, execution, null);
    }

    HostSession(
            AgentControl control,
            TrustedContext context,
            HostSession previous,
            boolean detached,
            ExecutionContext execution,
            HostSession delegationParent) {
        this(control, context, previous, detached, execution, delegationParent, null);
    }

    HostSession(
            AgentControl control,
            TrustedContext context,
            HostSession previous,
            boolean detached,
            ExecutionContext execution,
            HostSession delegationParent,
            TaskLimits taskLimits) {
        this(control, context, previous, detached, execution, delegationParent, taskLimits, null);
    }

    HostSession(
            AgentControl control,
            TrustedContext context,
            HostSession previous,
            boolean detached,
            ExecutionContext execution,
            HostSession delegationParent,
            TaskLimits taskLimits,
            DelegationScope scope) {
        this(
                control,
                context,
                previous,
                detached,
                execution,
                delegationParent,
                taskLimits,
                scope,
                null);
    }

    HostSession(
            AgentControl control,
            TrustedContext context,
            HostSession previous,
            boolean detached,
            ExecutionContext execution,
            HostSession delegationParent,
            TaskLimits taskLimits,
            DelegationScope scope,
            SessionAuthority authority) {
        this.delegationParent = delegationParent;
        this.authority =
                authority != null
                        ? authority
                        : delegationParent == null ? null : delegationParent.authority;
        this.ownsAuthority = authority != null;
        this.delegationScope =
                scope == null && delegationParent != null
                        ? delegationParent.delegationScope
                        : scope;
        this.scopeOwner =
                scope == null && delegationParent != null ? delegationParent.scopeOwner : context;
        this.delegationDepth = delegationParent == null ? 0 : delegationParent.delegationDepth + 1;
        this.taskBudget =
                delegationParent == null
                        ? new TaskBudget(
                                taskLimits == null
                                        ? new TaskLimits(
                                                control.budget,
                                                control.dataLimits.maxCalls(),
                                                128,
                                                32)
                                        : taskLimits,
                                this::scheduleUsageFailure,
                                authority == null
                                        ? UUID.randomUUID().toString()
                                        : authority.taskId(),
                                authority == null ? null : authority.usageLedger(),
                                authority == null ? null : authority.dataFlowLedger(),
                                control.labelStore,
                                authority == null ? null : authority.externalExecutionLedger())
                        : delegationParent.taskBudget;
        this.execution = execution;
        this.detached = detached;
        this.control = control;
        this.context = context;
        this.previous = previous;
    }

    public String sessionId() {
        return id;
    }

    public String parentSessionId() {
        return delegationParent == null ? null : delegationParent.sessionId();
    }

    public synchronized ExternalExecution registerExternalExecution(
            String kind, ExternalCancellation cancellation) {
        checkActive();
        return taskBudget.registerExternal(this, kind, cancellation);
    }

    /** 诊断接口在关闭后仍可读；仅反映本 JVM 注册的根任务外部工作。 */
    public ExternalExecutionRegistry.Snapshot externalExecutionSnapshot() {
        return taskBudget.externalSnapshot();
    }

    public void bindExportedExternalView(ExternalExecutionLedger view) {
        taskBudget.bindExternalView(view);
    }

    public int delegationDepth() {
        return delegationDepth;
    }

    public List<AuthorityFrame> authorityFrames() {
        var frames = new java.util.ArrayList<AuthorityFrame>();
        if (authority != null) frames.addAll(authority.frames());
        for (HostSession session = this; session != null; session = session.delegationParent) {
            if (session.delegationScope != null) {
                var frame = new AuthorityFrame(session.scopeOwner, session.delegationScope);
                if (!frames.contains(frame)) frames.add(frame);
            }
        }
        return List.copyOf(frames);
    }

    public long remainingDurationMillis() {
        checkActive();
        return remainingMillis();
    }

    /** 单向转交剩余调用权；失败或未知时不自动退回，防止在两个协调器重复消费。 */
    public synchronized DelegationQuota exportDelegationQuota() {
        checkActive();
        if (control.labelStore != null && taskBudget.flow == null)
            throw new GovernanceException("data_flow_policy_required");
        if (authority != null)
            throw new GovernanceException("delegation_export_constraints_unsupported");
        return taskBudget.exportQuota();
    }

    public synchronized io.agt.core.delegation.DelegationState exportDelegationState() {
        checkActive();
        if (control.labelStore != null && taskBudget.flow == null)
            throw new GovernanceException("data_flow_policy_required");
        if (authority != null)
            throw new GovernanceException("delegation_export_constraints_unsupported");
        return taskBudget.exportState();
    }

    public BudgetLimits runtimeBudget() {
        return control.budget;
    }

    /** 仅供宿主授权适配器完成交接；此后原会话的交付与统计也使用权威视图。 */
    public void bindExportedTaskView(
            Runnable active,
            io.agt.core.flow.DataFlowLedger flow,
            io.agt.core.usage.UsageLedger usage) {
        taskBudget.bindExportedView(active, flow, usage);
    }

    AutoCloseable beginTaskOperation(String action) {
        return taskBudget.beginOperation(action);
    }

    void commitAuthority(
            String actionId,
            InterventionPoint point,
            String name,
            Object effective,
            Object prepared) {
        if (authority != null) {
            if (prepared instanceof BoundToolPreparation<?> bound && bound.lease() != null)
                authority.commit(
                        new PolicyRequest(
                                point,
                                context,
                                id,
                                actionId,
                                name,
                                effective,
                                null,
                                bound.lease().identity()));
            else authority.commit(actionId, point, name, effective);
        }
    }

    void attachAuthority() {
        if (authority != null && !ownsAuthority)
            throw new GovernanceException("delegation_remote_child_requires_grant");
        if (ownsAuthority) authority.attach(id, this::refuse);
    }

    void closeAuthority() {
        if (ownsAuthority) authority.close();
    }

    public DelegationScope delegationScope() {
        return delegationScope;
    }

    public boolean requiresToolVerification(String name) {
        checkActive();
        return control.reviewedTool(name) != null;
    }

    /** 模型适配器在发送工具描述前校验宿主固定清单；不泄露制品或凭据。 */
    public void validateToolSpecification(String name, Map<String, Object> specification) {
        checkActive();
        control.validateToolSpecification(name, specification, context);
    }

    public DataFlowState.Snapshot dataFlowSnapshot() {
        return taskBudget.flow == null ? null : taskBudget.flow.snapshot();
    }

    public UsageLimits usageLimits() {
        return taskBudget.usage == null ? null : taskBudget.usage.limits();
    }

    public UsageBudget.Snapshot usageSnapshot() {
        return taskBudget.usageSnapshot();
    }

    public synchronized ModelUsageReservation reserveModelUsage(
            String model, int inputUpperBound, int outputUpperBound) {
        checkActive();
        var reservation =
                taskBudget.reserveUsage(
                        id, model, inputUpperBound, outputUpperBound, pendingUsage::remove);
        pendingUsage.add(reservation);
        return reservation;
    }

    private void scheduleUsageFailure(String code) {
        try {
            control.deadlines.execute(() -> refuse(code));
        } catch (RejectedExecutionException stopped) {
            /* 已关闭的 runtime 已独立终止全部会话。 */
        }
    }

    /** 实时预览仍受宿主数据等级约束；不声称已完成内容规则的最终检查。 */
    public void checkDelivery() {
        checkActive();
        if (taskBudget.flow != null) {
            try {
                taskBudget.flow.validate(
                        new PolicyRequest(
                                InterventionPoint.OUTPUT,
                                context,
                                id,
                                UUID.randomUUID().toString(),
                                null,
                                null));
            } catch (GovernanceException denied) {
                throw fail(InterventionPoint.OUTPUT, denied.code());
            }
        }
    }

    private void validateDelegation(PolicyRequest request) {
        if (authority != null) authority.validate(request);
        if (authority != null) for (var frame : authority.frames()) frame.validate(request);
        for (HostSession session = this; session != null; session = session.delegationParent) {
            if (session.delegationScope != null) {
                session.delegationScope.validate(
                        new PolicyRequest(
                                request.point(),
                                session.scopeOwner,
                                request.sessionId(),
                                request.actionId(),
                                request.toolName(),
                                request.target()));
            }
        }
    }

    void attachTask() {
        taskBudget.attach(delegationDepth);
        taskAttached = true;
    }

    synchronized void detachTask() {
        if (taskAttached) {
            taskAttached = false;
            taskBudget.detach();
        }
    }

    public TaskSnapshot taskSnapshot() {
        return taskBudget.snapshot();
    }

    public record TaskSnapshot(
            String taskId,
            int modelCalls,
            int toolCalls,
            int dataCalls,
            int activeSessions,
            TaskLimits limits) {}

    synchronized void attachChild(HostSession child) {
        checkActive();
        children.add(child);
    }

    void detachChild(HostSession child) {
        children.remove(child);
    }

    public ExecutionContext execution() {
        return execution;
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
        for (HostSession parent = delegationParent;
                parent != null;
                parent = parent.delegationParent) {
            if (parent.closed || parent.revoked || parent.failed || parent.remainingMillis() <= 0) {
                throw new GovernanceException("delegation_parent_inactive");
            }
        }
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
        taskBudget.checkExportedActive();
        taskBudget.checkExternalActive();
        if (taskBudget.usage != null) taskBudget.usage.checkActive();
        if (authority != null) authority.checkActive();
        if (remainingMillis() <= 0) {
            failed = true;
            failureCode = "session_expired";
            releaseReservations();
            throw new GovernanceException("session_expired");
        }
    }

    private long remainingMillis() {
        long local =
                control.budget.maxDurationMillis()
                        - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        local = Math.min(local, taskBudget.remainingMillis());
        if (authority != null) local = Math.min(local, authority.remainingMillis());
        return delegationParent == null
                ? local
                : Math.min(local, delegationParent.remainingMillis());
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

    /** 适配器只记录已发生错误的机器原因；实际终止仍由正常失败路径执行。 */
    public synchronized void noteFailureCause(String code) {
        if (failureCode == null) failureCode = Identifiers.require(code);
    }

    public GovernanceException refuse(String code) {
        String cause;
        synchronized (this) {
            if (failureCode == null) {
                failureCode = code;
            }
            cause = failureCode;
            failed = true;
            releaseReservations();
        }
        terminatePending(cause);
        return new GovernanceException(code);
    }

    private void terminatePending(String code) {
        toolExecutions.clear();
        // 先结束外部租约，权威端批量清理预占；本地逐项取消不应逐笔等待网络超时。
        try {
            closeAuthority();
        } catch (RuntimeException unavailable) {
            /* 本机会话已终止，继续取消交付；远端按租约到期拒绝。 */
        }
        taskBudget.cancelExternal(id);
        // 显式通知所有已登记子会话；祖先状态检查不获取祖先锁，避免反向锁序。
        for (var child : List.copyOf(children)) {
            if (code.equals("session_revoked") || code.equals("session_closed")) child.revoke();
            else child.refuse(code);
        }
        for (var reservation : List.copyOf(pendingUsage)) reservation.close();
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
            taskBudget.reserve(TaskBudget.Kind.TOOL);
            toolCalls++;
            reservedTools++;
        } else {
            if (modelCalls >= control.budget.maxModelCalls()) {
                throw new GovernanceException("model_budget_exceeded");
            }
            taskBudget.reserve(TaskBudget.Kind.MODEL);
            modelCalls++;
            reservedModels++;
        }
    }

    synchronized void release(boolean tool) {
        if (tool && reservedTools > 0) {
            taskBudget.release(TaskBudget.Kind.TOOL, 1);
            reservedTools--;
            toolCalls--;
        } else if (!tool && reservedModels > 0) {
            taskBudget.release(TaskBudget.Kind.MODEL, 1);
            reservedModels--;
            modelCalls--;
        }
    }

    synchronized void commit(boolean tool) {
        commit(tool, null, null);
    }

    synchronized void commit(boolean tool, String name, Object arguments) {
        commit(tool, name, arguments, null);
    }

    synchronized void commit(boolean tool, String name, Object arguments, Object prepared) {
        checkActive();
        if (tool) {
            var bound = prepared instanceof BoundToolPreparation<?> value ? value : null;
            validateToolBinding(name, bound == null ? null : bound.lease());
            Object actualPrepared = bound == null ? prepared : bound.value();
            var required = control.evaluator.effectRule(name);
            if (required != null
                    && (!(actualPrepared instanceof io.agt.core.effects.PreparedEffect plan)
                            || !required.equals(plan.rule())
                            || control.mode != EnforcementMode.ENFORCE))
                throw new GovernanceException("effect_tool_binding_required");
            if (reservedTools < 1) {
                throw new GovernanceException("budget_state_invalid");
            }
            if (taskBudget.flow != null) taskBudget.flow.beforeTool(name, arguments);
            reservedTools--;
        } else {
            if (reservedModels < 1) {
                throw new GovernanceException("budget_state_invalid");
            }
            if (taskBudget.flow != null) taskBudget.flow.beforeModel();
            if (prepared instanceof ManagedModelInput managed) managed.beforeModelDispatch();
            if (taskBudget.usage != null) {
                if (!(prepared instanceof MeteredModelInput metered)
                        || metered.usageReservation() == null) {
                    throw new GovernanceException("model_usage_reservation_required");
                }
                metered.usageReservation().dispatch(id, taskBudget.usage);
            }
            reservedModels--;
        }
    }

    private void releaseReservations() {
        // 所有未执行预占先原子释放，再通知任何终止 future，避免观察到陈旧计数。
        taskBudget.release(TaskBudget.Kind.DATA, reservedData);
        taskBudget.release(TaskBudget.Kind.MODEL, reservedModels);
        taskBudget.release(TaskBudget.Kind.TOOL, reservedTools);
        dataCalls -= reservedData;
        reservedData = 0;
        modelCalls -= reservedModels;
        toolCalls -= reservedTools;
        reservedModels = 0;
        reservedTools = 0;
    }

    synchronized void reserveData() {
        checkActive();
        if (control.labelStore != null && taskBudget.flow == null)
            throw new GovernanceException("data_flow_policy_required");
        if (dataCalls >= control.dataLimits.maxCalls()) {
            throw new GovernanceException("data_budget_exceeded");
        }
        taskBudget.reserve(TaskBudget.Kind.DATA);
        dataCalls++;
        reservedData++;
    }

    synchronized void commitData() {
        commitData(null);
    }

    synchronized void commitData(Object target) {
        commitData(target, UUID.randomUUID().toString());
    }

    synchronized void commitData(Object target, String actionId) {
        checkActive();
        if (reservedData < 1) {
            throw new GovernanceException("budget_state_invalid");
        }
        if (taskBudget.flow != null) taskBudget.flow.beforeData(actionId, target);
        reservedData--;
    }

    synchronized void releaseData() {
        if (reservedData > 0) {
            taskBudget.release(TaskBudget.Kind.DATA, 1);
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

    /** 有信息流策略时，为实际准备值和读取结果补充内容寻址来源。 */
    public <E, T> T dataOperationPrepared(
            DataResource resource,
            DataOperation operation,
            Object input,
            Function<Object, E> prepare,
            Function<E, T> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace,
            FragmentProjection<E, T> fragments) {
        if (taskBudget.flow == null)
            return dataOperationPrepared(
                    resource, operation, input, prepare, execute, project, replace);
        return executePrepared(
                false,
                true,
                null,
                InterventionPoint.PRE_DATA_ACCESS,
                InterventionPoint.POST_DATA_ACCESS,
                resource.target(operation, input),
                effective -> {
                    E value = prepare.apply(resource.value(effective, operation));
                    return new PreparedDataInput<>(
                            value,
                            DataFragment.target(
                                    resource,
                                    operation,
                                    resource.value(effective, operation),
                                    fragments.written(operation, value)));
                },
                prepared -> execute.apply(prepared.value()),
                result ->
                        DataFragment.target(
                                resource,
                                operation,
                                project.apply(result),
                                fragments.read(operation, result)),
                (result, effective) ->
                        replace.apply(
                                result,
                                DataFragment.value(
                                        resource,
                                        operation,
                                        effective,
                                        fragments.read(operation, result))));
    }

    public <E, T> CompletableFuture<T> dataOperationAsyncPrepared(
            DataResource resource,
            DataOperation operation,
            Object input,
            Function<Object, E> prepare,
            Function<E, ? extends CompletionStage<T>> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace,
            FragmentProjection<E, T> fragments) {
        if (taskBudget.flow == null)
            return dataOperationAsyncPrepared(
                    resource, operation, input, prepare, execute, project, replace);
        try {
            return new AsyncInvocation<PreparedDataInput<E>, T>(
                            this,
                            false,
                            false,
                            null,
                            resource.target(operation, input),
                            effective -> {
                                E value = prepare.apply(resource.value(effective, operation));
                                return new PreparedDataInput<>(
                                        value,
                                        DataFragment.target(
                                                resource,
                                                operation,
                                                resource.value(effective, operation),
                                                fragments.written(operation, value)));
                            },
                            prepared -> execute.apply(prepared.value()),
                            result ->
                                    DataFragment.target(
                                            resource,
                                            operation,
                                            project.apply(result),
                                            fragments.read(operation, result)),
                            (result, effective) ->
                                    replace.apply(
                                            result,
                                            DataFragment.value(
                                                    resource,
                                                    operation,
                                                    effective,
                                                    fragments.read(operation, result))))
                    .dataAccess()
                    .start();
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    fail(
                            InterventionPoint.PRE_DATA_ACCESS,
                            error instanceof GovernanceException denied
                                    ? denied.code()
                                    : "data_access_failed"));
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
            var binding = target instanceof ToolInput bound ? bound.lease() : null;
            Object actual = target instanceof ToolInput bound ? bound.parameters() : target;
            if (point == InterventionPoint.PRE_TOOL_CALL
                    || point == InterventionPoint.POST_TOOL_CALL)
                validateToolBinding(toolName, binding);
            request =
                    actual instanceof EffectInput effect
                            ? new PolicyRequest(
                                    point,
                                    context,
                                    id,
                                    actionId,
                                    toolName,
                                    effect.parameters(),
                                    effect.target(),
                                    binding == null ? null : binding.identity())
                            : new PolicyRequest(
                                    point,
                                    context,
                                    id,
                                    actionId,
                                    toolName,
                                    actual,
                                    null,
                                    binding == null ? null : binding.identity());
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
        var detectionWarnings = new java.util.ArrayList<String>();
        if (request.toolBinding() != null)
            detectionWarnings.add("tool_binding:" + request.toolBinding());
        try {
            control.requirePoints(Set.of(point));
            validateDelegation(request);
            if (taskBudget.flow != null) taskBudget.flow.validate(request);
            decision = Objects.requireNonNull(control.evaluator.evaluate(request));
            if (!(control.mode == EnforcementMode.ENFORCE
                    && decision.kind() == PolicyDecision.Kind.DENY
                    && !decision.approvalRequired())) {
                var detection =
                        control.injection.check(
                                this,
                                request,
                                io.agt.core.injection.DetectionInput.Stage.ORIGINAL,
                                control.mode);
                detectionWarnings.addAll(detection.warnings());
                if (detection.refusal() != null) throw new GovernanceException(detection.refusal());
            }
            if (control.mode == EnforcementMode.ENFORCE) {
                if (decision.kind() == PolicyDecision.Kind.TRANSFORM) {
                    if (point == InterventionPoint.AGENT_STARTUP
                            || point == InterventionPoint.AGENT_SHUTDOWN) {
                        throw new GovernanceException("transform_not_applicable");
                    }
                    effective = decision.replacement();
                    control.evaluator.validateEffective(request.withTarget(effective));
                    validateDelegation(request.withTarget(effective));
                    if (taskBudget.flow != null)
                        taskBudget.flow.validate(request.withTarget(effective));
                    if (!Objects.equals(request.target(), effective)) {
                        var detection =
                                control.injection.check(
                                        this,
                                        request.withTarget(effective),
                                        io.agt.core.injection.DetectionInput.Stage.EFFECTIVE,
                                        control.mode);
                        detectionWarnings.addAll(detection.warnings());
                        if (detection.refusal() != null)
                            throw new GovernanceException(detection.refusal());
                    }
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
                    java.util.stream.Stream.concat(
                                    decision.warnings().stream(), detectionWarnings.stream())
                            .distinct()
                            .toList(),
                    true,
                    request.target(),
                    effective);
            return effective;
        } catch (Exception e) {
            String code =
                    e instanceof GovernanceException g ? g.code() : "policy_evaluation_failed";
            boolean policyDenied =
                    code.equals("injection_detected")
                            || (decision != null
                                    && decision.kind() == PolicyDecision.Kind.DENY
                                    && !decision.approvalRequired()
                                    && code.equals(decision.reasonCode()));
            try {
                emit(
                        point,
                        actionId,
                        policyDenied
                                        || ((code.startsWith("delegation_")
                                                        || code.startsWith("data_flow_"))
                                                && code.endsWith("_denied"))
                                ? "DENY"
                                : "ERROR",
                        code,
                        false,
                        code.startsWith("approval") ? code : approvalOutcome,
                        "not_executed",
                        inputIdentity,
                        inputIdentity,
                        System.nanoTime() - start,
                        detectionWarnings.stream().distinct().toList(),
                        true,
                        request.target(),
                        effective);
            } finally {
                refuse(code);
            }
            throw new GovernanceException(code);
        }
    }

    private String identity(PolicyRequest r) {
        return io.agt.core.approval.ActionIdentity.of(control.version, r);
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
            // 审批工作线程先达到等待期限时，Future 会包装其 TimeoutException。
            // 与调用线程先超时保持同一语义；宿主 stage 的异常仍是 approval_failed。
            if (e.getCause() instanceof TimeoutException) {
                throw new GovernanceException("approval_timeout");
            }
            throw new GovernanceException("approval_failed");
        } finally {
            pending.remove(task);
            task.cancel(true);
        }
    }

    /**
     * 检查并确认一次独立回调披露，不新建顶层运行或重复计入模型/工具预算。
     *
     * @param original 原始框架值
     * @param project 有界策略投影
     * @param restore 类型恢复
     * @param <T> 披露值类型
     * @return 在输出检查和审计确认后的值
     */
    public <T> T disclose(
            T original, Function<T, Object> project, BiFunction<T, Object, T> restore) {
        String actionId = UUID.randomUUID().toString();
        try {
            checkActive();
            Object effective =
                    evaluate(InterventionPoint.OUTPUT, actionId, null, project.apply(original));
            T value = restore.apply(original, effective);
            checkActive();
            execution(InterventionPoint.OUTPUT, actionId, "succeeded");
            return value;
        } catch (RuntimeException error) {
            refuse(error instanceof GovernanceException g ? g.code() : "disclosure_failed");
            execution(InterventionPoint.OUTPUT, actionId, "failed");
            throw error;
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

    private EffectInput effectInput(
            String name, Object parameters, io.agt.core.effects.EffectIntent intent) {
        var rule = control.evaluator.effectRule(name);
        if (rule == null || control.mode != EnforcementMode.ENFORCE)
            throw new GovernanceException("effect_policy_required");
        return new EffectInput(parameters, new io.agt.core.effects.EffectTarget(rule, intent));
    }

    private io.agt.core.effects.PreparedEffect prepareEffect(
            String name,
            Object value,
            io.agt.core.effects.EffectLedger ledger,
            io.agt.core.effects.EffectIntent intent,
            io.agt.core.effects.EffectBackend backend) {
        var rule = control.evaluator.effectRule(name);
        if (rule == null || control.mode != EnforcementMode.ENFORCE)
            throw new GovernanceException("effect_policy_required");
        return new io.agt.core.effects.PreparedEffect(rule, ledger, intent, backend, value);
    }

    public Object effectOperation(
            String name,
            Object input,
            io.agt.core.effects.EffectLedger ledger,
            io.agt.core.effects.EffectIntent intent,
            io.agt.core.effects.EffectBackend backend) {
        return effectOperation(name, input, ledger, intent, backend, value -> {}, value -> {});
    }

    public Object effectOperation(
            String name,
            Object input,
            io.agt.core.effects.EffectLedger ledger,
            io.agt.core.effects.EffectIntent intent,
            io.agt.core.effects.EffectBackend backend,
            java.util.function.Consumer<Object> before,
            java.util.function.Consumer<Object> raw) {
        return effectOperation(name, input, ledger, intent, backend, before, raw, value -> {});
    }

    public Object effectOperation(
            String name,
            Object input,
            io.agt.core.effects.EffectLedger ledger,
            io.agt.core.effects.EffectIntent intent,
            io.agt.core.effects.EffectBackend backend,
            java.util.function.Consumer<Object> before,
            java.util.function.Consumer<Object> raw,
            java.util.function.Consumer<Object> checked) {
        EffectInput bound;
        io.agt.core.tools.ToolLease lease;
        try {
            lease = control.reviewedTool(name);
            bound = effectInput(name, input, intent);
        } catch (RuntimeException invalid) {
            throw fail(
                    InterventionPoint.PRE_TOOL_CALL,
                    invalid instanceof GovernanceException denied
                            ? denied.code()
                            : "effect_preparation_failed");
        }
        return executePrepared(
                true,
                false,
                name,
                InterventionPoint.PRE_TOOL_CALL,
                InterventionPoint.POST_TOOL_CALL,
                lease == null ? bound : new ToolInput(bound, lease),
                value ->
                        new BoundToolPreparation<>(
                                lease, prepareEffect(name, value, ledger, intent, backend)),
                (action, value) ->
                        io.agt.core.effects.EffectCoordinator.execute(
                                this, action, name, value.value(), before, raw),
                value -> lease == null ? value : new ToolInput(value, lease),
                (original, effective) -> checkedEffect(name, lease, effective, checked));
    }

    public CompletableFuture<Object> effectOperationAsync(
            String name,
            Object input,
            io.agt.core.effects.EffectLedger ledger,
            io.agt.core.effects.EffectIntent intent,
            io.agt.core.effects.EffectBackend backend,
            java.util.function.Consumer<Object> before,
            java.util.function.Consumer<Object> raw) {
        return effectOperationAsync(name, input, ledger, intent, backend, before, raw, value -> {});
    }

    public CompletableFuture<Object> effectOperationAsync(
            String name,
            Object input,
            io.agt.core.effects.EffectLedger ledger,
            io.agt.core.effects.EffectIntent intent,
            io.agt.core.effects.EffectBackend backend,
            java.util.function.Consumer<Object> before,
            java.util.function.Consumer<Object> raw,
            java.util.function.Consumer<Object> checked) {
        try {
            var lease = control.reviewedTool(name);
            var bound = effectInput(name, input, intent);
            return new AsyncInvocation<
                            BoundToolPreparation<io.agt.core.effects.PreparedEffect>, Object>(
                            this,
                            false,
                            true,
                            name,
                            lease == null ? bound : new ToolInput(bound, lease),
                            value ->
                                    new BoundToolPreparation<>(
                                            lease,
                                            prepareEffect(name, value, ledger, intent, backend)),
                            (action, value) ->
                                    CompletableFuture.completedFuture(
                                            io.agt.core.effects.EffectCoordinator.execute(
                                                    this,
                                                    action,
                                                    name,
                                                    value.value(),
                                                    before,
                                                    raw)),
                            value -> lease == null ? value : new ToolInput(value, lease),
                            (original, effective) -> checkedEffect(name, lease, effective, checked))
                    .start();
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    fail(
                            InterventionPoint.PRE_TOOL_CALL,
                            error instanceof GovernanceException denied
                                    ? denied.code()
                                    : "effect_preparation_failed"));
        }
    }

    private Object checkedEffect(
            String name,
            io.agt.core.tools.ToolLease lease,
            Object effective,
            java.util.function.Consumer<Object> checked) {
        Object value = JsonValues.freeze(effective);
        validateToolBinding(name, lease);
        checked.accept(value);
        checkActive();
        validateToolBinding(name, lease);
        return value;
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

    private void validateToolBinding(String name, io.agt.core.tools.ToolLease lease) {
        String expected = control.evaluator.toolBinding(name);
        if (expected == null && lease == null) return;
        if (control.mode != EnforcementMode.ENFORCE)
            throw new GovernanceException("tool_version_enforce_required");
        if (lease == null
                || !Objects.equals(expected, lease.identity())
                || !Objects.equals(name, lease.name()))
            throw new GovernanceException("tool_version_binding_denied");
        if (control.reviewedTool(name) != lease)
            throw new GovernanceException("tool_version_catalog_mismatch");
        lease.validate(context);
    }

    /** 可信宿主登记的版本工具；租约与制品不进入参数，仅摘要进入审批和策略请求。 */
    public <E, T> T reviewedToolOperation(
            io.agt.core.tools.ToolLease lease,
            Object input,
            Function<Object, E> prepare,
            Function<E, T> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        Objects.requireNonNull(lease);
        return operationPrepared(
                true,
                lease.name(),
                new ToolInput(input, lease),
                effective -> new BoundToolPreparation<>(lease, prepare.apply(effective)),
                bound -> execute.apply(bound.value()),
                value -> new ToolInput(project.apply(value), lease),
                replace);
    }

    public <E, T> CompletableFuture<T> reviewedToolOperationAsync(
            io.agt.core.tools.ToolLease lease,
            Object input,
            Function<Object, E> prepare,
            Function<E, ? extends CompletionStage<T>> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        Objects.requireNonNull(lease);
        return operationAsyncPrepared(
                true,
                lease.name(),
                new ToolInput(input, lease),
                effective -> new BoundToolPreparation<>(lease, prepare.apply(effective)),
                bound -> execute.apply(bound.value()),
                value -> new ToolInput(project.apply(value), lease),
                replace);
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
        return executePrepared(
                tool,
                data,
                name,
                pre,
                post,
                input,
                prepare,
                (id, value) -> execute.apply(value),
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
            BiFunction<String, E, T> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        String actionId = UUID.randomUUID().toString();
        if (data) {
            registerDataAudit(actionId, input);
        }
        long operationStart = System.nanoTime();
        boolean reserved = false;
        boolean startedExecution = false;
        ModelUsageReservation usage = null;
        ManagedModelInput managed = null;
        AutoCloseable taskOperation = null;
        try {
            taskOperation = beginTaskOperation(actionId);
            if (data) {
                reserveData();
            } else {
                reserve(tool);
            }
            reserved = true;
            Object effective = evaluate(pre, actionId, name, input);
            E prepared = prepare.apply(effective);
            if (data && prepared instanceof PreparedDataInput<?> bound) effective = bound.target();
            if (!tool && !data && prepared instanceof MeteredModelInput metered)
                usage = metered.usageReservation();
            if (!tool && !data && prepared instanceof ManagedModelInput lifecycle)
                managed = lifecycle;
            commitAuthority(actionId, pre, name, effective, prepared);
            if (data) {
                commitData(effective, actionId);
            } else {
                commit(tool, name, effective, prepared);
            }
            startedExecution = true;
            if (tool && !data) enterToolAction(actionId, name);
            T result = execute.apply(actionId, prepared);
            checkActive();
            Object output = evaluate(post, actionId, name, project.apply(result));
            T replaced = replace.apply(result, output);
            execution(
                    post,
                    actionId,
                    "succeeded",
                    "execution_result",
                    System.nanoTime() - operationStart);
            completedOperation(tool, name, actionId);
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
            leaveToolAction(actionId);
            if (managed != null) managed.closeModelPreparation();
            if (usage != null) usage.close();
            if (reserved && !startedExecution) {
                if (data) {
                    releaseData();
                } else {
                    release(tool);
                }
            }
            if (taskOperation != null)
                try {
                    taskOperation.close();
                } catch (Exception impossible) {
                    throw new IllegalStateException(impossible);
                }
        }
    }

    private void execution(InterventionPoint point, String actionId, String outcome) {
        execution(point, actionId, outcome, "execution_result", 0);
    }

    void completedOperation(boolean tool, String name, String actionId) {
        if (tool && name != null && taskBudget.flow != null)
            taskBudget.flow.completedTool(actionId, name);
    }

    public void effectEvidence(
            String actionId,
            String code,
            String keyDigest,
            String requestDigest,
            io.agt.core.effects.EffectLedger.Phase phase,
            long attempt) {
        io.agt.core.effects.EffectLedger.requireDigest(keyDigest);
        io.agt.core.effects.EffectLedger.requireDigest(requestDigest);
        Identifiers.require(code);
        if (attempt < 1) throw new IllegalArgumentException("Invalid effect attempt");
        emit(
                InterventionPoint.POST_TOOL_CALL,
                actionId,
                "EXECUTION",
                code,
                false,
                "not_requested",
                code,
                requestDigest,
                requestDigest,
                0,
                List.of(
                        "effect_key:" + keyDigest,
                        "effect_phase:" + phase.name(),
                        "effect_attempt:" + attempt));
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
        emit(
                point,
                actionId,
                verdict,
                reason,
                transformed,
                approval,
                outcome,
                input,
                effective,
                nanos,
                warnings,
                false,
                null,
                null);
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
            List<String> warnings,
            boolean capture,
            Object originalBody,
            Object effectiveBody) {
        var info = dataAudit.get(actionId);
        boolean dataPoint =
                point == InterventionPoint.PRE_DATA_ACCESS
                        || point == InterventionPoint.POST_DATA_ACCESS;
        try {
            control.emit(
                    new io.agt.core.audit.AuditScope(
                            context.tenantId(), context.principalId(), taskBudget.taskId(), id),
                    new AuditRecord(
                            execution != null ? 3 : dataPoint ? 2 : 1,
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
                            info == null ? null : info.resourceIdentity(),
                            execution),
                    capture,
                    originalBody,
                    effectiveBody);
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
            detachTask();
            try {
                closeAuthority();
            } finally {
                if (delegationParent != null) delegationParent.detachChild(this);
            }
        }
    }
}
