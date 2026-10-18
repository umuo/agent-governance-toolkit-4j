package io.agt.core;

import io.agt.core.approval.ApprovalResolver;
import io.agt.core.audit.AuditRecord;
import io.agt.core.audit.AuditSink;
import io.agt.core.data.DataLimits;
import io.agt.core.delegation.DelegationScope;
import io.agt.core.delegation.SessionAuthority;
import io.agt.core.flow.LabelStore;
import io.agt.core.injection.*;
import io.agt.core.policy.StructuredPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 不可变配置的治理运行时。
 *
 * <p>一个不可变配置的治理运行时可服务多个隔离会话。运行时拥有审批线程池，审计 sink 由宿主关闭。
 */
public final class AgentControl implements AutoCloseable {
    final PolicyEvaluator evaluator;
    final String version;
    final BudgetLimits budget;
    final DataLimits dataLimits;
    final LabelStore labelStore;
    final InjectionRuntime injection;
    final EnforcementMode mode;
    final ApprovalResolver approvalResolver;
    final long approvalTimeoutMillis;
    final ThreadPoolExecutor approvals;
    private final AuditSink audit;
    private final io.agt.core.audit.AuditBodyPolicy auditBodyPolicy;
    final ThreadPoolExecutor workers;
    final ScheduledThreadPoolExecutor deadlines;
    final ThreadPoolExecutor externalCancellations;
    final Set<io.agt.core.execution.ExternalExecutionRegistry> externalRegistries =
            ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor failureAudit;
    private final ThreadLocal<HostSession> current = new ThreadLocal<>();
    private final Set<HostSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile boolean auditFailed;
    private final java.util.Map<String, io.agt.core.tools.ToolLease> reviewedTools;

    /** 宿主适配器在向模型公开工具元数据前查询固定版本要求。 */
    public String requiredToolBinding(String name) {
        return evaluator.toolBinding(Identifiers.require(name));
    }

    public io.agt.core.tools.ToolLease reviewedTool(String name) {
        var lease = reviewedTools.get(Identifiers.require(name));
        if (lease == null) {
            if (!reviewedTools.isEmpty() || evaluator.toolBinding(name) != null)
                throw new GovernanceException("tool_version_unregistered");
            return null;
        }
        lease.check();
        return lease;
    }

    public void validateToolSpecification(
            String name, java.util.Map<String, Object> specification) {
        var lease = reviewedTool(name);
        if (lease != null) lease.validateSpecification(specification);
    }

    public void validateToolSpecification(
            String name, java.util.Map<String, Object> specification, TrustedContext identity) {
        validateToolSpecification(name, specification);
        var lease = reviewedTool(name);
        if (lease != null) {
            lease.validate(identity);
            if (evaluator instanceof io.agt.core.policy.StructuredPolicy policy
                    && !identity.permissions()
                            .containsAll(policy.document().tools().get(name).permissions()))
                throw new GovernanceException("tool_version_permission_denied");
        }
    }

