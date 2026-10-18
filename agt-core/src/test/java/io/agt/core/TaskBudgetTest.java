package io.agt.core;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.policy.StructuredPolicy;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskBudgetTest {
    private static final TrustedContext IDENTITY =
            new TrustedContext("agent", "tenant", "user", Set.of("read"));

    private AgentControl runtime() {
        return AgentControl.builder(StructuredPolicy.builder("v1").build()).audit(r -> {}).build();
    }

    private TaskLimits limits(int models, int sessions, int depth) {
        return new TaskLimits(new BudgetLimits(models, 2, 60000), 2, sessions, depth);
    }

    private String model(HostSession session, AtomicInteger effects) {
        return session.operation(
                false,
                null,
                "input",
                input -> {
                    effects.incrementAndGet();
                    return "ok";
                },
                value -> value,
                (original, value) -> (String) value);
    }

    @Test
    void parallelDescendantsCannotMultiplyRootModelBudget() throws Exception {
        try (var control = runtime();
                var root = control.openTaskSession(IDENTITY, limits(3, 30, 3))) {
            var children = new ArrayList<HostSession>();
            var workers = Executors.newFixedThreadPool(20);
            var gate = new CountDownLatch(1);
            var effects = new AtomicInteger();
            try {
                var futures = new ArrayList<java.util.concurrent.Future<String>>();
                for (int i = 0; i < 20; i++) {
                    var child = control.openDelegatedSession(root, IDENTITY);
                    children.add(child);
                    futures.add(
                            workers.submit(
                                    () -> {
                                        gate.await();
                                        try {
                                            return model(child, effects);
                                        } catch (GovernanceException error) {
                                            return error.code();
                                        }
                                    }));
                }
                gate.countDown();
                int successes = 0;
                for (var future : futures) {
                    String outcome = future.get(5, TimeUnit.SECONDS);
                    if (outcome.equals("ok")) successes++;
                    else assertEquals("task_model_budget_exceeded", outcome);
                }
                assertEquals(3, successes);
                assertEquals(3, effects.get());
                assertEquals(3, root.taskSnapshot().modelCalls());
                assertTrue(
                        children.stream()
                                .allMatch(
                                        c ->
                                                c.taskSnapshot()
                                                        .taskId()
                                                        .equals(root.taskSnapshot().taskId())));
                root.checkActive();
            } finally {
                workers.shutdownNow();
                children.forEach(HostSession::close);
            }
            assertEquals(3, root.taskSnapshot().modelCalls());
            assertEquals(1, root.taskSnapshot().activeSessions());
        }
    }

    @Test
    void abandonedReservationsReturnSharedCapacityExactlyOnce() {
        try (var control = runtime();
                var root = control.openTaskSession(IDENTITY, limits(2, 4, 3));
                var first = control.openDelegatedSession(root, IDENTITY);
                var next = control.openDelegatedSession(root, IDENTITY)) {
            first.reserve(true);
            first.reserve(false);
            first.reserveData();
            assertEquals(1, root.taskSnapshot().modelCalls());
            assertEquals(1, root.taskSnapshot().toolCalls());
            assertEquals(1, root.taskSnapshot().dataCalls());
            first.revoke();
            first.revoke();
            first.release(true);
            first.release(false);
            first.releaseData();
            assertEquals(0, root.taskSnapshot().modelCalls());
            assertEquals(0, root.taskSnapshot().toolCalls());
            assertEquals(0, root.taskSnapshot().dataCalls());
            next.reserve(true);
            next.commit(true);
            next.reserveData();
            next.commitData();
            next.close();
            assertEquals(1, root.taskSnapshot().toolCalls());
            assertEquals(1, root.taskSnapshot().dataCalls());
        }
    }

    @Test
    void failedPreparationReturnsRootBudgetWithoutCountingExecution() {
        try (var control = runtime();
                var root = control.openTaskSession(IDENTITY, limits(1, 3, 2));
                var first = control.openDelegatedSession(root, IDENTITY);
                var second = control.openDelegatedSession(root, IDENTITY)) {
            var effects = new AtomicInteger();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            first.operationPrepared(
                                    false,
                                    null,
                                    "in",
                                    input -> {
                                        throw new IllegalArgumentException();
                                    },
                                    prepared -> {
                                        effects.incrementAndGet();
                                        return "bad";
                                    },
                                    value -> value,
                                    (original, value) -> (String) value));
            assertEquals(0, root.taskSnapshot().modelCalls());
            assertEquals("ok", model(second, effects));
            assertEquals(1, effects.get());
        }
    }

    @Test
    void sessionAndDepthCapsRejectBeforeStartupAndDoNotLeakSlots() {
        try (var control = runtime();
                var root = control.openTaskSession(IDENTITY, limits(2, 2, 1));
                var child = control.openDelegatedSession(root, IDENTITY)) {
            assertEquals(
                    "task_session_limit",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> control.openDelegatedSession(root, IDENTITY))
                            .code());
            assertEquals(
                    "delegation_depth_exceeded",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> control.openDelegatedSession(child, IDENTITY))
                            .code());
            assertEquals(2, root.taskSnapshot().activeSessions());
            child.close();
            try (var replacement = control.openDelegatedSession(root, IDENTITY)) {
                assertEquals(2, root.taskSnapshot().activeSessions());
                replacement.checkActive();
            }
        }
    }

    @Test
    void sharedDeadlineStopsDescendantDeliveryAndFurtherExecution() throws Exception {
        try (var control = runtime();
                var root =
                        control.openTaskSession(
                                IDENTITY, new TaskLimits(new BudgetLimits(2, 2, 200), 2, 3, 2));
                var child = control.openDelegatedSession(root, IDENTITY)) {
            var pending =
                    child.watchTermination(new java.util.concurrent.CompletableFuture<String>());
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> pending.get(3, TimeUnit.SECONDS));
            var effects = new AtomicInteger();
            assertThrows(GovernanceException.class, () -> model(child, effects));
            assertEquals(0, effects.get());
        }
    }

    @Test
    void independentRootTasksDoNotShareQuota() {
        try (var control = runtime();
                var first = control.openTaskSession(IDENTITY, limits(1, 2, 1));
                var second = control.openTaskSession(IDENTITY, limits(1, 2, 1))) {
            assertNotEquals(first.taskSnapshot().taskId(), second.taskSnapshot().taskId());
            var effects = new AtomicInteger();
            model(first, effects);
            model(second, effects);
            assertEquals(2, effects.get());
        }
    }
}
