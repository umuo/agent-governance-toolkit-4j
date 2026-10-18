package io.agt.core.usage;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.GovernanceException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UsageBudgetTest {
    private UsageBudget budget(long tokens, String cost, List<String> faults) {
        return new UsageBudget(
                new UsageLimits(
                        tokens,
                        new BigDecimal(cost),
                        "USD",
                        Map.of(
                                "model",
                                new UsageLimits.ModelRate(
                                        100,
                                        100,
                                        BigDecimal.ONE,
                                        new BigDecimal("2"),
                                        Set.of("served-model")))),
                faults::add);
    }

    @Test
    void zeroAllowanceDeniesPaidOrTokenConsumingRequestsInsteadOfDisablingBudget() {
        var noCost = budget(1000, "0", new ArrayList<>());
        assertEquals(
                "task_cost_budget_exceeded",
                assertThrows(
                                GovernanceException.class,
                                () -> noCost.reserve("owner", "model", 1, 1, done -> {}))
                        .code());
        var noTokens = budget(0, "1", new ArrayList<>());
        assertEquals(
                "task_token_budget_exceeded",
                assertThrows(
                                GovernanceException.class,
                                () -> noTokens.reserve("owner", "model", 1, 1, done -> {}))
                        .code());
        assertEquals(0, noCost.snapshot().heldTokens());
        assertEquals(0, noTokens.snapshot().heldTokens());
    }

    @Test
    void parallelReservationsCannotSpendTheSameRemainingTokens() throws Exception {
        var budget = budget(600, "1", new CopyOnWriteArrayList<>());
        var pool = Executors.newFixedThreadPool(20);
        var gate = new CountDownLatch(1);
        var leases = new ArrayList<ModelUsageReservation>();
        try {
            var futures = new ArrayList<Future<ModelUsageReservation>>();
            for (int i = 0; i < 20; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        return budget.reserve(
                                                "owner", "model", 100, 100, done -> {});
                                    } catch (GovernanceException denied) {
                                        assertEquals("task_token_budget_exceeded", denied.code());
                                        return null;
                                    }
                                }));
            gate.countDown();
            for (var future : futures) {
                var lease = future.get(5, TimeUnit.SECONDS);
                if (lease != null) leases.add(lease);
            }
            assertEquals(3, leases.size());
            assertEquals(600, budget.snapshot().heldTokens());
            assertEquals(0, budget.snapshot().settledTokens());
            assertEquals(0, budget.snapshot().heldCost().compareTo(new BigDecimal("0.0009")));
        } finally {
            pool.shutdownNow();
            leases.forEach(ModelUsageReservation::close);
        }
        assertEquals(0, budget.snapshot().heldTokens());
        assertEquals(0, budget.snapshot().inFlightCalls());
        assertNull(budget.snapshot().fault());
    }

    @Test
    void monetaryLimitAndSettlementUseExactDecimalsAndRefundOnlyTheDifference() {
        var budget = budget(10000, "0.0003", new ArrayList<>());
        var first = budget.reserve("owner", "model", 100, 100, done -> {});
        assertEquals(
                "task_cost_budget_exceeded",
                assertThrows(
                                GovernanceException.class,
                                () -> budget.reserve("owner", "model", 1, 1, done -> {}))
                        .code());
        first.dispatch("owner", budget);
        first.settle("served-model", 50, 20, 70);
        first.close();
        first.close();
        assertEquals(70, budget.snapshot().settledTokens());
        assertEquals(0, budget.snapshot().settledCost().compareTo(new BigDecimal("0.00009")));
        assertEquals(0, budget.snapshot().heldTokens());
        try (var second = budget.reserve("owner", "model", 100, 50, done -> {})) {
            assertEquals(150, budget.snapshot().heldTokens());
        }
        assertEquals(70, budget.snapshot().settledTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "identity", "bounds", "cancel"})
    void unknownInvalidOrExceededUsageCannotRestoreSpendAuthority(String mode) {
        var faults = new ArrayList<String>();
        var budget = budget(10000, "1", faults);
        var lease = budget.reserve("owner", "model", 100, 100, done -> {});
        lease.dispatch("owner", budget);
        String code =
                switch (mode) {
                    case "missing" ->
                            assertThrows(
                                            GovernanceException.class,
                                            () -> lease.settle("model", null, null, null))
                                    .code();
                    case "invalid" ->
                            assertThrows(
                                            GovernanceException.class,
                                            () -> lease.settle("model", 10, 10, 9))
                                    .code();
                    case "identity" ->
                            assertThrows(
                                            GovernanceException.class,
                                            () -> lease.settle("unapproved-model", 10, 10, 20))
                                    .code();
                    case "bounds" ->
                            assertThrows(
                                            GovernanceException.class,
                                            () -> lease.settle("model", 101, 10, 111))
                                    .code();
                    default -> {
                        lease.close();
                        yield "model_usage_unconfirmed";
                    }
                };
        lease.close();
        assertEquals(code, budget.snapshot().fault());
        assertEquals(List.of(code), faults);
        assertThrows(
                GovernanceException.class,
                () -> budget.reserve("owner", "model", 1, 1, done -> {}));
        if (mode.equals("bounds")) {
            assertEquals(111, budget.snapshot().settledTokens());
            assertEquals(0, budget.snapshot().heldTokens());
        } else {
            assertEquals(200, budget.snapshot().heldTokens());
            assertEquals(1, budget.snapshot().uncertainCalls());
        }
    }

    @Test
    void reservationIsSingleUseAndBoundToOwnerAndBudget() {
        var budget = budget(1000, "1", new ArrayList<>());
        var other = budget(1000, "1", new ArrayList<>());
        try (var lease = budget.reserve("owner", "model", 100, 100, done -> {})) {
            assertThrows(GovernanceException.class, () -> lease.dispatch("other-owner", budget));
            assertThrows(GovernanceException.class, () -> lease.dispatch("owner", other));
            lease.dispatch("owner", budget);
            assertThrows(GovernanceException.class, () -> lease.dispatch("owner", budget));
            lease.settle("model", 0, 0, 0);
            assertThrows(GovernanceException.class, () -> lease.settle("model", 0, 0, 0));
        }
        assertEquals(0, budget.snapshot().settledTokens());
        assertNull(budget.snapshot().fault());
    }

    @Test
    void rejectsUnknownModelAndPerCallLimitBeforeHoldingFunds() {
        var budget = budget(1000, "1", new ArrayList<>());
        assertEquals(
                "model_usage_model_denied",
                assertThrows(
                                GovernanceException.class,
                                () -> budget.reserve("owner", "unknown", 1, 1, done -> {}))
                        .code());
        assertEquals(
                "model_usage_request_bound_denied",
                assertThrows(
                                GovernanceException.class,
                                () -> budget.reserve("owner", "model", 101, 1, done -> {}))
                        .code());
        assertEquals(0, budget.snapshot().heldTokens());
        assertEquals(0, budget.snapshot().inFlightCalls());
    }
}
