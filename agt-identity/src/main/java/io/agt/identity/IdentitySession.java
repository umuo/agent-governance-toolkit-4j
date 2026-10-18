package io.agt.identity;

import io.agt.core.*;
import io.agt.core.data.DataLimits;
import io.agt.core.delegation.*;
import io.agt.core.policy.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** 单次绑定的已认证根会话；不隐式授予本机派生权限，撤销检查贯穿准入与交付。 */
public final class IdentitySession implements SessionAuthority {
    private final JwtIdentityVerifier verifier;
    private final AuthenticatedActor actor;
    private final TrustedContext context;
    private final String policyId, taskId = UUID.randomUUID().toString();
    private final BudgetLimits budget;
    private final DataLimits data;
    private final List<AuthorityFrame> frames;
    private final AtomicBoolean attached = new AtomicBoolean(), closed = new AtomicBoolean();
    private final AtomicBoolean notified = new AtomicBoolean();
    private volatile String sessionId;
    private volatile Consumer<String> stop;
    private volatile ScheduledFuture<?> pulse, unattached;
    private volatile long unattachedDeadline;

    IdentitySession(
            JwtIdentityVerifier verifier,
            AuthenticatedActor actor,
            String agentId,
            StructuredPolicy policy,
            DelegationScope scope) {
        this.verifier = verifier;
        this.actor = actor;
        context = actor.context(agentId);
        policyId = PolicyFingerprints.digest(policy.document());
        budget = policy.budget();
        data = policy.dataLimits();
        frames = List.of(new AuthorityFrame(context, Objects.requireNonNull(scope)));
    }

    void expireUnattached() {
        unattachedDeadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(verifier.limits.unattachedMillis());
        var future =
                verifier.monitor.schedule(
                        this::close, verifier.limits.unattachedMillis(), TimeUnit.MILLISECONDS);
        unattached = future;
        if (closed.get() || attached.get()) future.cancel(false);
    }

    public TrustedContext identity() {
        checkActive();
        return context;
    }

    public String taskId() {
        return taskId;
    }

    public int remainingDelegationDepth() {
        return 0;
    }

    public long remainingMillis() {
        return actor.remainingMillis();
    }

    public List<AuthorityFrame> frames() {
        return frames;
    }

    public void validateRuntime(
            PolicyEvaluator evaluator,
            BudgetLimits actual,
            DataLimits actualData,
            EnforcementMode mode) {
        checkActive();
        if (!(evaluator instanceof StructuredPolicy policy)
                || !policyId.equals(PolicyFingerprints.digest(policy.document()))
                || mode != EnforcementMode.ENFORCE
                || actual.maxModelCalls() > budget.maxModelCalls()
                || actual.maxToolCalls() > budget.maxToolCalls()
                || actual.maxDurationMillis() > budget.maxDurationMillis()
                || actualData.maxCalls() > data.maxCalls()
                || actualData.maxItems() > data.maxItems())
            throw new GovernanceException("identity_runtime_denied");
        if (attached.get()) throw new GovernanceException("identity_session_already_bound");
    }

    public void attach(String id, Consumer<String> terminate) {
        checkActive();
        if (!attached.compareAndSet(false, true))
            throw new GovernanceException("identity_session_already_bound");
        sessionId = Identifiers.require(id);
        stop = Objects.requireNonNull(terminate);
        if (unattached != null) unattached.cancel(false);
        try {
            pulse =
                    verifier.monitor.scheduleWithFixedDelay(
                            () -> {
                                try {
                                    checkActive();
                                } catch (GovernanceException denied) {
                                    revoke(denied.code());
                                }
                            },
                            verifier.limits.monitorIntervalMillis(),
                            verifier.limits.monitorIntervalMillis(),
                            TimeUnit.MILLISECONDS);
            if (closed.get()) pulse.cancel(false);
        } catch (RejectedExecutionException ended) {
            close();
            throw new GovernanceException("identity_verifier_closed");
        }
    }

    public void checkActive() {
        if (closed.get()) throw new GovernanceException("identity_session_closed");
        actor.checkActive();
        // 定时器可能因其他状态查询而延迟；实际准入仍按单调期限拒绝。
        long deadline = unattachedDeadline;
        if (!attached.get() && deadline != 0 && System.nanoTime() - deadline >= 0) close();
        if (closed.get()) throw new GovernanceException("identity_session_closed");
    }

    public void validate(PolicyRequest request) {
        // 核心先关闭授权再写终止审计；仅允许绑定会话记录 shutdown，不恢复业务权限。
        if (request.point() != InterventionPoint.AGENT_SHUTDOWN) checkActive();
        if (!context.equals(request.context()) || !Objects.equals(sessionId, request.sessionId()))
            throw new GovernanceException("identity_session_binding_denied");
    }

    public void commit(String actionId, InterventionPoint point, String tool, Object effective) {
        validate(new PolicyRequest(point, context, sessionId, actionId, tool, effective));
    }

    @Override
    public void commit(PolicyRequest request) {
        validate(request);
    }

    private void revoke(String reason) {
        var terminate = stop;
        close();
        if (terminate != null && notified.compareAndSet(false, true)) {
            try {
                terminate.accept(reason);
            } catch (RuntimeException ignored) {
                /* 回调失败也不能恢复已经关闭的权限。 */
            }
        }
    }

    void revoked() {
        revoke("identity_verifier_closed");
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (pulse != null) pulse.cancel(false);
        if (unattached != null) unattached.cancel(false);
        verifier.release(this);
    }

    @Override
    public String toString() {
        return "IdentitySession[redacted]";
    }
}
