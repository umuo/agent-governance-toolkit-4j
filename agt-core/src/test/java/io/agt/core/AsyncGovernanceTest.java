package io.agt.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agt.core.approval.ApprovalRequest;
import io.agt.core.approval.ApprovalResponse;
import io.agt.core.audit.AuditRecord;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 异步治理特性的单元测试。
 *
 * <p>验证异步调用的上下文传递、取消传播、超时到期、并发限制与资源安全释放。
 */
class AsyncGovernanceTest {
    static final TrustedContext CTX = new TrustedContext("agent", "tenant", "user", Set.of());

    static AgentControl runtime() {
        return AgentControl.builder(StructuredPolicy.builder("v2").build()).audit(r -> {}).build();
    }

    static String code(CompletableFuture<?> f) {
        Throwable e = assertThrows(Exception.class, () -> f.get(3, TimeUnit.SECONDS));
        while (e.getCause() != null
                && (e instanceof ExecutionException || e instanceof CompletionException)) {
            e = e.getCause();
        }
        return e instanceof GovernanceException g ? g.code() : e.getClass().getSimpleName();
    }

    @Test
    void callbacksRestoreSessionAndAvoidProviderThread() throws Exception {
        try (var c = runtime();
                var s = c.openSession(CTX)) {
            var source = new CompletableFuture<String>();
            var started = new CountDownLatch(1);
            var result =
                    s.runAsync(
                            "in",
                            v -> {
                                assertSame(s, c.currentSession());
                                started.countDown();
                                return source;
                            },
                            v -> v,
                            (v, e) -> (String) e);
            var callback =
                    result.thenApply(
                            v -> {
                                assertSame(s, c.currentSession());
                                assertEquals("agt-worker", Thread.currentThread().getName());
                                return v;
                            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var provider = new Thread(() -> source.complete("ok"), "provider-io");
            provider.start();
            provider.join();
            assertEquals("ok", callback.get(3, TimeUnit.SECONDS));
            assertFalse(s.isAsyncRun());
            assertTrue(
                    c.workers
                            .submit(
                                    () -> {
                                        try {
                                            c.currentSession();
                                            return false;
                                        } catch (GovernanceException e) {
                                            return e.code().equals("missing_session");
                                        }
                                    })
                            .get());
        }
    }

    @Test
    void cancellationPropagatesAndStopsSession() throws Exception {
        try (var c = runtime();
                var s = c.openSession(CTX)) {
            var source = new CompletableFuture<String>();
            var ready = new CountDownLatch(1);
            var result =
                    c.runAsync(
                            "in",
                            v -> {
                                ready.countDown();
                                return source;
                            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            assertTrue(result.cancel(true));
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
            assertTrue(result.isCancelled());
            assertEquals("operation_cancelled", s.failureCode());
            assertThrows(GovernanceException.class, s::checkActive);
        }
    }

    @Test
    void closeCancelsPendingResultAndSource() throws Exception {
        try (var c = runtime()) {
            var s = c.openSession(CTX);
            var source = new CompletableFuture<String>();
            var ready = new CountDownLatch(1);
            var result =
                    c.runAsync(
                            "in",
                            v -> {
                                ready.countDown();
                                return source;
                            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            s.close();
            assertEquals("session_closed", code(result));
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void expiryEndsNeverCompletingSource() throws Exception {
        try (var c =
                        AgentControl.builder(StructuredPolicy.builder("v2").build())
                                .budget(new BudgetLimits(2, 2, 500))
                                .audit(r -> {})
                                .build();
                var s = c.openSession(CTX)) {
            var source = new CompletableFuture<String>();
            var started = new CountDownLatch(1);
            var result =
                    c.runAsync(
                            "in",
                            v -> {
                                started.countDown();
                                return source;
                            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals("session_expired", code(result));
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void runtimeCloseAndRevokeResolvePendingFutures() throws Exception {
        for (boolean close : List.of(true, false)) {
            var c = runtime();
            var s = c.openSession(CTX);
            var source = new CompletableFuture<String>();
            var ready = new CountDownLatch(1);
            var result =
                    c.runAsync(
                            "x",
                            v -> {
                                ready.countDown();
                                return source;
                            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            if (close) {
                c.close();
            } else {
                s.revoke();
            }
            assertEquals(close ? "runtime_closed" : "session_revoked", code(result));
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
            s.close();
            c.close();
        }
    }

    @Test
    void overlappingTopLevelCallsRefused() throws Exception {
        try (var c = runtime();
                var s = c.openSession(CTX)) {
            var source = new CompletableFuture<String>();
            var ready = new CountDownLatch(1);
            var first =
                    c.runAsync(
                            "x",
                            v -> {
                                ready.countDown();
                                return source;
                            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            assertEquals(
                    "concurrent_run_unsupported",
                    code(c.runAsync("y", v -> CompletableFuture.completedFuture("bad"))));
            assertTrue(first.isCompletedExceptionally());
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void contextualExecutorCleansReusedThreads() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try (var c = runtime();
                var s = c.openSession(CTX)) {
            var done = new CompletableFuture<String>();
            c.contextualExecutor(pool)
                    .execute(() -> done.complete(c.currentSession().context().tenantId()));
            assertEquals("tenant", done.get(2, TimeUnit.SECONDS));
            assertEquals(
                    "missing_session",
                    pool.submit(
                                    () -> {
                                        try {
                                            c.currentSession();
                                            return "leaked";
                                        } catch (GovernanceException e) {
                                            return e.code();
                                        }
                                    })
                            .get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void capacityRejectedAndCanceledQueueSlotReclaimed() throws Exception {
        try (var c =
                AgentControl.builder(StructuredPolicy.builder("v2").build())
                        .asyncLimits(new AsyncLimits(1, 1))
                        .audit(r -> {})
                        .build()) {
            var s1 = c.openSession(CTX);
            var gate = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var f1 =
                    s1.runAsync(
                            "x",
                            v -> {
                                entered.countDown();
                                try {
                                    gate.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return CompletableFuture.completedFuture("a");
                            },
                            v -> v,
                            (v, e) -> (String) e);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var s2 = c.openSession(CTX);
            var f2 = c.runAsync("y", v -> CompletableFuture.completedFuture("b"));
            var s3 = c.openSession(CTX);
            assertEquals(
                    "async_capacity_exceeded",
                    code(c.runAsync("z", v -> CompletableFuture.completedFuture("c"))));
            assertTrue(f2.cancel(true));
            assertEquals(0, c.workers.getQueue().size());
            gate.countDown();
            assertEquals("a", f1.get(3, TimeUnit.SECONDS));
            s3.close();
            s2.close();
            s1.close();
        }
    }

    @Test
    void asyncApprovalRemainsBoundToEffectiveAction() throws Exception {
        var params = ParameterRule.object(Map.of("query", ParameterRule.string(null)), "query");
        var p =
                StructuredPolicy.builder("v2")
                        .tool("lookup", new ToolRule(Set.of(), params, true))
                        .content(
                                new ContentRule(
                                        "rewrite",
                                        Set.of(InterventionPoint.PRE_TOOL_CALL),
                                        List.of(),
                                        Map.of("old", "new")))
                        .build();
        var requested = new CompletableFuture<ApprovalRequest>();
        var approved = new CompletableFuture<ApprovalResponse>();
        var effects = new AtomicInteger();
        try (var c =
                        AgentControl.builder(p)
                                .audit(r -> {})
                                .approvalTimeout(Duration.ofSeconds(2))
                                .approvalResolver(
                                        r -> {
                                            requested.complete(r);
                                            return approved;
                                        })
                                .build();
                var s = c.openSession(CTX)) {
            var result =
                    s.operationAsyncPrepared(
                            true,
                            "lookup",
                            Map.of("query", "old"),
                            v -> v,
                            v -> {
                                effects.incrementAndGet();
                                assertEquals(Map.of("query", "new"), v);
                                return CompletableFuture.completedFuture("ok");
                            },
                            v -> v,
                            (v, e) -> (String) e);
            var request = requested.get(2, TimeUnit.SECONDS);
            assertEquals(Map.of("query", "new"), request.action().target());
            assertEquals(0, effects.get());
            approved.complete(ApprovalResponse.allow(request));
            assertEquals("ok", result.get(3, TimeUnit.SECONDS));
            assertEquals(1, effects.get());
            assertEquals(1, s.budgetSnapshot().toolCalls());
        }
    }

    @Test
    void deadlineCanFinishWhileSuccessAuditIsBlocked() throws Exception {
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var c =
                        AgentControl.builder(StructuredPolicy.builder("v2").build())
                                .budget(new BudgetLimits(2, 2, 500))
                                .audit(
                                        r -> {
                                            if (r.verdict().equals("EXECUTION")
                                                    && r.executionOutcome().equals("succeeded")) {
                                                blocked.countDown();
                                                release.await(3, TimeUnit.SECONDS);
                                            }
                                        })
                                .build();
                var session = c.openSession(CTX)) {
            try {
                var result = c.runAsync("x", v -> CompletableFuture.completedFuture("y"));
                assertTrue(blocked.await(2, TimeUnit.SECONDS));
                assertEquals("session_expired", code(result));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void queuedContextFutureFailsAfterCloseInsteadOfHanging() throws Exception {
        var host = Executors.newSingleThreadExecutor();
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var c = runtime()) {
            host.submit(
                    () -> {
                        occupied.countDown();
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            var session = c.openSession(CTX);
            var result =
                    CompletableFuture.supplyAsync(
                            () -> c.currentSession().sessionId(), c.contextualExecutor(host));
            session.close();
            release.countDown();
            assertEquals("session_closed", code(result));
        } finally {
            release.countDown();
            host.shutdownNow();
        }
    }

    @Test
    void ownedAsyncSessionClosesWithoutBlockingCallerOrLeakingBinding() throws Exception {
        var events = Collections.synchronizedList(new ArrayList<AuditRecord>());
        try (var c =
                AgentControl.builder(StructuredPolicy.builder("v2").build())
                        .audit(events::add)
                        .build()) {
            var ready = new CountDownLatch(1);
            var source = new CompletableFuture<String>();
            var captured = new AtomicReference<HostSession>();
            var result =
                    c.withSessionAsync(
                            CTX,
                            () -> {
                                captured.set(c.currentSession());
                                return c.runAsync(
                                        "x",
                                        v -> {
                                            ready.countDown();
                                            return source;
                                        });
                            });
            assertEquals(
                    "missing_session",
                    assertThrows(GovernanceException.class, c::currentSession).code());
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            source.complete("ok");
            assertEquals("ok", result.get(3, TimeUnit.SECONDS));
            assertTrue(captured.get().budgetSnapshot().closed());
            assertEquals(
                    1,
                    events.stream()
                            .filter(r -> r.point() == InterventionPoint.AGENT_SHUTDOWN)
                            .count());
        }
    }

    @Test
    void ownedAsyncCancellationClosesSession() throws Exception {
        try (var c = runtime()) {
            var ready = new CountDownLatch(1);
            var source = new CompletableFuture<String>();
            var captured = new AtomicReference<HostSession>();
            var result =
                    c.withSessionAsync(
                            CTX,
                            () -> {
                                captured.set(c.currentSession());
                                return c.runAsync(
                                        "x",
                                        v -> {
                                            ready.countDown();
                                            return source;
                                        });
                            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            assertTrue(result.cancel(true));
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
            assertTrue(captured.get().budgetSnapshot().closed());
        }
    }

    @Test
    void terminationReleasesReservationsBeforeNotifyingButKeepsStartedCounts() {
        try (var control = runtime();
                var session = control.openSession(CTX)) {
            session.reserve(false);
            session.commit(false);
            session.reserve(false);
            session.reserve(true);
            var result = session.watchTermination(new CompletableFuture<String>());
            var observed = new AtomicReference<HostSession.BudgetSnapshot>();
            result.whenComplete((value, error) -> observed.set(session.budgetSnapshot()));
            session.refuse("test_terminated");
            assertEquals(1, observed.get().modelCalls());
            assertEquals(0, observed.get().toolCalls());
            session.release(false);
            session.release(true);
            assertEquals(1, session.budgetSnapshot().modelCalls());
            assertEquals(0, session.budgetSnapshot().toolCalls());
        }
    }
}
