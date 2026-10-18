package io.agt.core;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.policy.StructuredPolicy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DelegatedSessionTest {
    private static final TrustedContext ROOT =
            new TrustedContext("coordinator", "tenant", "user", Set.of("read", "write"));
    private static final TrustedContext READ =
            new TrustedContext("research", "tenant", "user", Set.of("read"));

    private AgentControl runtime() {
        return AgentControl.builder(StructuredPolicy.builder("v1").build()).audit(r -> {}).build();
    }

    @Test
    void explicitDelegationNarrowsAuthorityWithoutReplacingThreadSession() throws Exception {
        try (var control = runtime();
                var parent = control.openSession(ROOT);
                var child = control.openDelegatedSession(parent, READ)) {
            assertSame(parent, control.currentSession());
            assertEquals(parent.sessionId(), child.parentSessionId());
            assertEquals(1, child.delegationDepth());
            assertEquals(Set.of("read"), child.context().permissions());
            var worker = Executors.newSingleThreadExecutor();
            try {
                assertEquals(
                        "ok",
                        worker.submit(
                                        () -> {
                                            try (var scope = child.bind()) {
                                                assertSame(child, control.currentSession());
                                                return control.run("task", input -> "ok");
                                            }
                                        })
                                .get(3, TimeUnit.SECONDS));
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    void rejectsEscalationIdentityChangesAndForeignRuntime() {
        try (var control = runtime();
                var other = runtime();
                var parent = control.openSession(ROOT)) {
            for (var identity :
                    Set.of(
                            new TrustedContext("child", "tenant", "user", Set.of("admin")),
                            new TrustedContext("child", "other", "user", Set.of("read")),
                            new TrustedContext("child", "tenant", "other", Set.of("read")))) {
                assertEquals(
                        "delegation_scope_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> control.openDelegatedSession(parent, identity))
                                .code());
            }
            assertEquals(
                    "delegation_runtime_mismatch",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> other.openDelegatedSession(parent, READ))
                            .code());
            parent.checkActive();
        }
    }

    @Test
    void ancestorRevocationTerminatesDescendantsAndPendingDelivery() {
        try (var control = runtime();
                var parent = control.openSession(ROOT);
                var child = control.openDelegatedSession(parent, READ);
                var grandchild = control.openDelegatedSession(child, READ)) {
            var delivery = grandchild.watchTermination(new CompletableFuture<String>());
            parent.revoke();
            assertTrue(delivery.isCompletedExceptionally());
            assertThrows(GovernanceException.class, child::checkActive);
            assertThrows(GovernanceException.class, grandchild::checkActive);
            assertThrows(
                    GovernanceException.class, () -> control.openDelegatedSession(parent, READ));
            assertEquals(2, grandchild.delegationDepth());
        }
    }

    @Test
    void childFailureDoesNotRevokeParentOrSiblingButStopsItsOwnDescendants() {
        try (var control = runtime();
                var parent = control.openSession(ROOT);
                var child = control.openDelegatedSession(parent, READ);
                var sibling = control.openDelegatedSession(parent, READ);
                var grandchild = control.openDelegatedSession(child, READ)) {
            child.refuse("permission_denied");
            assertThrows(GovernanceException.class, grandchild::checkActive);
            parent.checkActive();
            sibling.checkActive();
        }
    }

    @Test
    void parentCloseCancelsAnAlreadyRunningAsyncChildAndSuppressesLateOutput() throws Exception {
        try (var control = runtime();
                var parent = control.openDetachedSession(ROOT);
                var child = control.openDelegatedSession(parent, READ)) {
            var source = new CompletableFuture<String>();
            var entered = new CountDownLatch(1);
            var delivered = new AtomicInteger();
            var result =
                    child.runAsync(
                            "task",
                            input -> {
                                entered.countDown();
                                return source;
                            },
                            value -> {
                                delivered.incrementAndGet();
                                return value;
                            },
                            (original, value) -> (String) value);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            parent.close();
            assertThrows(Exception.class, () -> result.get(3, TimeUnit.SECONDS));
            source.complete("late output");
            assertEquals(0, delivered.get());
        }
    }

    @Test
    void creatingChildrenRacingWithRevocationCannotLeaveActiveChild() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> {
                    try (var control = runtime();
                            var parent = control.openDetachedSession(ROOT)) {
                        var workers = Executors.newFixedThreadPool(2);
                        var gate = new CountDownLatch(1);
                        try {
                            var creation =
                                    workers.submit(
                                            () -> {
                                                gate.await();
                                                try {
                                                    return control.openDelegatedSession(
                                                            parent, READ);
                                                } catch (GovernanceException denied) {
                                                    return null;
                                                }
                                            });
                            var revocation =
                                    workers.submit(
                                            () -> {
                                                gate.await();
                                                parent.revoke();
                                                return null;
                                            });
                            gate.countDown();
                            var child = creation.get(3, TimeUnit.SECONDS);
                            revocation.get(3, TimeUnit.SECONDS);
                            if (child != null) {
                                try (child) {
                                    assertThrows(GovernanceException.class, child::checkActive);
                                }
                            }
                        } finally {
                            workers.shutdownNow();
                        }
                    }
                });
    }
}
