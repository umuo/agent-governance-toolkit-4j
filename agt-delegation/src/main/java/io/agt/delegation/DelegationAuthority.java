package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.delegation.*;
import io.agt.core.flow.DataFlowState;
import io.agt.core.usage.UsageBudget;
import java.security.interfaces.RSAPrivateKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单个授权服务的签发入口，私钥仅留在该服务。在线注册表由同一权威服务共享。 */
public final class DelegationAuthority implements AutoCloseable {
    private final String issuer;
    private final String kid;
    private final RSAPrivateKey key;
    private final GrantRegistry registry;
    private final Clock clock;
    private final Set<Root> roots = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeat =
            Executors.newScheduledThreadPool(
                    2,
                    task -> {
                        var thread = new Thread(task, "agt-grant-source");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final AtomicBoolean closed = new AtomicBoolean();

    public DelegationAuthority(
            String issuer, String kid, RSAPrivateKey key, GrantRegistry registry, Clock clock) {
        this.issuer = Identifiers.require(issuer);
        this.kid = Identifiers.require(kid);
        this.key = Objects.requireNonNull(key);
        DelegationJwt.requireKey(key);
        this.registry = Objects.requireNonNull(registry);
        this.clock = Objects.requireNonNull(clock);
    }

    public Root register(HostSession source) {
        requireOpen();
        source.checkActive();
        var frames = source.authorityFrames();
        if (frames.isEmpty()) throw new GovernanceException("delegation_scope_required");
        String policyDigest = GrantCodec.policyDigest(source.control().exportAuthorizationPolicy());
        if (source.taskSnapshot().limits().maxDelegationDepth() <= source.delegationDepth())
            throw new GovernanceException("delegation_depth_exceeded");
        long now = clock.millis() / 1000 * 1000;
        long expiration =
                (clock.millis() + Math.min(600000, source.remainingDurationMillis())) / 1000 * 1000;
        if (expiration <= now) throw new GovernanceException("delegation_lifetime_denied");
        var exported = source.exportDelegationState();
        var doc =
                new GrantDocument(
                        UUID.randomUUID().toString(),
                        issuer,
                        issuer,
                        source.context(),
                        source.taskSnapshot().taskId(),
                        null,
                        0,
                        Math.max(
                                0,
                                source.taskSnapshot().limits().maxDelegationDepth()
                                        - source.delegationDepth()),
                        now,
                        expiration,
                        frames,
                        exported.quota(),
                        policyDigest,
                        source.context(),
                        source.runtimeBudget(),
                        source.dataLimits(),
                        exported.usage() == null ? null : exported.usage().limits(),
                        exported.flow() == null
                                ? null
                                : GrantCodec.encodeFlowPolicy(exported.flow().policy()));
        var lease = LeaseSecrets.create(doc.id());
        registry.registerRoot(
                doc,
                lease,
                source.sessionId(),
                exported.usage(),
                exported.flow(),
                exported.external());
        source.bindExportedExternalView(
                new RemoteExternalLedger(registry, lease, source::sessionId, doc.taskId()));
        Runnable activeView = () -> registry.check(lease, source.sessionId());
        source.bindExportedTaskView(
                activeView,
                exported.flow() == null
                        ? null
                        : new RemoteFlowLedger(
                                exported.flow().policy(),
                                registry,
                                lease,
                                source::sessionId,
                                activeView),
                exported.usage() == null
                        ? null
                        : new RemoteUsageLedger(
                                exported.usage().limits(),
                                registry,
                                lease,
                                source::sessionId,
                                activeView));
        var root = new Root(lease, source.sessionId());
        roots.add(root);
        root.pulse =
                heartbeat.scheduleWithFixedDelay(
                        () -> {
                            try {
                                source.checkActive();
                                registry.heartbeatRoot(lease);
                            } catch (RuntimeException stopped) {
                                root.close();
                            }
                        },
                        250,
                        250,
                        TimeUnit.MILLISECONDS);
        source.watchTermination(new CompletableFuture<>())
                .whenComplete((value, failure) -> root.close());
        return root;
    }

    public DelegationToken issue(
            GrantLease parent,
            String agent,
            String audience,
            Set<String> permissions,
            DelegationScope scope,
            Duration lifetime) {
        requireOpen();
        var previous = registry.parent(parent);
        if (!previous.issuer().equals(issuer))
            throw new GovernanceException("delegation_issuer_denied");
        if (lifetime == null
                || lifetime.isNegative()
                || lifetime.isZero()
                || lifetime.compareTo(Duration.ofMinutes(10)) > 0)
            throw new GovernanceException("delegation_lifetime_denied");
        var identity =
                new TrustedContext(
                        agent,
                        previous.identity().tenantId(),
                        previous.identity().principalId(),
                        permissions);
        if (!previous.identity().permissions().containsAll(permissions))
            throw new GovernanceException("delegation_scope_denied");
        scope.requireNarrowerThan(previous.frames().get(0).scope());
        var frames = new ArrayList<AuthorityFrame>();
        frames.add(new AuthorityFrame(identity, scope));
        frames.addAll(previous.frames());
        long now = clock.millis() / 1000 * 1000;
        long expiration =
                Math.min(
                        previous.expiresAt(), (clock.millis() + lifetime.toMillis()) / 1000 * 1000);
        var doc =
                new GrantDocument(
                        UUID.randomUUID().toString(),
                        issuer,
                        audience,
                        identity,
                        previous.taskId(),
                        previous.id(),
                        previous.depth() + 1,
                        previous.maxDepth(),
                        now,
                        expiration,
                        frames,
                        previous.quota(),
                        previous.policyDigest(),
                        previous.policyOwner(),
                        previous.sessionBudget(),
                        previous.dataLimits(),
                        previous.usageLimits(),
                        previous.flowPolicy());
        var token = DelegationJwt.sign(doc, kid, key);
        registry.issue(doc, parent);
        return token;
    }

    private void requireOpen() {
        if (closed.get()) throw new GovernanceException("delegation_authority_closed");
    }

    public final class Root implements AutoCloseable {
        private final GrantLease lease;
        private final String sessionId;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile ScheduledFuture<?> pulse;

        private Root(GrantLease lease, String sessionId) {
            this.lease = lease;
            this.sessionId = sessionId;
        }

        public GrantLease lease() {
            return lease;
        }

        /** 读取已交接的权威账本；不返回发送端冻结的本机旧快照。 */
        public UsageBudget.Snapshot usageSnapshot() {
            return registry.usageSnapshot(lease, sessionId);
        }

        public DataFlowState.Snapshot flowSnapshot() {
            return registry.flowSnapshot(lease, sessionId);
        }

        public void close() {
            if (stopped.compareAndSet(false, true)) {
                if (pulse != null) pulse.cancel(false);
                registry.revoke(lease.grantId());
                roots.remove(this);
            }
        }

        @Override
        public String toString() {
            return "RootDelegation[redacted]";
        }
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            RuntimeException failure = null;
            try {
                for (var root : roots) {
                    try {
                        root.close();
                    } catch (RuntimeException unavailable) {
                        if (failure == null) failure = unavailable;
                    }
                }
            } finally {
                heartbeat.shutdownNow();
                try {
                    // 等待已开始的 JDBC 心跳退出，再允许宿主销毁连接池或存储。
                    heartbeat.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) throw failure;
        }
    }
}
