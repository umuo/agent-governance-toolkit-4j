package io.agt.delegation.jdbc;

import static io.agt.delegation.jdbc.DurableTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.execution.*;
import io.agt.delegation.*;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ExternalWorkRegistryTest {
    @TempDir Path directory;

    @Test
    void onlyOwnerCanFinishAndLateExpiredReceiptPersistsWithoutResumingExecution() {
        try (var f = new Fixture(directory, -1, false, false, false)) {
            var a = f.claim("a");
            var b = f.claim("b");
            f.registry.workRegister(a.lease(), "a", "work", "REMOTE_JOB");
            f.registry.workStart(a.lease(), "a", "work");
            assertEquals(
                    "external_execution_owner_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.registry()
                                                    .workFinish(
                                                            b.lease(),
                                                            "b",
                                                            "work",
                                                            TerminationEvidence.CONFIRMED))
                            .code());
            assertEquals(
                    "delegation_lease_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.registry()
                                                    .workFinish(
                                                            a.lease(),
                                                            "other",
                                                            "work",
                                                            TerminationEvidence.CONFIRMED))
                            .code());
            f.registry().complete(a.lease(), "a");
            assertEquals(1, f.inspect().external().pendingCancellation());
            f.clock.time.addAndGet(1500);
            f.registry.heartbeatRoot(f.root.lease());
            f.registry.check(b.lease(), "b");
            f.clock.time.addAndGet(600);
            assertEquals(1, f.inspect().external().unknown());
            assertThrows(
                    GovernanceException.class,
                    () -> f.registry().workStart(b.lease(), "b", "new-work"));
            f.clock.time.addAndGet(31000);
            f.registry().revoke(f.root.lease().grantId());
            f.registry().workFinish(a.lease(), "a", "work", TerminationEvidence.CONFIRMED);
            f.registry().workFinish(a.lease(), "a", "work", TerminationEvidence.CONFIRMED);
            var state = f.inspect();
            assertTrue(state.external().allTerminationConfirmed());
            assertEquals(0, state.external().active());
            assertEquals("external_execution_unconfirmed", state.external().fault());
            assertFalse(state.active());
            assertThrows(
                    GovernanceException.class,
                    () -> f.registry().workStart(a.lease(), "a", "work"));
        }
    }

    @Test
    void independentRegistryInstancesCannotMultiplyGlobalActiveWorkLimit() throws Exception {
        try (var f = new Fixture(directory, -1, false, false, false)) {
            var a = f.claim("a");
            var b = f.claim("b");
            var pool = Executors.newFixedThreadPool(8);
            try {
                var accepted = new ConcurrentLinkedQueue<String>();
                var tasks = new ArrayList<Future<?>>();
                for (int i = 0; i < 140; i++) {
                    String id = "work-" + i;
                    boolean first = i % 2 == 0;
                    var owner = first ? a : b;
                    tasks.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            f.registry()
                                                    .workRegister(
                                                            owner.lease(),
                                                            owner.session(),
                                                            id,
                                                            "REMOTE_JOB");
                                            accepted.add(id);
                                        } catch (GovernanceException denied) {
                                            assertEquals(
                                                    "external_execution_capacity_exceeded",
                                                    denied.code());
                                        }
                                    }));
                }
                for (var task : tasks) task.get(30, TimeUnit.SECONDS);
                assertEquals(128, accepted.size());
                assertEquals(128, f.inspect().external().active());
                f.registry().revoke(f.root.lease().grantId());
                var state = f.inspect().external();
                assertEquals(0, state.active());
                assertTrue(state.allTerminationConfirmed());
                assertTrue(
                        state.entries().stream()
                                .allMatch(
                                        row -> row.evidence() == TerminationEvidence.NOT_STARTED));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void completedSourceHistorySurvivesHandoffAndCannotBeReusedForDispatch() {
        var clock = new MutableClock();
        var ds = dataSource("jdbc:h2:file:" + directory.resolve("history") + ";WRITE_DELAY=0");
        var registry =
                new JdbcGrantRegistry(
                        ds, "primary", clock, null, 2, JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
        registry.initializeSchema();
        try (var control = AgentControl.builder(policy()).audit(record -> {}).build();
                var source =
                        control.openTaskSession(
                                SOURCE, new TaskLimits(CALLS, 10, 5, 3), null, scope());
                var authority =
                        new DelegationAuthority(
                                ISSUER,
                                "key-1",
                                (RSAPrivateKey) KEYS.getPrivate(),
                                registry,
                                clock)) {
            var history =
                    source.registerExternalExecution(
                            "DIRECT_PROCESS",
                            () -> CompletableFuture.completedFuture(TerminationEvidence.CONFIRMED));
            history.started();
            history.finished(TerminationEvidence.CONFIRMED);
            try (var root = authority.register(source)) {
                var saved = source.externalExecutionSnapshot();
                assertEquals(1, saved.entries().size());
                assertEquals(history.id(), saved.entries().get(0).executionId());
                assertTrue(saved.allTerminationConfirmed());
                var reopened =
                        new JdbcGrantRegistry(
                                ds,
                                "primary",
                                clock,
                                null,
                                2,
                                JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
                assertEquals(
                        history.id(),
                        reopened.inspect(root.lease().grantId(), AUDITOR)
                                .external()
                                .entries()
                                .get(0)
                                .executionId());
                assertEquals(
                        "external_execution_start_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                reopened.workStart(
                                                        root.lease(),
                                                        source.sessionId(),
                                                        history.id()))
                                .code());
            }
        }
    }
}