    private AgentControl(Builder b) {
        evaluator = Objects.requireNonNull(b.evaluator);
        var pins = new java.util.LinkedHashMap<String, io.agt.core.tools.ToolLease>();
        for (var lease : b.reviewedTools) {
            lease.check();
            if (!Objects.equals(evaluator.toolBinding(lease.name()), lease.identity())
                    || pins.putIfAbsent(lease.name(), lease) != null)
                throw new GovernanceException("tool_version_binding_denied");
        }
        reviewedTools = java.util.Map.copyOf(pins);
        if (evaluator instanceof io.agt.core.policy.StructuredPolicy policy)
            policy.document()
                    .tools()
                    .forEach(
                            (name, rule) -> {
                                if (rule.binding() != null && !reviewedTools.containsKey(name))
                                    throw new GovernanceException("tool_version_unregistered");
                            });
        version = Identifiers.require(evaluator.version());
        budget = Objects.requireNonNull(b.budget);
        dataLimits = Objects.requireNonNull(b.dataLimits);
        labelStore = b.labelStore;
        mode = Objects.requireNonNull(b.mode);
        audit = Objects.requireNonNull(b.audit, "An audit sink is required");
        auditBodyPolicy = Objects.requireNonNull(audit.bodyPolicy());
        var injectionChecks = java.util.List.copyOf(evaluator.injectionChecks());
        for (var check : injectionChecks)
            if (!evaluator.interventionPoints().containsAll(check.points()))
                throw new GovernanceException("injection_points_not_configured");
        injection = new InjectionRuntime(injectionChecks, b.detectors);
        approvalResolver = b.approvalResolver;
        approvalTimeoutMillis = b.approvalTimeoutMillis;
        AsyncLimits limits = Objects.requireNonNull(b.asyncLimits);
        externalCancellations =
                new ThreadPoolExecutor(
                        2,
                        2,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(128),
                        task -> {
                            var thread = new Thread(task, "agt-external-cancel");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        workers =
                new ThreadPoolExecutor(
                        limits.workerThreads(),
                        limits.workerThreads(),
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(limits.queueCapacity()),
                        r -> {
                            Thread t = new Thread(r, "agt-worker");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        failureAudit =
                new ThreadPoolExecutor(
                        1,
                        1,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(limits.queueCapacity()),
                        r -> {
                            Thread t = new Thread(r, "agt-failure-audit");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        deadlines =
                new ScheduledThreadPoolExecutor(
                        1,
                        r -> {
                            Thread t = new Thread(r, "agt-deadline");
                            t.setDaemon(true);
                            return t;
                        });
        deadlines.setRemoveOnCancelPolicy(true);
        approvals =
                new ThreadPoolExecutor(
                        2,
                        2,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(32),
                        r -> {
                            Thread t = new Thread(r, "agt-approval");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    public static Builder builder(PolicyEvaluator evaluator) {
        return new Builder(evaluator);
    }

    public static Builder fromPath(Path path) {
        return builder(StructuredPolicy.fromPath(path));
    }

    public StructuredPolicy.Document exportAuthorizationPolicy() {
        checkRuntime();
        if (mode != EnforcementMode.ENFORCE || !(evaluator instanceof StructuredPolicy policy)) {
            throw new GovernanceException("delegation_policy_export_unsupported");
        }
        return policy.document();
    }

    public void requirePoints(Set<InterventionPoint> points) {
        if (!evaluator.interventionPoints().containsAll(points)) {
            throw new GovernanceException("point_not_configured");
        }
    }

    /** 整体输出检测不能由尚未完成检查的实时预览绕过。 */
    public boolean requiresCompleteOutputCheck() {
        return injection.requiresCompleteOutputCheck();
    }

    public HostSession openSession(TrustedContext context) {
        return createSession(context, false, null);
    }

    /**
     * 不隐式绑定调用线程，允许 future 完成后在完成线程关闭。
     *
     * @param context 可信上下文
     * @return 独立绑定的宿主会话
     */
    public HostSession openDetachedSession(TrustedContext context) {
        return createSession(context, true, null);
    }

    public HostSession openSession(TrustedContext context, ExecutionContext execution) {
        return createSession(context, false, Objects.requireNonNull(execution));
    }

    public HostSession openDetachedSession(TrustedContext context, ExecutionContext execution) {
        return createSession(context, true, Objects.requireNonNull(execution));
    }

    /** 显式派生可跨线程绑定的会话；身份来自宿主，链路头不能授予委托权限。 */
    public HostSession openDelegatedSession(HostSession parent, TrustedContext context) {
        return openDelegatedSession(parent, context, null);
    }

    public HostSession openDelegatedSession(
            HostSession parent, TrustedContext context, ExecutionContext execution) {
        return openDelegatedSession(parent, context, execution, null);
    }

    public HostSession openDelegatedSession(
            HostSession parent,
            TrustedContext context,
            ExecutionContext execution,
            DelegationScope scope) {
        Objects.requireNonNull(parent);
        Objects.requireNonNull(context);
        if (parent.control() != this) {
            throw new GovernanceException("delegation_runtime_mismatch");
        }
        var issuer = parent.context();
        if (!issuer.tenantId().equals(context.tenantId())
                || !issuer.principalId().equals(context.principalId())
                || !issuer.permissions().containsAll(context.permissions())) {
            throw new GovernanceException("delegation_scope_denied");
        }
        if (scope != null && parent.delegationScope() != null) {
            scope.requireNarrowerThan(parent.delegationScope());
        }
        return createSession(context, true, execution, parent, null, scope);
    }

    private HostSession createSession(
            TrustedContext context, boolean detached, ExecutionContext execution) {
        return createSession(context, detached, execution, null);
    }

    /** 创建 detached 根任务；后续显式派生的所有会话共享这些上限。 */
    public HostSession openTaskSession(TrustedContext context, TaskLimits limits) {
        return createSession(context, true, null, null, Objects.requireNonNull(limits));
    }

    public HostSession openTaskSession(
            TrustedContext context, TaskLimits limits, ExecutionContext execution) {
        return createSession(context, true, execution, null, Objects.requireNonNull(limits));
    }

    public HostSession openTaskSession(
            TrustedContext context,
            TaskLimits limits,
            ExecutionContext execution,
            DelegationScope scope) {
        return createSession(
                context,
                true,
                execution,
                null,
                Objects.requireNonNull(limits),
                Objects.requireNonNull(scope));
    }

    private HostSession createSession(
            TrustedContext context,
            boolean detached,
            ExecutionContext execution,
            HostSession parent) {
        return createSession(context, detached, execution, parent, null);
    }

    private HostSession createSession(
            TrustedContext context,
            boolean detached,
            ExecutionContext execution,
            HostSession parent,
            TaskLimits limits) {
        return createSession(context, detached, execution, parent, limits, null);
    }

    private HostSession createSession(
            TrustedContext context,
            boolean detached,
            ExecutionContext execution,
            HostSession parent,
            TaskLimits limits,
            DelegationScope scope) {
        return createSession(context, detached, execution, parent, limits, scope, null);
    }

    /** 已由宿主验证的在线授权；不能从 Trace 头创建。 */
    public HostSession openAuthorizedSession(
            SessionAuthority authority, TaskLimits limits, ExecutionContext execution) {
        Objects.requireNonNull(authority);
        Objects.requireNonNull(limits);
        authority.validateRuntime(evaluator, budget, dataLimits, mode);
        if (limits.maxDelegationDepth() > authority.remainingDelegationDepth()) {
            throw new GovernanceException("delegation_depth_exceeded");
        }
        return createSession(authority.identity(), true, execution, null, limits, null, authority);
    }

    private HostSession createSession(
            TrustedContext context,
            boolean detached,
            ExecutionContext execution,
            HostSession parent,
            TaskLimits limits,
            DelegationScope scope,
            SessionAuthority authority) {
        Objects.requireNonNull(context);
        checkRuntime();
        requirePoints(
                EnumSet.of(InterventionPoint.AGENT_STARTUP, InterventionPoint.AGENT_SHUTDOWN));
        HostSession session =
                new HostSession(
                        this,
                        context,
                        detached ? null : current.get(),
                        detached,
                        execution,
                        parent,
                        limits,
                        scope,
                        authority);
        sessions.add(session);
        try {
            session.attachTask();
            session.attachAuthority();
            if (parent != null) parent.attachChild(session);
            session.evaluate(InterventionPoint.AGENT_STARTUP, "startup", null, Map.of());
            checkRuntime();
            if (!detached) {
                current.set(session);
            }
            session.startDeadline();
            return session;
        } catch (RuntimeException e) {
            sessions.remove(session);
            session.revoke();
            session.detachTask();
            session.closeAuthority();
            if (parent != null) parent.detachChild(session);
            throw e;
        }
    }

    public HostSession currentSession() {
        checkRuntime();
        HostSession session = current.get();
        if (session == null) {
            throw new GovernanceException("missing_session");
        }
        session.checkActive();
        return session;
    }

    public Object evaluate(InterventionPoint point, Object target) {
        return currentSession().evaluate(point, UUID.randomUUID().toString(), null, target);
    }

    public String run(String input, Function<String, String> execute) {
        return currentSession()
                .run(input, v -> execute.apply((String) v), v -> v, (original, v) -> (String) v);
    }

    public String runTool(
            String name,
            Map<String, Object> arguments,
            Function<Map<String, Object>, String> execute) {
        return currentSession()
                .operation(
                        true,
                        name,
                        arguments,
                        v -> {
                            if (!(v instanceof Map<?, ?>)) {
                                throw new GovernanceException("invalid_tool_arguments");
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> effective = (Map<String, Object>) v;
                            return execute.apply(effective);
                        },
                        v -> v,
                        (original, v) -> (String) v);
    }

    void checkRuntime() {
        if (closed) {
            throw new GovernanceException("runtime_closed");
        }
        if (auditFailed) {
            throw new GovernanceException("audit_failed");
        }
        if (!version.equals(evaluator.version())) {
            throw new GovernanceException("policy_version_changed");
        }
    }

    void emit(
            io.agt.core.audit.AuditScope scope,
            AuditRecord record,
            boolean capture,
            Object original,
            Object effective) {
        boolean failedWrite = false;
        synchronized (this) {
            if (auditFailed) {
                throw new GovernanceException("audit_failed");
            }
            try {
                var body =
                        capture
                                ? auditBodyPolicy.capture(record.point(), original, effective)
                                : io.agt.core.audit.AuditBody.omitted("not_collected");
                audit.write(
                        new io.agt.core.audit.AuditEvent(
                                java.util.UUID.randomUUID().toString(), scope, record, body));
            } catch (Exception e) {
                auditFailed = true;
                failedWrite = true;
            }
        }
        // 不持有 runtime 锁取消 future，避免与会话/完成回调发生锁序反转。
        if (failedWrite) {
            sessions.forEach(s -> s.refuse("audit_failed"));
            throw new GovernanceException("audit_failed");
        }
    }

    HostSession swapSession(HostSession session) {
        HostSession old = current.get();
        if (session == null) {
            current.remove();
        } else {
            current.set(session);
        }
        return old;
    }

    /**
     * 提交时捕获会话；受控入口检查有效性，finally 恢复绑定。不拦截任意业务 Runnable。
     *
     * @param executor 底层执行器
     * @return 携带当前会话上下文的执行器
     */
    public Executor contextualExecutor(Executor executor) {
        Objects.requireNonNull(executor);
        return task -> {
            HostSession session = currentSession();
            try {
                executor.execute(
                        () -> {
                            try (var scope = session.bind()) {
                                task.run();
                            }
                        });
            } catch (RejectedExecutionException e) {
                throw session.refuse("async_capacity_exceeded");
            }
        };
    }

    public Executor asyncExecutor() {
        return contextualExecutor(workers);
    }

    public CompletableFuture<String> runAsync(
            String input, Function<String, ? extends CompletionStage<String>> execute) {
        try {
            return currentSession()
                    .runAsync(input, v -> execute.apply((String) v), v -> v, (v, e) -> (String) e);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 为 HTTP/异步服务管理会话生命周期；回调应使用受控 Builder 或 core runAsync。
     *
     * @param <T> 返回值类型
     * @param context 可信上下文
     * @param invoke 异步执行提供方
     * @return 治理包装后的 CompletableFuture
     */
    public <T> CompletableFuture<T> withSessionAsync(
            TrustedContext context,
            java.util.function.Supplier<? extends CompletionStage<T>> invoke) {
        HostSession session;
        try {
            session = openDetachedSession(context);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<T> source;
        try (var scope = session.bind()) {
            source = Objects.requireNonNull(invoke.get()).toCompletableFuture();
        } catch (RuntimeException e) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
            }
            return CompletableFuture.failedFuture(
                    e instanceof GovernanceException
                            ? e
                            : new GovernanceException("async_execution_failed"));
        }
        var result =
                new CompletableFuture<T>() {
                    @Override
                    public boolean cancel(boolean interrupt) {
                        boolean changed = super.cancel(interrupt);
                        if (changed) {
                            source.cancel(interrupt);
                        }
                        return changed;
                    }
                };
        source.whenComplete(
                (value, error) -> {
                    Throwable failure = error;
                    while (failure instanceof CompletionException && failure.getCause() != null) {
                        failure = failure.getCause();
                    }
                    try (var scope = session.bind()) {
                        try {
                            session.close();
                        } catch (RuntimeException closeError) {
                            failure = closeError;
                        }
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(
                                    failure instanceof GovernanceException
                                                    || failure instanceof CancellationException
                                            ? failure
                                            : new GovernanceException("async_execution_failed"));
                        }
                    }
                });
        return result;
    }

    void auditFailureAsync(Runnable write) {
        try {
            failureAudit.execute(
                    () -> {
                        try {
                            write.run();
                        } catch (RuntimeException e) {
                            /* emit 已使运行时故障关闭。 */
                        }
                    });
        } catch (RejectedExecutionException e) {
            if (!auditFailed) {
                auditFailed = true;
                sessions.forEach(s -> s.refuse("audit_failed"));
            }
        }
    }

    void detach(HostSession session, HostSession previous) {
        sessions.remove(session);
        if (current.get() == session) {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        sessions.forEach(s -> s.refuse("runtime_closed"));
        externalRegistries.forEach(io.agt.core.execution.ExternalExecutionRegistry::runtimeClosed);
        externalRegistries.clear();
        approvals.shutdownNow();
        workers.shutdownNow();
        injection.close();
        deadlines.shutdownNow();
        externalCancellations.shutdown();
        try {
            // 正常终止证据已排队时给出有界发布机会；不能在正常节点退出时直接丢弃回执。
            if (!externalCancellations.awaitTermination(2, TimeUnit.SECONDS))
                externalCancellations.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            externalCancellations.shutdownNow();
        }
        failureAudit.shutdown();
        try {
            if (!failureAudit.awaitTermination(5, TimeUnit.SECONDS)) {
                auditFailed = true;
                failureAudit.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failureAudit.shutdownNow();
        }
        current.remove();
    }

    public static final class Builder {
        private java.util.List<io.agt.core.tools.ToolLease> reviewedTools = java.util.List.of();

        public Builder reviewedTools(java.util.Collection<io.agt.core.tools.ToolLease> leases) {
            reviewedTools = java.util.List.copyOf(leases);
            if (reviewedTools.size() > 128)
                throw new IllegalArgumentException("Too many reviewed tools");
            return this;
        }

        private final PolicyEvaluator evaluator;
        private BudgetLimits budget;
        private DataLimits dataLimits;
        private LabelStore labelStore;
        private EnforcementMode mode = EnforcementMode.ENFORCE;
        private AuditSink audit;
        private ApprovalResolver approvalResolver;
        private long approvalTimeoutMillis = 30_000;
        private AsyncLimits asyncLimits = AsyncLimits.defaults();
        private final Map<String, DetectorBinding> detectors = new java.util.LinkedHashMap<>();

        private Builder(PolicyEvaluator evaluator) {
            this.evaluator = Objects.requireNonNull(evaluator);
            budget = evaluator instanceof StructuredPolicy p ? p.budget() : BudgetLimits.defaults();
            dataLimits =
                    evaluator instanceof StructuredPolicy p
                            ? p.dataLimits()
                            : DataLimits.defaults();
        }

        public Builder dataLimits(DataLimits value) {
            dataLimits = Objects.requireNonNull(value);
            return this;
        }

        /** 持久标签由宿主管理生命周期和连接超时，不包含在模型可见配置或 Trace 中。 */
        public Builder labelStore(LabelStore value) {
            labelStore = Objects.requireNonNull(value);
            return this;
        }

        public Builder asyncLimits(AsyncLimits value) {
            asyncLimits = Objects.requireNonNull(value);
            return this;
        }

        public Builder detector(String name, String revision, InjectionDetector detector) {
            Identifiers.require(name);
            if (detectors.putIfAbsent(name, new DetectorBinding(revision, detector)) != null)
                throw new IllegalArgumentException("Duplicate detector binding");
            return this;
        }

        public Builder budget(BudgetLimits value) {
            budget = value;
            return this;
        }

        public Builder mode(EnforcementMode value) {
            mode = value;
            return this;
        }

        public Builder audit(AuditSink value) {
            audit = value;
            return this;
        }

        public Builder approvalResolver(ApprovalResolver value) {
            approvalResolver = value;
            return this;
        }

        public Builder approvalTimeout(Duration value) {
            long ms = value.toMillis();
            if (ms < 1 || ms > 300_000) {
                throw new IllegalArgumentException("Approval timeout must be 1..300000 ms");
            }
            approvalTimeoutMillis = ms;
            return this;
        }

        public AgentControl build() {
            return new AgentControl(this);
        }
    }
}
