package io.agt.delegation;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.usage.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

class RemoteUsageLeaseTest {
    @BeforeAll
    static void keys() throws Exception {
        DelegationTest.keyPair();
    }

    static final class Manual implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final InMemoryGrantRegistry registry = new InMemoryGrantRegistry(clock);
        final AgentControl control = DelegationTest.control();
        final HostSession source =
                control.openTaskSession(
                        DelegationTest.SOURCE,
                        new TaskLimits(
                                DelegationTest.CALLS,
                                1000,
                                10,
                                3,
                                null,
                                RemoteUsageTest.limits(1000, "1")),
                        null,
                        RemoteUsageTest.scope());
        final DelegationAuthority authority = DelegationTest.authority(registry, clock);
        final GrantLease root = LeaseSecrets.create("manual-root");

        Manual() {
            var state = source.exportDelegationState();
            var doc =
                    new GrantDocument(
                            root.grantId(),
                            DelegationTest.ISSUER,
                            DelegationTest.ISSUER,
                            source.context(),
                            source.taskSnapshot().taskId(),
                            null,
                            0,
                            3,
                            clock.millis(),
                            clock.millis() + 30000,
                            source.authorityFrames(),
                            state.quota(),
                            GrantCodec.policyDigest(control.exportAuthorizationPolicy()),
                            source.context(),
                            source.runtimeBudget(),
                            source.dataLimits(),
                            state.usage().limits());
            registry.registerRoot(doc, root, source.sessionId(), state.usage());
        }

        GrantLease receiver(GrantLease parent, String session) {
            var token =
                    authority.issue(
                            parent,
                            "reader",
                            DelegationTest.AUDIENCE,
                            Set.of("read"),
                            RemoteUsageTest.scope(),
                            Duration.ofSeconds(20));
            var claims =
                    DelegationJwt.verify(
                            token,
                            DelegationTest.ISSUER,
                            DelegationTest.AUDIENCE,
                            Map.of("key-1", (RSAPublicKey) DelegationTest.keys.getPublic()),
                            clock);
            var lease = LeaseSecrets.create(claims.grantId());
            registry.claim(
                    claims.grantId(), claims.digest(), "reader", DelegationTest.AUDIENCE, lease);
            registry.bind(lease, session);
            return lease;
        }

        UsageBudget.Snapshot snapshot() {
            return registry.usageSnapshot(root, source.sessionId());
        }

        public void close() {
            registry.revoke(root.grantId());
            authority.close();
            source.close();
            control.close();
        }
    }

    @Test
    void expiredPreparedReservationRefundsWithoutFreezingHealthySibling() {
        try (var f = new Manual()) {
            var a = f.receiver(f.root, "a");
            var b = f.receiver(f.root, "b");
            f.registry.usageReserve(a, "a", "prepared", "fixture", 100, 50);
            f.clock.millis.addAndGet(1500);
            f.registry.heartbeatRoot(f.root);
            f.registry.check(b, "b");
            f.clock.millis.addAndGet(1000);
            assertEquals(0, f.snapshot().heldTokens());
            assertNull(f.snapshot().fault());
            f.registry.usageReserve(b, "b", "next", "fixture", 100, 50);
            assertEquals(150, f.snapshot().heldTokens());
            assertThrows(GovernanceException.class, () -> f.registry.check(a, "a"));
            f.clock.millis.addAndGet(-1000);
            assertThrows(GovernanceException.class, () -> f.registry.check(a, "a"));
        }
    }

    @Test
    void expiredDispatchedReservationQuarantinesHealthySibling() {
        try (var f = new Manual()) {
            var a = f.receiver(f.root, "a");
            var b = f.receiver(f.root, "b");
            f.registry.usageReserve(a, "a", "dispatched", "fixture", 100, 50);
            f.registry.usageDispatch(a, "a", "dispatched");
            f.clock.millis.addAndGet(1500);
            f.registry.heartbeatRoot(f.root);
            f.registry.check(b, "b");
            f.clock.millis.addAndGet(1000);
            assertEquals("model_usage_unconfirmed", f.snapshot().fault());
            assertEquals(150, f.snapshot().heldTokens());
            assertEquals(1, f.snapshot().uncertainCalls());
            assertThrows(GovernanceException.class, () -> f.registry.check(b, "b"));
        }
    }

    @Test
    void ancestorExpiryQuarantinesAnOtherwiseRenewedDescendantReceipt() {
        try (var f = new Manual()) {
            var a = f.receiver(f.root, "a");
            var b = f.receiver(f.root, "b");
            var child = f.receiver(a, "child");
            f.registry.usageReserve(child, "child", "pending", "fixture", 100, 50);
            f.registry.usageDispatch(child, "child", "pending");
            f.clock.millis.addAndGet(1500);
            f.registry.heartbeatRoot(f.root);
            f.registry.check(b, "b");
            f.registry.check(child, "child");
            f.clock.millis.addAndGet(1000);
            assertEquals("model_usage_unconfirmed", f.snapshot().fault());
            assertEquals(150, f.snapshot().heldTokens());
            assertThrows(GovernanceException.class, () -> f.registry.check(b, "b"));
        }
    }

    @Test
    void authenticatedCallerAndClosedLeaseCannotBeReusedToSpend() {
        try (var f = new Manual()) {
            var lease = f.receiver(f.root, "receiver");
            var service = new GrantStatusService(f.registry);
            var body =
                    Map.of(
                            "op",
                            "usage_reserve",
                            "lease",
                            Map.of("grantId", lease.grantId(), "secret", lease.secret()),
                            "sessionId",
                            "receiver",
                            "reservationId",
                            "receipt",
                            "model",
                            "fixture",
                            "input",
                            100,
                            "output",
                            50);
            assertEquals(
                    403,
                    service.handle(new AuthenticatedAgent("impostor"), JsonValues.write(body))
                            .status());
            assertEquals(0, f.snapshot().heldTokens());
            assertEquals(
                    200,
                    service.handle(new AuthenticatedAgent("reader"), JsonValues.write(body))
                            .status());
            f.registry.complete(lease, "receiver");
            assertEquals(0, f.snapshot().heldTokens());
            assertEquals(
                    403,
                    service.handle(new AuthenticatedAgent("reader"), JsonValues.write(body))
                            .status());
            assertEquals(
                    400,
                    service.handle(
                                    new AuthenticatedAgent("reader"),
                                    "{\"op\":\"check\",\"op\":\"complete\"}")
                            .status());
        }
    }

    private static final class MutableClock extends Clock {
        final AtomicLong millis = new AtomicLong(System.currentTimeMillis() / 1000 * 1000);

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public long millis() {
            return millis.get();
        }

        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }
    }
}
