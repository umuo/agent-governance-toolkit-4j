package io.agt.core;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.flow.*;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.core.usage.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelUsageSessionTest {
    private static final TrustedContext IDENTITY =
            new TrustedContext("agent", "tenant", "user", Set.of());

    private record Input(ModelUsageReservation usageReservation) implements MeteredModelInput {}

    private AgentControl runtime() {
        return AgentControl.builder(StructuredPolicy.builder("usage-test").build())
                .audit(record -> {})
                .build();
    }

    private TaskLimits limits(long tokens) {
        return new TaskLimits(
                new BudgetLimits(20, 20, 60000),
                20,
                10,
                3,
                null,
                new UsageLimits(
                        tokens,
                        BigDecimal.ONE,
                        "USD",
                        Map.of(
                                "model",
                                new UsageLimits.ModelRate(
                                        100, 100, BigDecimal.ONE, BigDecimal.ONE, Set.of()))));
    }

    private String model(HostSession session, AtomicInteger effects) {
        return session.operationPrepared(
                false,
                null,
                "request",
                input -> new Input(session.reserveModelUsage("model", 100, 100)),
                prepared -> {
                    effects.incrementAndGet();
                    prepared.usageReservation().settle("model", 50, 20, 70);
                    return "ok";
                },
                value -> value,
                (original, effective) -> (String) effective);
    }

    @Test
    void concurrentDataFlowDenialRefundsUnsentMeterReservation() throws Exception {
        var flow =
                new DataFlowPolicy(
                        Map.of(),
                        Map.of("fetch", DataLabel.CONFIDENTIAL),
                        Map.of(),
                        Set.of(),
                        DataLabel.PUBLIC,
                        DataLabel.PUBLIC,
                        DataLabel.SECRET);
        var policy =
                StructuredPolicy.builder("combined-usage-flow")
                        .tool(
                                "fetch",
                                new ToolRule(Set.of(), ParameterRule.object(Map.of()), false))
                        .build();
        var base = limits(1000);
        try (var control = AgentControl.builder(policy).audit(record -> {}).build();
                var root =
                        control.openTaskSession(
                                IDENTITY,
                                new TaskLimits(base.calls(), 20, 10, 3, flow, base.usage()));
                var reader = control.openDelegatedSession(root, IDENTITY);
                var model = control.openDelegatedSession(root, IDENTITY)) {
            var ready = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var effects = new AtomicInteger();
            var call =
                    model.operationAsyncPrepared(
                            false,
                            null,
                            "request",
                            input -> {
                                var lease = model.reserveModelUsage("model", 100, 100);
                                ready.countDown();
                                try {
                                    assertTrue(resume.await(3, TimeUnit.SECONDS));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException(interrupted);
                                }
                                return new Input(lease);
                            },
                            input -> {
                                effects.incrementAndGet();
                                return CompletableFuture.completedFuture("bad");
                            },
                            value -> value,
                            (original, value) -> (String) value);
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            try {
                reader.operation(
                        true,
                        "fetch",
                        Map.of(),
                        input -> "secret",
                        value -> value,
                        (original, value) -> (String) value);
            } finally {
                resume.countDown();
            }
            var error = assertThrows(ExecutionException.class, () -> call.get(3, TimeUnit.SECONDS));
            assertEquals("data_flow_model_denied", ((GovernanceException) error.getCause()).code());
            assertEquals(0, effects.get());
            assertEquals(0, root.usageSnapshot().heldTokens());
            assertEquals(0, root.usageSnapshot().uncertainCalls());
            assertNull(root.usageSnapshot().fault());
            root.checkActive();
        }
    }

    @Test
    void childSessionsShareSettledTokensAndCannotResetThemByClosing() {
        try (var control = runtime();
                var root = control.openTaskSession(IDENTITY, limits(300));
                var first = control.openDelegatedSession(root, IDENTITY);
                var second = control.openDelegatedSession(root, IDENTITY);
                var third = control.openDelegatedSession(root, IDENTITY)) {
            var effects = new AtomicInteger();
            assertEquals("ok", model(first, effects));
            first.close();
            assertEquals("ok", model(second, effects));
            second.close();
            assertEquals(
                    "task_token_budget_exceeded",
                    assertThrows(GovernanceException.class, () -> model(third, effects)).code());
            assertEquals(2, effects.get());
            assertEquals(140, root.usageSnapshot().settledTokens());
            assertEquals(0, root.usageSnapshot().heldTokens());
            assertEquals(2, root.taskSnapshot().modelCalls());
        }
    }

    @Test
    void unmeteredGenericModelOperationCannotBypassOptInBudget() {
        try (var control = runtime();
                var root = control.openTaskSession(IDENTITY, limits(1000))) {
            var effects = new AtomicInteger();
            assertEquals(
                    "model_usage_reservation_required",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            root.operation(
                                                    false,
                                                    null,
                                                    "request",
                                                    input -> {
                                                        effects.incrementAndGet();
                                                        return "bad";
                                                    },
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
            assertEquals(0, effects.get());
            assertEquals(0, root.taskSnapshot().modelCalls());
        }
    }

    @Test
    void cancellationAfterDispatchRetainsUnknownChargeAndStopsSiblingDelivery() throws Exception {
        try (var control = runtime();
                var root = control.openTaskSession(IDENTITY, limits(1000));
                var child = control.openDelegatedSession(root, IDENTITY);
                var sibling = control.openDelegatedSession(root, IDENTITY)) {
            var stop = sibling.watchTermination(new CompletableFuture<String>());
            var entered = new CountDownLatch(1);
            var source = new CompletableFuture<String>();
            var result =
                    child.operationAsyncPrepared(
                            false,
                            null,
                            "request",
                            input -> new Input(child.reserveModelUsage("model", 100, 100)),
                            prepared -> {
                                entered.countDown();
                                return source;
                            },
                            value -> value,
                            (original, value) -> (String) value);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            result.cancel(true);
            assertThrows(ExecutionException.class, () -> stop.get(3, TimeUnit.SECONDS));
            assertEquals(200, root.usageSnapshot().heldTokens());
            assertEquals(1, root.usageSnapshot().uncertainCalls());
            assertEquals("model_usage_unconfirmed", root.usageSnapshot().fault());
            assertThrows(GovernanceException.class, sibling::checkActive);
        }
    }

    @Test
    void cancellationWhilePreparingRefundsUnsentReservationWithoutQuarantiningRoot()
            throws Exception {
        try (var control = runtime();
                var root = control.openTaskSession(IDENTITY, limits(1000));
                var child = control.openDelegatedSession(root, IDENTITY)) {
            var reserved = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var effects = new AtomicInteger();
            var result =
                    child.operationAsyncPrepared(
                            false,
                            null,
                            "request",
                            input -> {
                                var lease = child.reserveModelUsage("model", 100, 100);
                                reserved.countDown();
                                try {
                                    resume.await(3, TimeUnit.SECONDS);
                                } catch (InterruptedException ignored) {
                                    Thread.currentThread().interrupt();
                                }
                                return new Input(lease);
                            },
                            prepared -> {
                                effects.incrementAndGet();
                                return CompletableFuture.completedFuture("bad");
                            },
                            value -> value,
                            (original, value) -> (String) value);
            assertTrue(reserved.await(3, TimeUnit.SECONDS));
            result.cancel(true);
            resume.countDown();
            assertEquals(0, root.usageSnapshot().heldTokens());
            assertNull(root.usageSnapshot().fault());
            assertEquals(0, effects.get());
            root.checkActive();
        }
    }
}
