package io.agt.delegation.jdbc;

import static io.agt.delegation.jdbc.DurableTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.data.*;
import io.agt.core.flow.*;
import io.agt.delegation.*;
import io.agt.provenance.JdbcLabelStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JdbcGrantRegistryTest {
    @Test
    void fragmentReadRequiresMatchingAdmittedActionAndPersistsItsActualCausalReference() {
        try (var f = new Fixture(directory, -1, true, true, false)) {
            var child = f.claim("session");
            f.registry.guardedCommit(
                    child.lease(),
                    child.session(),
                    "read",
                    InterventionPoint.PRE_DATA_ACCESS,
                    null,
                    MEMORY.target(DataOperation.MEMORY_READ, null),
                    JsonValues.digest("request"));
            var fragment = DataFragment.message(Map.of("role", "USER", "text", "synthetic"));
            var projection =
                    DataFragment.target(MEMORY, DataOperation.MEMORY_READ, null, List.of(fragment));
            assertEquals(
                    "delegation_data_action_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.registry()
                                                    .flowValidate(
                                                            child.lease(),
                                                            child.session(),
                                                            "missing",
                                                            InterventionPoint.POST_DATA_ACCESS,
                                                            null,
                                                            projection))
                            .code());
            assertEquals(
                    "delegation_data_action_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.registry()
                                                    .flowValidate(
                                                            child.lease(),
                                                            child.session(),
                                                            InterventionPoint.POST_DATA_ACCESS,
                                                            null,
                                                            projection))
                            .code());
            var substituted =
                    DataFragment.target(MEMORY, DataOperation.MEMORY_ADD, null, List.of(fragment));
            assertEquals(
                    "delegation_data_action_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.registry()
                                                    .flowValidate(
                                                            child.lease(),
                                                            child.session(),
                                                            "read",
                                                            InterventionPoint.POST_DATA_ACCESS,
                                                            null,
                                                            substituted))
                            .code());
            for (int i = 0; i < 2; i++)
                f.registry()
                        .flowValidate(
                                child.lease(),
                                child.session(),
                                "read",
                                InterventionPoint.POST_DATA_ACCESS,
                                null,
                                projection);
            var history = f.labels.fragmentHistory(MEMORY, fragment, 1).orElseThrow();
            assertEquals("read", history.provenance().actionId());
            assertEquals(LabelProvenance.Kind.CLASSIFICATION, history.provenance().kind());
            assertEquals(1, f.labels.readFragment(MEMORY, fragment).orElseThrow().revision());
            assertTrue(
                    f.registry()
                            .flowSnapshot(child.lease(), child.session())
                            .sources()
                            .contains("stored-fragment:" + fragment.key(MEMORY) + ":1"));
        }
    }

    @TempDir Path directory;

    @Test
    void reopenPreservesEarlierLocalConsumptionSharedLimitsAndRevocation() {
        try (var f = new Fixture(directory, 200, false, false, true)) {
            var child = f.claim("session");
            var reopened = f.registry();
            assertEquals(50, f.inspect().usage().settledTokens());
            reopened.usageReserve(child.lease(), child.session(), "first", "fixture", 100, 50);
            reopened.usageDispatch(child.lease(), child.session(), "first");
            f.registry()
                    .usageSettle(child.lease(), child.session(), "first", "fixture", 40, 10, 50);
            assertEquals(100, f.inspect().usage().settledTokens());
            assertEquals(0, new BigDecimal("0.00012").compareTo(f.inspect().usage().settledCost()));
            assertEquals(
                    "task_token_budget_exceeded",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.registry()
                                                    .usageReserve(
                                                            child.lease(),
                                                            child.session(),
                                                            "second",
                                                            "fixture",
                                                            100,
                                                            50))
                            .code());
            for (int i = 0; i < 3; i++)
                f.registry()
                        .commit(
                                child.lease(),
                                child.session(),
                                "tool-" + i,
                                "tool",
                                JsonValues.digest("fixture"));
            assertEquals(
                    "task_tool_budget_exceeded",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.registry()
                                                    .commit(
                                                            child.lease(),
                                                            child.session(),
                                                            "fourth",
                                                            "tool",
                                                            JsonValues.digest("fixture")))
                            .code());
            assertEquals(3, f.inspect().toolCalls());
            reopened.revoke(child.lease().grantId());
            assertThrows(
                    GovernanceException.class,
                    () -> f.registry().check(child.lease(), child.session()));
            assertEquals(100, f.inspect().usage().settledTokens());
        }
    }

    @Test
    void preparedRefundAndDispatchedUnknownHoldSurviveReconstruction() {
        try (var f = new Fixture(directory, 1000, false, false, false)) {
            var a = f.claim("a");
            var b = f.claim("b");
            f.registry.usageReserve(a.lease(), "a", "prepared", "fixture", 100, 50);
            f.registry.usageReserve(b.lease(), "b", "dispatched", "fixture", 100, 50);
            f.registry.usageDispatch(b.lease(), "b", "dispatched");
            assertEquals(300, f.inspect().usage().heldTokens());
            f.registry().complete(a.lease(), "a");
            assertEquals(150, f.inspect().usage().heldTokens());
            f.registry().complete(b.lease(), "b");
            var state = f.inspect();
            assertEquals(150, state.usage().heldTokens());
            assertEquals(1, state.usage().uncertainCalls());
            assertEquals("model_usage_unconfirmed", state.usage().fault());
            assertFalse(state.active());
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.source.disclose(
                                    "result", value -> value, (original, value) -> (String) value));
            assertEquals(150, f.inspect().usage().heldTokens());
        }
    }

    @Test
    void invalidModelAndExceededBoundsPersistTheirFailureInsteadOfRollingBack() {
        for (boolean mismatch : List.of(false, true)) {
            Path path = directory.resolve(mismatch ? "mismatch" : "bounds");
            try (var f = new Fixture(path, 1000, false, false, false)) {
                var child = f.claim("session");
                f.registry.usageReserve(child.lease(), child.session(), "call", "fixture", 100, 50);
                f.registry.usageDispatch(child.lease(), child.session(), "call");
                String code =
                        mismatch ? "model_usage_identity_mismatch" : "model_usage_bound_exceeded";
                assertEquals(
                        code,
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                f.registry()
                                                        .usageSettle(
                                                                child.lease(),
                                                                child.session(),
                                                                "call",
                                                                mismatch ? "unpriced" : "fixture",
                                                                101,
                                                                50,
                                                                151))
                                .code());
                var state = f.inspect().usage();
                assertEquals(code, state.fault());
                assertEquals(mismatch ? 150 : 0, state.heldTokens());
                assertEquals(mismatch ? 0 : 151, state.settledTokens());
            }
        }
    }

    @Test
    void expirationDoesNotRefundUnknownFeesAndInspectionRemainsAuthorizedAfterTokenExpiry() {
        try (var f = new Fixture(directory, 1000, false, false, false)) {
            var a = f.claim("a");
            var b = f.claim("b");
            f.registry.usageReserve(a.lease(), "a", "pending", "fixture", 100, 50);
            f.registry.usageDispatch(a.lease(), "a", "pending");
            f.clock.time.addAndGet(1500);
            f.registry.heartbeatRoot(f.root.lease());
            f.registry.check(b.lease(), "b");
            f.clock.time.addAndGet(1000);
            assertEquals("model_usage_unconfirmed", f.inspect().usage().fault());
            assertThrows(GovernanceException.class, () -> f.registry().check(b.lease(), "b"));
            f.clock.time.addAndGet(61000);
            assertEquals(150, f.inspect().usage().heldTokens());
            assertThrows(GovernanceException.class, () -> f.root.usageSnapshot());
            assertEquals(
                    "delegation_inspection_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.registry()
                                                    .inspect(
                                                            f.root.lease().grantId(),
                                                            new TrustedContext(
                                                                    "auditor",
                                                                    "other-tenant",
                                                                    "user",
                                                                    Set.of("governance:inspect"))))
                            .code());
            assertEquals(
                    "delegation_inspection_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.registry()
                                                    .inspect(
                                                            f.root.lease().grantId(),
                                                            new TrustedContext(
                                                                    "auditor",
                                                                    "tenant",
                                                                    "other-user",
                                                                    Set.of("governance:inspect"))))
                            .code());
        }
    }

    @Test
    void concurrentRegistryInstancesCannotClaimSameGrantOrMultiplyUsage() throws Exception {
        try (var f = new Fixture(directory, 450, false, false, false)) {
            var token = f.token();
            var decoded =
                    JsonValues.parseObject(
                            new String(
                                    Base64.getUrlDecoder().decode(token.value().split("\\.")[1]),
                                    java.nio.charset.StandardCharsets.UTF_8));
            String id = (String) decoded.get("jti"), digest = (String) decoded.get("grant_hash");
            var pool = Executors.newFixedThreadPool(12);
            var accepted = new CopyOnWriteArrayList<GrantLease>();
            try {
                var tasks = new ArrayList<Future<?>>();
                var gate = new CountDownLatch(1);
                for (int i = 0; i < 12; i++)
                    tasks.add(
                            pool.submit(
                                    () -> {
                                        gate.await();
                                        var lease = lease(id);
                                        try {
                                            f.registry()
                                                    .claim(id, digest, "reader", AUDIENCE, lease);
                                            accepted.add(lease);
                                        } catch (GovernanceException denied) {
                                            assertEquals("delegation_replay_denied", denied.code());
                                        }
                                        return null;
                                    }));
                gate.countDown();
                for (var task : tasks) task.get(10, TimeUnit.SECONDS);
                assertEquals(1, accepted.size());
                var lease = accepted.get(0);
                f.registry.bind(lease, "session");
                tasks.clear();
                var held = new CopyOnWriteArrayList<String>();
                for (int i = 0; i < 20; i++) {
                    String receipt = "call-" + i;
                    tasks.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            f.registry()
                                                    .usageReserve(
                                                            lease, "session", receipt, "fixture",
                                                            100, 50);
                                            held.add(receipt);
                                        } catch (GovernanceException denied) {
                                            assertEquals(
                                                    "task_token_budget_exceeded", denied.code());
                                        }
                                    }));
                }
                for (var task : tasks) task.get(10, TimeUnit.SECONDS);
                assertEquals(3, held.size());
                assertEquals(450, f.inspect().usage().heldTokens());
                for (String receipt : held) f.registry().usageClose(lease, "session", receipt);
                assertEquals(0, f.inspect().usage().heldTokens());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void restoredFlowAndCatalogIdentityCannotBeReplacedAndSourceOutputUsesCurrentState() {
        try (var f = new Fixture(directory, -1, true, true, false)) {
            var child = f.claim("session");
            f.registry.guardedCommit(
                    child.lease(),
                    child.session(),
                    "lookup",
                    InterventionPoint.PRE_TOOL_CALL,
                    "lookup",
                    Map.of(),
                    JsonValues.digest("fixture"));
            assertEquals(
                    DataLabel.CONFIDENTIAL,
                    f.registry().flowSnapshot(child.lease(), child.session()).label());
            assertEquals(DataLabel.CONFIDENTIAL, f.source.dataFlowSnapshot().label());
            var otherData = dataSource("jdbc:h2:file:" + directory.resolve("other-labels"));
            var other = new JdbcLabelStore(otherData, 2);
            other.initializeSchema();
            assertNotEquals(f.labels.catalogIdentity(), other.catalogIdentity());
            var wrong =
                    new JdbcGrantRegistry(
                            f.data,
                            "primary",
                            f.clock,
                            other,
                            2,
                            JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
            assertEquals(
                    "delegation_checkpoint_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> wrong.check(child.lease(), child.session()))
                            .code());
            assertEquals(
                    "data_flow_output_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.source.disclose(
                                                    "fixture",
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
        }
    }

    @Test
    void damagedCheckpointDoesNotBecomeAnEmptyRegistry() throws Exception {
        try (var f = new Fixture(directory, -1, false, false, false)) {
            var child = f.claim("session");
            byte[] saved;
            try (var connection = f.data.getConnection();
                    var query = connection.createStatement();
                    var rows = query.executeQuery("SELECT state_blob FROM agt_authority_root")) {
                assertTrue(rows.next());
                saved = rows.getBytes(1);
            }
            try {
                try (var connection = f.data.getConnection();
                        var update =
                                connection.prepareStatement(
                                        "UPDATE agt_authority_root SET state_blob=?")) {
                    update.setBytes(1, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    update.executeUpdate();
                }
                assertEquals(
                        "delegation_checkpoint_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> f.registry().check(child.lease(), child.session()))
                                .code());
                try (var connection = f.data.getConnection();
                        var query = connection.createStatement();
                        var rows =
                                query.executeQuery("SELECT state_blob FROM agt_authority_root")) {
                    assertTrue(rows.next());
                    assertEquals(
                            "{}",
                            new String(rows.getBytes(1), java.nio.charset.StandardCharsets.UTF_8));
                }
            } finally {
                try (var connection = f.data.getConnection();
                        var update =
                                connection.prepareStatement(
                                        "UPDATE agt_authority_root SET state_blob=?")) {
                    update.setBytes(1, saved);
                    update.executeUpdate();
                }
            }
        }
    }
}
