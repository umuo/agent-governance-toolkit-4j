package io.agt.identity;

import static io.agt.identity.IdentityFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.delegation.*;
import io.agt.core.policy.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class IdentitySessionTest {
    static final BudgetLimits BUDGET = new BudgetLimits(5, 5, 60000);
    static final ParameterRule ARGUMENTS = ParameterRule.object(Map.of());

    static StructuredPolicy rules(String version) {
        return StructuredPolicy.builder(version)
                .budget(BUDGET)
                .tool("read", new ToolRule(Set.of("read"), ARGUMENTS, false))
                .build();
    }

    static DelegationScope scope() {
        return new DelegationScope("task", false, Map.of("read", ARGUMENTS), Map.of());
    }

    static TaskLimits limits() {
        return new TaskLimits(BUDGET, 10, 1, 0);
    }

    static String call(HostSession session, java.util.function.Function<Object, String> delegate) {
        return session.operation(
                true,
                "read",
                Map.of(),
                delegate,
                value -> value,
                (original, value) -> (String) value);
    }

    @Test
    void authenticatedIdentityBindsActualCoreSessionAndPolicy() {
        var policy = rules("identity-v1");
        try (var verifier = verifier(t -> true, new MutableClock());
                var control = AgentControl.builder(policy).audit(r -> {}).build()) {
            var actor = verifier.verify(token(claims()));
            try (var permit = actor.permit("agent", policy, scope());
                    var session = control.openAuthorizedSession(permit, limits(), null)) {
                assertEquals(actor.principalId(), session.context().principalId());
                assertEquals("ok", call(session, args -> "ok"));
                assertThrows(
                        GovernanceException.class,
                        () -> control.openAuthorizedSession(permit, limits(), null));
                assertThrows(
                        GovernanceException.class,
                        () ->
                                permit.validate(
                                        new PolicyRequest(
                                                InterventionPoint.PRE_TOOL_CALL,
                                                new TrustedContext(
                                                        "agent",
                                                        "tenant-b",
                                                        actor.principalId(),
                                                        Set.of("read")),
                                                session.sessionId(),
                                                "action",
                                                "read",
                                                Map.of())));
            }
            try (var permit = actor.permit("agent", policy, scope());
                    var other =
                            AgentControl.builder(rules("other-version")).audit(r -> {}).build()) {
                assertEquals(
                        "identity_runtime_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> other.openAuthorizedSession(permit, limits(), null))
                                .code());
            }
            try (var permit = actor.permit("agent", policy, scope());
                    var observe =
                            AgentControl.builder(policy)
                                    .mode(EnforcementMode.EVALUATE_ONLY)
                                    .audit(r -> {})
                                    .build()) {
                assertEquals(
                        "identity_runtime_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> observe.openAuthorizedSession(permit, limits(), null))
                                .code());
            }
        }
    }

    @Test
    void revokedIdentityCannotReachBusinessDelegate() {
        var active = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        var policy = rules("v1");
        try (var verifier = verifier(t -> active.get(), new MutableClock());
                var control = AgentControl.builder(policy).audit(r -> {}).build();
                var permit = verifier.verify(token(claims())).permit("agent", policy, scope());
                var session = control.openAuthorizedSession(permit, limits(), null)) {
            active.set(false);
            assertThrows(
                    GovernanceException.class,
                    () ->
                            call(
                                    session,
                                    args -> {
                                        calls.incrementAndGet();
                                        return "secret";
                                    }));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void revocationDuringBusinessCallSuppressesLateResultAndStopsSession() throws Exception {
        var active = new AtomicBoolean(true);
        var policy = rules("v1");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var verifier =
                        new JwtIdentityVerifier(
                                policy(1000),
                                Map.of("key-a", (RSAPublicKey) KEY.getPublic()),
                                t -> active.get(),
                                new MutableClock(),
                                new IdentityLimits(2, 4, 32, 100, 1000));
                var control = AgentControl.builder(policy).audit(r -> {}).build();
                var permit = verifier.verify(token(claims())).permit("agent", policy, scope());
                var session = control.openAuthorizedSession(permit, limits(), null)) {
            var worker = Executors.newSingleThreadExecutor();
            try {
                var result =
                        worker.submit(
                                () ->
                                        call(
                                                session,
                                                args -> {
                                                    entered.countDown();
                                                    try {
                                                        release.await();
                                                    } catch (InterruptedException interrupted) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                    return "late secret";
                                                }));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                active.set(false);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!verifier.sessions.isEmpty() && System.nanoTime() < deadline)
                    Thread.sleep(10);
                assertTrue(verifier.sessions.isEmpty());
                assertThrows(GovernanceException.class, session::checkActive);
                release.countDown();
                assertInstanceOf(
                        GovernanceException.class,
                        assertThrows(
                                        ExecutionException.class,
                                        () -> result.get(3, TimeUnit.SECONDS))
                                .getCause());
            } finally {
                release.countDown();
                worker.shutdownNow();
            }
        }
    }

    @Test
    void unattachedPermitsExpireAndReleaseTheirSlotOnlyOnce() throws Exception {
        var policy = rules("v1");
        try (var verifier =
                new JwtIdentityVerifier(
                        policy(1000),
                        Map.of("key-a", (RSAPublicKey) KEY.getPublic()),
                        t -> true,
                        new MutableClock(),
                        new IdentityLimits(1, 2, 4, 100, 100))) {
            var actor = verifier.verify(token(claims()));
            var first = actor.permit("agent", policy, scope());
            assertEquals(
                    "identity_session_capacity",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> actor.permit("agent", policy, scope()))
                            .code());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!verifier.sessions.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(verifier.sessions.isEmpty());
            assertThrows(GovernanceException.class, first::checkActive);
            first.close();
            try (var second = actor.permit("agent", policy, scope())) {
                assertEquals(
                        "identity_session_capacity",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> actor.permit("agent", policy, scope()))
                                .code());
            }
        }
    }

    @Test
    void closingVerifierTerminatesBoundSession() {
        var policy = rules("v1");
        var verifier = verifier(t -> true, new MutableClock());
        try (var control = AgentControl.builder(policy).audit(r -> {}).build();
                var permit = verifier.verify(token(claims())).permit("agent", policy, scope());
                var session = control.openAuthorizedSession(permit, limits(), null)) {
            verifier.close();
            assertThrows(GovernanceException.class, session::checkActive);
            assertThrows(GovernanceException.class, permit::checkActive);
        } finally {
            verifier.close();
        }
    }

    @Test
    void delayedSchedulerCannotExtendUnattachedPermitDeadline() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(2);
        try (var verifier =
                new JwtIdentityVerifier(
                        policy(1000),
                        Map.of("key-a", (RSAPublicKey) KEY.getPublic()),
                        t -> true,
                        new MutableClock(),
                        new IdentityLimits(1, 2, 4, 100, 100))) {
            for (int i = 0; i < 2; i++)
                verifier.monitor.execute(
                        () -> {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException stopped) {
                                Thread.currentThread().interrupt();
                            }
                        });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var actor = verifier.verify(token(claims()));
            try (var permit = actor.permit("agent", rules("v1"), scope())) {
                Thread.sleep(150);
                assertEquals(1, verifier.sessions.size());
                assertThrows(GovernanceException.class, permit::checkActive);
                assertTrue(verifier.sessions.isEmpty());
            }
        } finally {
            release.countDown();
        }
    }
}
