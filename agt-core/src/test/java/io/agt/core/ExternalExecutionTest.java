package io.agt.core;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.execution.*;
import io.agt.core.policy.StructuredPolicy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ExternalExecutionTest {
    @Test
    void earlyExitReceiptPublishesCancellationBeforeCompletion() {
        var queue = new ArrayDeque<Runnable>();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var authority = new ExternalExecutionState("task", java.time.Clock.systemUTC());
        ExternalExecutionLedger mirror =
                new ExternalExecutionLedger() {
                    public void register(String id, String owner, String kind) {
                        authority.register(id, owner, kind);
                    }

                    public void start(String id, String owner) {
                        authority.start(id, owner);
                    }

                    public void cancel(String id, String owner) {
                        authority.cancel(id, owner);
                    }

                    public void finish(String id, String owner, TerminationEvidence proof) {
                        authority.finish(id, owner, proof);
                    }

                    public ExternalExecutionRegistry.Snapshot snapshot() {
                        return authority.snapshot();
                    }
                };
        try {
            var registry =
                    new ExternalExecutionRegistry(
                            "task", queue::add, timer, code -> fail(code), new HashSet<>(), mirror);
            var work =
                    registry.register(
                            "owner",
                            "DIRECT_PROCESS",
                            () -> CompletableFuture.completedFuture(TerminationEvidence.CONFIRMED),
                            () -> {});
            work.started();
            work.requestCancellation();
            work.finished(TerminationEvidence.CONFIRMED);
            // 故意让进程退出的发布抢先，模拟取消线程尚未发出权威 RPC。
            queue.removeLast().run();
            assertEquals(
                    ExternalExecutionRegistry.State.CANCEL_CONFIRMED,
                    authority.snapshot().entries().get(0).state());
            queue.removeFirst().run();
            assertTrue(authority.snapshot().allTerminationConfirmed());
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void blockedRemoteSnapshotCannotBlockHeartbeatOrSessionTermination() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try (var control = control();
                var root = control.openTaskSession(ID, limits())) {
            root.exportDelegationState();
            root.bindExportedTaskView(() -> {}, null, null);
            root.bindExportedExternalView(
                    new ExternalExecutionLedger() {
                        public void register(String id, String owner, String kind) {
                            throw new AssertionError();
                        }

                        public void start(String id, String owner) {
                            throw new AssertionError();
                        }

                        public void cancel(String id, String owner) {
                            throw new AssertionError();
                        }

                        public void finish(String id, String owner, TerminationEvidence proof) {
                            throw new AssertionError();
                        }

                        public ExternalExecutionRegistry.Snapshot snapshot() {
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("Missing release");
                            } catch (InterruptedException interrupted) {
                                throw new IllegalStateException(interrupted);
                            }
                            return new ExternalExecutionRegistry.Snapshot(
                                    root.taskSnapshot().taskId(), List.of(), 0, 0, 0, true, null);
                        }
                    });
            var diagnostic = pool.submit(root::externalExecutionSnapshot);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            pool.submit(root::checkActive).get(1, TimeUnit.SECONDS);
            pool.submit(root::revoke).get(1, TimeUnit.SECONDS);
            release.countDown();
            diagnostic.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    static final TrustedContext ID = new TrustedContext("agent", "tenant", "user", Set.of());

    AgentControl control() {
        return AgentControl.builder(StructuredPolicy.builder("external").build())
                .audit(record -> {})
                .build();
    }

    TaskLimits limits() {
        return new TaskLimits(new BudgetLimits(10, 10, 60000), 10, 5, 3);
    }

    static void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(done.getAsBoolean(), "Expected external lifecycle transition");
    }

    @Test
    void cancellingPreparedWorkPreventsDispatchWithoutCallingBackend() {
        try (var control = control();
                var root = control.openTaskSession(ID, limits())) {
            var calls = new AtomicInteger();
            var work =
                    root.registerExternalExecution(
                            "REMOTE_JOB",
                            () -> {
                                calls.incrementAndGet();
                                return CompletableFuture.completedFuture(
                                        TerminationEvidence.CONFIRMED);
                            });
            work.requestCancellation();
            assertThrows(GovernanceException.class, work::started);
            assertEquals(0, calls.get());
            assertTrue(root.externalExecutionSnapshot().allTerminationConfirmed());
            assertEquals(
                    TerminationEvidence.NOT_STARTED,
                    root.externalExecutionSnapshot().entries().get(0).evidence());
        }
    }

    @Test
    void childRevocationRequestsStopAndSharedSnapshotWaitsForActualAcknowledgement()
            throws Exception {
        try (var control = control();
                var root = control.openTaskSession(ID, limits());
                var child = control.openDelegatedSession(root, ID)) {
            var acknowledgement = new CompletableFuture<TerminationEvidence>();
            var called = new CountDownLatch(1);
            var work =
                    child.registerExternalExecution(
                            "REMOTE_JOB",
                            () -> {
                                called.countDown();
                                return acknowledgement;
                            });
            work.started();
            child.revoke();
            assertTrue(called.await(2, TimeUnit.SECONDS));
            var pending = root.externalExecutionSnapshot();
            assertEquals(1, pending.pendingCancellation());
            assertEquals(1, pending.active());
            assertFalse(pending.allTerminationConfirmed());
            assertEquals(child.sessionId(), pending.entries().get(0).sessionId());
            root.checkActive();
            acknowledgement.complete(TerminationEvidence.CONFIRMED);
            await(() -> root.externalExecutionSnapshot().allTerminationConfirmed());
            assertEquals(0, root.externalExecutionSnapshot().active());
            assertEquals(
                    ExternalExecutionRegistry.State.CANCEL_CONFIRMED,
                    root.externalExecutionSnapshot().entries().get(0).state());
        }
    }

    @Test
    void timeoutHoldsUnknownWorkAndLateProofCannotUnfreezeRoot() throws Exception {
        try (var control = control();
                var root = control.openTaskSession(ID, limits());
                var other = control.openTaskSession(ID, limits());
                var child = control.openDelegatedSession(root, ID)) {
            var acknowledgement = new CompletableFuture<TerminationEvidence>();
            var work = child.registerExternalExecution("REMOTE_JOB", () -> acknowledgement);
            work.started();
            child.revoke();
            await(() -> root.externalExecutionSnapshot().unknown() == 1);
            assertEquals(1, root.externalExecutionSnapshot().active());
            assertEquals(
                    "external_execution_unconfirmed", root.externalExecutionSnapshot().fault());
            assertThrows(GovernanceException.class, root::checkActive);
            other.checkActive();
            acknowledgement.complete(TerminationEvidence.CONFIRMED);
            await(() -> root.externalExecutionSnapshot().allTerminationConfirmed());
            assertEquals(0, root.externalExecutionSnapshot().active());
            assertEquals(
                    "external_execution_unconfirmed", root.externalExecutionSnapshot().fault());
            assertThrows(GovernanceException.class, root::checkActive);
        }
    }

    @Test
    void cancelledFutureIsNotEvidenceOfExternalTermination() throws Exception {
        try (var control = control();
                var root = control.openTaskSession(ID, limits())) {
            var delivery = new CompletableFuture<TerminationEvidence>();
            var work = root.registerExternalExecution("HTTP_REQUEST", () -> delivery);
            work.started();
            delivery.cancel(true);
            work.requestCancellation();
            await(() -> root.externalExecutionSnapshot().unknown() == 1);
            assertFalse(root.externalExecutionSnapshot().allTerminationConfirmed());
            assertEquals(1, root.externalExecutionSnapshot().active());
        }
    }

    @Test
    void parentCascadeIncludesGrandchildAndAcknowledgementsAreIndependent() throws Exception {
        try (var control = control();
                var root = control.openTaskSession(ID, limits());
                var child = control.openDelegatedSession(root, ID);
                var grandchild = control.openDelegatedSession(child, ID)) {
            var first = new CompletableFuture<TerminationEvidence>();
            var second = new CompletableFuture<TerminationEvidence>();
            child.registerExternalExecution("REMOTE_JOB", () -> first).started();
            grandchild.registerExternalExecution("REMOTE_JOB", () -> second).started();
            root.revoke();
            assertEquals(2, root.externalExecutionSnapshot().pendingCancellation());
            first.complete(TerminationEvidence.CONFIRMED);
            await(() -> root.externalExecutionSnapshot().pendingCancellation() == 1);
            assertFalse(root.externalExecutionSnapshot().allTerminationConfirmed());
            second.complete(TerminationEvidence.CONFIRMED);
            await(() -> root.externalExecutionSnapshot().allTerminationConfirmed());
        }
    }

    @Test
    void runtimeShutdownDoesNotLosePendingConfirmationForAlreadyClosedSession() {
        var control = control();
        var root = control.openTaskSession(ID, limits());
        var work = root.registerExternalExecution("REMOTE_JOB", CompletableFuture::new);
        work.started();
        root.close();
        control.close();
        assertEquals(1, root.externalExecutionSnapshot().unknown());
        assertFalse(root.externalExecutionSnapshot().allTerminationConfirmed());
    }

    @Test
    void workerOnlyCompletionDoesNotClaimResourceCleanupAndExportPreservesHistory() {
        try (var control = control();
                var root = control.openTaskSession(ID, limits())) {
            var work = root.registerExternalExecution("SANDBOX_TASK", CompletableFuture::new);
            work.started();
            work.finished(TerminationEvidence.WORKER_ONLY);
            var snapshot = root.externalExecutionSnapshot();
            assertEquals(0, snapshot.active());
            assertFalse(snapshot.allTerminationConfirmed());
            assertEquals(
                    ExternalExecutionRegistry.State.COMPLETED, snapshot.entries().get(0).state());
            var exported = root.exportDelegationState();
            assertEquals(1, exported.external().rows().size());
            assertEquals(work.id(), exported.external().rows().get(0).entry().executionId());
            assertEquals(
                    TerminationEvidence.WORKER_ONLY,
                    exported.external().rows().get(0).entry().evidence());
        }
    }
}
