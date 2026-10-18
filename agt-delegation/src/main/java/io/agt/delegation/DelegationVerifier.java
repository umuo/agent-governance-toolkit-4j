package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.data.DataLimits;
import io.agt.core.delegation.*;
import io.agt.core.flow.DataFlowLedger;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.usage.UsageLedger;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** 固定信任配置的接收端；每项操作和租约监测均检查在线状态，不在故障时回退为离线允许。 */
public final class DelegationVerifier implements AutoCloseable {
    private final String issuer;
    private final String audience;
    private final Map<String, RSAPublicKey> keys;
    private final GrantStatusClient registry;
    private final Clock clock;
    private final Semaphore slots = new Semaphore(16);
    private final Set<Permit> active = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService monitor =
            Executors.newScheduledThreadPool(
                    16,
                    task -> {
                        var thread = new Thread(task, "agt-grant-check");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final AtomicBoolean closed = new AtomicBoolean();

    public DelegationVerifier(
            String issuer,
            String audience,
            Map<String, RSAPublicKey> keys,
            GrantStatusClient registry,
            Clock clock) {
        this.issuer = Identifiers.require(issuer);
        this.audience = Identifiers.require(audience);
        this.keys = Map.copyOf(keys);
        if (keys.isEmpty() || keys.size() > 32)
            throw new IllegalArgumentException("Invalid trusted key set");
        keys.forEach(
                (kid, key) -> {
                    Identifiers.require(kid);
                    DelegationJwt.requireKey(key);
                });
        this.registry = Objects.requireNonNull(registry);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized Permit accept(DelegationToken token, AuthenticatedAgent caller) {
        if (closed.get()) throw new GovernanceException("delegation_verifier_closed");
        var claims = DelegationJwt.verify(token, issuer, audience, keys, clock);
        if (!claims.actor().equals(caller.agentId()))
            throw new GovernanceException("delegation_actor_denied");
        if (!slots.tryAcquire())
            throw new GovernanceException("delegation_receiver_capacity_exceeded");
        GrantLease lease = LeaseSecrets.create(claims.grantId());
        try {
            var doc =
                    call(
                            () ->
                                    registry.claim(
                                            claims.grantId(),
                                            claims.digest(),
                                            caller.agentId(),
                                            audience,
                                            lease));
            if (!doc.id().equals(claims.grantId())
                    || !doc.digest().equals(claims.digest())
                    || !doc.issuer().equals(issuer)
                    || !doc.audience().equals(audience)
                    || !doc.identity().agentId().equals(claims.actor())
                    || !doc.identity().principalId().equals(claims.subject())
                    || !doc.identity().tenantId().equals(claims.tenant())
                    || !doc.taskId().equals(claims.task())
                    || doc.issuedAt() != claims.issuedAt()
                    || doc.expiresAt() != claims.expiresAt())
                throw new GovernanceException("delegation_document_denied");
            var permit = new Permit(doc, lease);
            active.add(permit);
            permit.unattachedExpiry = monitor.schedule(permit::close, 2, TimeUnit.SECONDS);
            return permit;
        } catch (RuntimeException failure) {
            try {
                registry.complete(lease, null);
            } catch (RuntimeException ignored) {
                /* 在线状态未知，不能放行。 */
            }
            slots.release();
            throw failure;
        }
    }

    private <T> T call(java.util.function.Supplier<T> query) {
        try {
            return query.get();
        } catch (GovernanceException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new GovernanceException("delegation_registry_unavailable");
        }
    }

    public final class Permit implements SessionAuthority {
        private final GrantDocument doc;
        private final GrantLease lease;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean attached = new AtomicBoolean();
        private volatile String sessionId;
        private volatile ScheduledFuture<?> pulse;
        private volatile ScheduledFuture<?> unattachedExpiry;
        private volatile StructuredPolicy policy;
        private final RemoteUsageLedger usage;
        private final RemoteFlowLedger flow;

        private Permit(GrantDocument doc, GrantLease lease) {
            this.doc = doc;
            this.lease = lease;
            this.usage =
                    doc.usageLimits() == null
                            ? null
                            : new RemoteUsageLedger(
                                    doc.usageLimits(),
                                    registry,
                                    lease,
                                    () -> sessionId,
                                    this::checkActive);
            this.flow =
                    doc.flowPolicy() == null
                            ? null
                            : new RemoteFlowLedger(
                                    GrantCodec.decodeFlowPolicy(doc.flowPolicy()),
                                    registry,
                                    lease,
                                    () -> sessionId,
                                    this::checkActive);
        }

        public TrustedContext identity() {
            return doc.identity();
        }

        public String taskId() {
            return doc.taskId();
        }

        public int remainingDelegationDepth() {
            return doc.maxDepth() - doc.depth();
        }

        public long remainingMillis() {
            return doc.expiresAt() - clock.millis();
        }

        public List<AuthorityFrame> frames() {
            return doc.frames();
        }

        public UsageLedger usageLedger() {
            return usage;
        }

        public DataFlowLedger dataFlowLedger() {
            return flow;
        }

        public io.agt.core.execution.ExternalExecutionLedger externalExecutionLedger() {
            return new RemoteExternalLedger(registry, lease, () -> sessionId, doc.taskId());
        }

        public GrantLease lease() {
            return lease;
        }

        public void validateRuntime(
                PolicyEvaluator evaluator,
                BudgetLimits budget,
                DataLimits data,
                EnforcementMode mode) {
            if (!(evaluator instanceof StructuredPolicy structured)
                    || mode != EnforcementMode.ENFORCE
                    || !GrantCodec.policyDigest(structured.document()).equals(doc.policyDigest())
                    || budget.maxModelCalls() > doc.sessionBudget().maxModelCalls()
                    || budget.maxToolCalls() > doc.sessionBudget().maxToolCalls()
                    || budget.maxDurationMillis() > doc.sessionBudget().maxDurationMillis()
                    || data.maxCalls() > doc.dataLimits().maxCalls()
                    || data.maxItems() > doc.dataLimits().maxItems()) {
                throw new GovernanceException("delegation_runtime_policy_denied");
            }
            policy = structured;
        }

        public void validate(PolicyRequest request) {
            if (policy == null) throw new GovernanceException("delegation_runtime_policy_denied");
            var owner = doc.policyOwner();
            var bound =
                    new TrustedContext(
                            owner.agentId(),
                            owner.tenantId(),
                            owner.principalId(),
                            request.context().permissions());
            policy.validateEffective(
                    new PolicyRequest(
                            request.point(),
                            bound,
                            request.sessionId(),
                            request.actionId(),
                            request.toolName(),
                            request.target(),
                            request.effect(),
                            request.toolBinding()));
        }

        public synchronized void attach(String session, Consumer<String> terminate) {
            if (stopped.get() || !attached.compareAndSet(false, true))
                throw new GovernanceException("delegation_replay_denied");
            call(
                    () -> {
                        registry.bind(lease, session);
                        return null;
                    });
            sessionId = session;
            if (unattachedExpiry != null) unattachedExpiry.cancel(false);
            pulse =
                    monitor.scheduleWithFixedDelay(
                            () -> {
                                try {
                                    checkActive();
                                } catch (GovernanceException revoked) {
                                    try {
                                        terminate.accept(revoked.code());
                                    } finally {
                                        close();
                                    }
                                }
                            },
                            250,
                            250,
                            TimeUnit.MILLISECONDS);
        }

        public void checkActive() {
            if (usage != null && usage.failure() != null)
                throw new GovernanceException(usage.failure());
            if (stopped.get() || remainingMillis() <= 0 || sessionId == null)
                throw new GovernanceException("delegation_inactive_denied");
            call(
                    () -> {
                        registry.check(lease, sessionId);
                        return null;
                    });
        }

        public void commit(
                String actionId, InterventionPoint point, String toolName, Object effective) {
            checkActive();
            String kind =
                    switch (point) {
                        case PRE_MODEL_CALL -> "model";
                        case PRE_TOOL_CALL -> "tool";
                        case PRE_DATA_ACCESS -> "data";
                        default -> throw new GovernanceException("delegation_action_denied");
                    };
            String digest =
                    JsonValues.digest(
                            Map.of(
                                    "point",
                                    point.wireName(),
                                    "tool",
                                    toolName == null ? "" : toolName,
                                    "effective",
                                    Collections.singletonList(effective)));
            call(
                    () -> {
                        if (flow == null) registry.commit(lease, sessionId, actionId, kind, digest);
                        else
                            registry.guardedCommit(
                                    lease,
                                    sessionId,
                                    actionId,
                                    point,
                                    toolName,
                                    flow.projection(point, toolName, effective),
                                    digest);
                        return null;
                    });
        }

        public synchronized void close() {
            if (stopped.compareAndSet(false, true)) {
                if (usage != null) usage.stop();
                if (pulse != null) pulse.cancel(false);
                if (unattachedExpiry != null) unattachedExpiry.cancel(false);
                try {
                    registry.complete(lease, sessionId);
                } catch (RuntimeException unknown) {
                    /* 不能确认远端结束时，最多等待租约到期，绝不续租。 */
                }
                active.remove(this);
                slots.release();
            }
        }

        @Override
        public String toString() {
            return "VerifiedDelegation[redacted]";
        }
    }

    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            active.forEach(Permit::close);
            monitor.shutdownNow();
        }
    }
}
