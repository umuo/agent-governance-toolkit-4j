package io.agt.delegation;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.delegation.*;
import io.agt.core.usage.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteUsageTest {
    @BeforeAll
    static void keys() throws Exception {
        DelegationTest.keyPair();
    }

    static UsageLimits limits(long tokens, String cost) {
        return new UsageLimits(
                tokens,
                new BigDecimal(cost),
                "USD",
                Map.of(
                        "fixture",
                        new UsageLimits.ModelRate(
                                100, 50, BigDecimal.ONE, new BigDecimal("2"), Set.of())));
    }

    static DelegationScope scope() {
        return new DelegationScope(
                "customer-summary", true, DelegationTest.scope().tools(), Map.of());
    }

    record Input(ModelUsageReservation usageReservation) implements MeteredModelInput {}

    static String model(HostSession session, AtomicInteger entered) {
        return session.operationPrepared(
                false,
                null,
                Map.of("model", "fixture"),
                input -> new Input(session.reserveModelUsage("fixture", 100, 50)),
                input -> {
                    entered.incrementAndGet();
                    input.usageReservation().settle("fixture", 40, 10, 50);
                    return "ok";
                },
                value -> value,
                (original, value) -> (String) value);
    }

    static final class Fixture implements AutoCloseable {
        final Clock clock;
        final InMemoryGrantRegistry registry;
        final AgentControl sender = DelegationTest.control();
        final AgentControl receiverA = DelegationTest.control(),
                receiverB = DelegationTest.control();
        final HostSession source;
        final DelegationAuthority authority;
        final DelegationAuthority.Root root;
        final DelegationVerifier first, second;
        final DelegationVerifier.Permit permitA, permitB;
        final HostSession a, b;
        final TestGrantEndpoint endpoint;

        Fixture(UsageLimits limits, boolean http, boolean previous) throws Exception {
            clock = Clock.systemUTC();
            registry = new InMemoryGrantRegistry(clock);
            source =
                    sender.openTaskSession(
                            DelegationTest.SOURCE,
                            new TaskLimits(DelegationTest.CALLS, 1000, 10, 3, null, limits),
                            null,
                            scope());
            if (previous) model(source, new AtomicInteger());
            authority = DelegationTest.authority(registry, clock);
            root = authority.register(source);
            endpoint = http ? new TestGrantEndpoint(registry) : null;
            GrantStatusClient client =
                    http
                            ? HttpGrantStatusClient.loopbackForTesting(
                                    endpoint.uri(), () -> endpoint.credential)
                            : registry;
            first = DelegationTest.verifier(client, clock);
            second = DelegationTest.verifier(client, clock);
            permitA =
                    first.accept(
                            authority.issue(
                                    root.lease(),
                                    "reader",
                                    DelegationTest.AUDIENCE,
                                    Set.of("read"),
                                    scope(),
                                    Duration.ofSeconds(30)),
                            new AuthenticatedAgent("reader"));
            permitB =
                    second.accept(
                            authority.issue(
                                    root.lease(),
                                    "reader",
                                    DelegationTest.AUDIENCE,
                                    Set.of("read"),
                                    scope(),
                                    Duration.ofSeconds(30)),
                            new AuthenticatedAgent("reader"));
            a = receiverA.openAuthorizedSession(permitA, DelegationTest.receiverLimits(), null);
            b = receiverB.openAuthorizedSession(permitB, DelegationTest.receiverLimits(), null);
        }

        UsageBudget.Snapshot snapshot() {
            return registry.usageSnapshot(root.lease(), source.sessionId());
        }

        public void close() {
            a.close();
            b.close();
            first.close();
            second.close();
            root.close();
            authority.close();
            source.close();
            sender.close();
            receiverA.close();
            receiverB.close();
            if (endpoint != null) endpoint.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void remoteReceiversShareTokenAndCostBudgets(boolean money) throws Exception {
        try (var f =
                new Fixture(limits(money ? 1000 : 200, money ? "0.00026" : "1"), true, false)) {
            var effects = new AtomicInteger();
            assertEquals("ok", model(f.a, effects));
            assertEquals("ok", model(f.b, effects));
            assertEquals(
                    money ? "task_cost_budget_exceeded" : "task_token_budget_exceeded",
                    assertThrows(GovernanceException.class, () -> model(f.a, effects)).code());
            assertEquals(2, effects.get());
            assertEquals(100, f.snapshot().settledTokens());
            assertEquals(0, f.snapshot().heldTokens());
            assertEquals(0, new BigDecimal("0.00012").compareTo(f.snapshot().settledCost()));
            assertEquals(f.snapshot(), f.b.usageSnapshot());
        }
    }

    @Test
    void priorLocalConsumptionAndSignedPriceConfigurationSurviveTransfer() throws Exception {
        try (var f = new Fixture(limits(250, "1"), true, true)) {
            assertEquals(50, f.snapshot().settledTokens());
            assertEquals(f.source.usageLimits(), f.a.usageLimits());
            var effects = new AtomicInteger();
            model(f.a, effects);
            model(f.b, effects);
            assertThrows(GovernanceException.class, () -> model(f.a, effects));
            assertEquals(150, f.snapshot().settledTokens());
            assertEquals(2, effects.get());
        }
    }

    @Test
    void parallelRemoteReservationsCannotMultiplyTheBalance() throws Exception {
        try (var f = new Fixture(limits(450, "1"), false, false)) {
            var pool = Executors.newFixedThreadPool(20);
            var gate = new CountDownLatch(1);
            var holds = new CopyOnWriteArrayList<ModelUsageReservation>();
            try {
                var futures = new ArrayList<Future<?>>();
                for (int i = 0; i < 20; i++) {
                    var session = i % 2 == 0 ? f.a : f.b;
                    futures.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            gate.await();
                                            holds.add(
                                                    session.reserveModelUsage("fixture", 100, 50));
                                        } catch (GovernanceException denied) {
                                            assertEquals(
                                                    "task_token_budget_exceeded", denied.code());
                                        } catch (InterruptedException stopped) {
                                            throw new RuntimeException(stopped);
                                        }
                                    }));
                }
                gate.countDown();
                for (var future : futures) future.get(5, TimeUnit.SECONDS);
                assertEquals(3, holds.size());
                assertEquals(450, f.snapshot().heldTokens());
                holds.forEach(ModelUsageReservation::close);
                assertEquals(0, f.snapshot().heldTokens());
                assertNull(f.snapshot().fault());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void dispatchedUnknownUsageFreezesAllReceiversAndKeepsTheHold() throws Exception {
        try (var f = new Fixture(limits(1000, "1"), true, false)) {
            var held = f.a.reserveModelUsage("fixture", 100, 50);
            held.dispatch(f.a.sessionId(), f.permitA.usageLedger());
            held.close();
            assertEquals("model_usage_unconfirmed", f.snapshot().fault());
            assertEquals(150, f.snapshot().heldTokens());
            assertEquals(1, f.snapshot().uncertainCalls());
            assertThrows(GovernanceException.class, f.b::checkActive);
        }
    }

    @Test
    void wrongModelOrBoundBreachCannotBeAcceptedAsCheapUsage() throws Exception {
        for (boolean mismatch : List.of(false, true)) {
            try (var f = new Fixture(limits(1000, "1"), true, false)) {
                var held = f.a.reserveModelUsage("fixture", 100, 50);
                held.dispatch(f.a.sessionId(), f.permitA.usageLedger());
                String code =
                        mismatch ? "model_usage_identity_mismatch" : "model_usage_bound_exceeded";
                assertEquals(
                        code,
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                held.settle(
                                                        mismatch ? "unpriced" : "fixture",
                                                        101,
                                                        50,
                                                        151))
                                .code());
                assertEquals(code, f.snapshot().fault());
                assertEquals(mismatch ? 150 : 0, f.snapshot().heldTokens());
                assertEquals(mismatch ? 0 : 151, f.snapshot().settledTokens());
                assertThrows(GovernanceException.class, f.b::checkActive);
            }
        }
    }

    @Test
    void preparedCancellationRefundsAndReplayedSettlementDoesNotDoubleCharge() throws Exception {
        try (var f = new Fixture(limits(1000, "1"), true, false)) {
            var prepared = f.a.reserveModelUsage("fixture", 100, 50);
            assertThrows(
                    GovernanceException.class,
                    () -> prepared.dispatch(f.b.sessionId(), f.permitB.usageLedger()));
            prepared.close();
            prepared.close();
            assertEquals(0, f.snapshot().heldTokens());
            var client =
                    HttpGrantStatusClient.loopbackForTesting(
                            f.endpoint.uri(), () -> f.endpoint.credential);
            client.usageReserve(f.permitA.lease(), f.a.sessionId(), "single", "fixture", 100, 50);
            client.usageReserve(f.permitA.lease(), f.a.sessionId(), "single", "fixture", 100, 50);
            client.usageDispatch(f.permitA.lease(), f.a.sessionId(), "single");
            client.usageDispatch(f.permitA.lease(), f.a.sessionId(), "single");
            client.usageSettle(f.permitA.lease(), f.a.sessionId(), "single", "fixture", 40, 10, 50);
            client.usageSettle(f.permitA.lease(), f.a.sessionId(), "single", "fixture", 40, 10, 50);
            assertEquals(50, f.snapshot().settledTokens());
            assertEquals(0, f.snapshot().heldTokens());
            assertThrows(
                    GovernanceException.class,
                    () -> client.usageDispatch(f.permitB.lease(), f.b.sessionId(), "single"));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            client.usageSettle(
                                    f.permitA.lease(),
                                    f.a.sessionId(),
                                    "single",
                                    "fixture",
                                    41,
                                    10,
                                    51));
            assertEquals(50, f.snapshot().settledTokens());
        }
    }

    @Test
    void exportWaitsForLocalInFlightUsageWithoutDestroyingTheLocalTask() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = DelegationTest.control();
                var source =
                        sender.openTaskSession(
                                DelegationTest.SOURCE,
                                new TaskLimits(
                                        DelegationTest.CALLS, 1000, 10, 3, null, limits(1000, "1")),
                                null,
                                scope());
                var authority = DelegationTest.authority(registry, clock)) {
            var pending = source.reserveModelUsage("fixture", 100, 50);
            assertEquals(
                    "model_usage_inflight_export_denied",
                    assertThrows(GovernanceException.class, () -> authority.register(source))
                            .code());
            assertEquals("ok", DelegationTest.lookup(source, "customer-a", new AtomicInteger()));
            pending.close();
            try (var root = authority.register(source)) {
                assertEquals(
                        root.usageSnapshot(),
                        registry.usageSnapshot(root.lease(), source.sessionId()));
                assertEquals(root.usageSnapshot(), source.usageSnapshot());
                assertEquals(
                        0, registry.usageSnapshot(root.lease(), source.sessionId()).heldTokens());
                assertEquals(
                        "delegation_budget_exported_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> source.reserveModelUsage("fixture", 1, 1))
                                .code());
            }
        }
    }

    @Test
    void receiverCannotReplaceTheAuthorityWithAnIndependentLocalUsageBudget() throws Exception {
        try (var f = new Fixture(limits(1000, "1"), false, false)) {
            var token =
                    f.authority.issue(
                            f.root.lease(),
                            "reader",
                            DelegationTest.AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            try (var permit = f.first.accept(token, new AuthenticatedAgent("reader"))) {
                var independent =
                        new TaskLimits(DelegationTest.CALLS, 1000, 10, 2, null, limits(1000, "1"));
                assertEquals(
                        "model_usage_local_override_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                f.receiverA.openAuthorizedSession(
                                                        permit, independent, null))
                                .code());
            }
            assertEquals(0, f.snapshot().heldTokens());
        }
    }

    @Test
    void genericModelInvocationCannotSkipTheInheritedMeteringContract() throws Exception {
        try (var f = new Fixture(limits(1000, "1"), true, false)) {
            var effects = new AtomicInteger();
            assertEquals(
                    "model_usage_reservation_required",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.a.operation(
                                                    false,
                                                    null,
                                                    Map.of(),
                                                    input -> {
                                                        effects.incrementAndGet();
                                                        return "unmetered";
                                                    },
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
            assertEquals(0, effects.get());
            assertEquals(0, f.snapshot().settledTokens());
            assertEquals(0, f.snapshot().heldTokens());
        }
    }
}
