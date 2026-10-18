package io.agt.delegation.jdbc;

import static io.agt.delegation.jdbc.DurableTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.delegation.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DurableFailureTest {
    @TempDir Path directory;

    @Test
    void lateOrUncertainCommitNeverRefundsDispatchedUsageOrReturnsAdmission() {
        for (boolean late : new boolean[] {false, true}) {
            try (var f =
                    new Fixture(
                            directory.resolve(late ? "late" : "unknown"),
                            1000,
                            false,
                            false,
                            false)) {
                var child = f.claim("session");
                f.registry.usageReserve(
                        child.lease(), child.session(), "receipt", "fixture", 100, 50);
                var armed = new AtomicBoolean(true);
                Thread owner = Thread.currentThread();
                var wrapped =
                        (DataSource)
                                Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class<?>[] {DataSource.class},
                                        (proxy, method, args) -> {
                                            Object result = invoke(f.data, method, args);
                                            if (!method.getName().equals("getConnection"))
                                                return result;
                                            return Proxy.newProxyInstance(
                                                    getClass().getClassLoader(),
                                                    new Class<?>[] {Connection.class},
                                                    (connection, operation, values) -> {
                                                        Object value =
                                                                invoke(result, operation, values);
                                                        if (operation.getName().equals("commit")
                                                                && Thread.currentThread() == owner
                                                                && armed.compareAndSet(
                                                                        true, false)) {
                                                            if (late) f.clock.time.addAndGet(2500);
                                                            else
                                                                throw new SQLException(
                                                                        "Commit acknowledgement lost",
                                                                        "08006");
                                                        }
                                                        return value;
                                                    });
                                        });
                var registry =
                        new JdbcGrantRegistry(
                                wrapped,
                                "primary",
                                f.clock,
                                null,
                                2,
                                JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
                assertEquals(
                        late ? "delegation_inactive_denied" : "delegation_registry_unavailable",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                registry.usageDispatch(
                                                        child.lease(), child.session(), "receipt"))
                                .code());
                if (late) {
                    // 已观察到到期后，将宿主时钟调回也不能复活授权。
                    f.clock.time.addAndGet(-2500);
                    assertThrows(
                            GovernanceException.class,
                            () -> f.registry().check(child.lease(), child.session()));
                } else f.clock.time.addAndGet(2500);
                var state = f.inspect().usage();
                assertEquals(150, state.heldTokens());
                assertEquals(1, state.uncertainCalls());
                assertEquals(0, state.settledTokens());
                assertEquals("model_usage_unconfirmed", state.fault());
            }
        }
    }

    @Test
    void duplicateRootRegistrationCannotResetConsumptionAndMissingSchemaDoesNotFallback() {
        try (var f = new Fixture(directory, -1, false, false, false)) {
            var doc = f.registry.parent(f.root.lease());
            var child = f.claim("session");
            f.registry.commit(
                    child.lease(), child.session(), "tool", "tool", JsonValues.digest("call"));
            assertEquals(
                    "delegation_replay_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.registry()
                                                    .registerRoot(
                                                            doc,
                                                            f.root.lease(),
                                                            f.source.sessionId(),
                                                            null,
                                                            null))
                            .code());
            assertEquals(1, f.inspect().toolCalls());
            var empty =
                    new JdbcGrantRegistry(
                            dataSource("jdbc:h2:mem:missing-authority"),
                            "primary",
                            f.clock,
                            null,
                            2,
                            JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
            assertEquals(
                    "delegation_registry_unavailable",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> empty.check(child.lease(), child.session()))
                            .code());
        }
    }

    @Test
    void validChecksumCannotHideInconsistentAdmissionCounters() throws Exception {
        try (var f = new Fixture(directory, -1, false, false, false)) {
            var child = f.claim("session");
            f.registry.commit(
                    child.lease(), child.session(), "tool", "tool", JsonValues.digest("call"));
            byte[] original;
            try (var connection = f.data.getConnection();
                    var query = connection.createStatement();
                    var rows = query.executeQuery("SELECT state_blob FROM agt_authority_root")) {
                assertTrue(rows.next());
                original = rows.getBytes(1);
            }
            var codec = new CheckpointCodec(JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
            var checkpoint = codec.decode(original, CheckpointCodec.digest(original));
            var nodes = new java.util.ArrayList<>(checkpoint.nodes());
            for (int i = 0; i < nodes.size(); i++) {
                var node = nodes.get(i);
                if (GrantCodec.decode(node.document()).parentId() == null)
                    nodes.set(
                            i,
                            new RegistryCheckpoint.Node(
                                    node.document(),
                                    node.state(),
                                    node.secretDigest(),
                                    node.session(),
                                    node.heartbeatUntil(),
                                    node.models(),
                                    node.tools() + 1,
                                    node.data(),
                                    node.retired(),
                                    node.admissions(),
                                    node.usage(),
                                    node.flow(),
                                    node.work()));
            }
            try {
                replace(f.data, codec.encode(new RegistryCheckpoint(1, nodes)));
                assertEquals(
                        "delegation_checkpoint_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> f.registry().check(child.lease(), child.session()))
                                .code());
            } finally {
                replace(f.data, original);
            }
        }
    }

    private static void replace(DataSource data, byte[] bytes) throws SQLException {
        try (var connection = data.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE agt_authority_root SET state_blob=?,checksum=?")) {
            update.setBytes(1, bytes);
            update.setString(2, CheckpointCodec.digest(bytes));
            update.executeUpdate();
        }
    }

    @Test
    void checkpointCapacityFailureDurablyBlocksEvenARegistryWithLargerLimit() throws Exception {
        try (var f = new Fixture(directory, 1000, false, false, false)) {
            var child = f.claim("session");
            f.registry.usageReserve(child.lease(), child.session(), "receipt", "fixture", 100, 50);
            f.registry.usageDispatch(child.lease(), child.session(), "receipt");
            byte[] original;
            try (var connection = f.data.getConnection();
                    var query = connection.createStatement();
                    var rows = query.executeQuery("SELECT state_blob FROM agt_authority_root")) {
                assertTrue(rows.next());
                original = rows.getBytes(1);
            }
            var codec = new CheckpointCodec(JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
            var checkpoint = codec.decode(original, CheckpointCodec.digest(original));
            int rootIndex = -1;
            for (int i = 0; i < checkpoint.nodes().size(); i++)
                if (checkpoint.nodes().get(i).usage() != null) rootIndex = i;
            assertTrue(rootIndex >= 0);
            var root = checkpoint.nodes().get(rootIndex);
            // 真实检查点可保留已经清理的旧授权 ID。把这种历史填到配置上限附近。
            int cap = 1_048_576;
            int target = cap - 1;
            int remainder;
            do {
                remainder = (target-- - original.length + 1) % 131;
            } while (remainder < 4);
            target++;
            int count = (target - original.length + 1) / 131;
            var ended = new java.util.HashSet<String>();
            for (int i = 0; i < count; i++) {
                String prefix = "expired-" + i + "-";
                ended.add(prefix + "x".repeat(128 - prefix.length()));
            }
            ended.add("z".repeat(remainder - 3));
            var usage = root.usage();
            var replacement =
                    new RegistryCheckpoint.Usage(
                            usage.baseline(),
                            usage.balance(),
                            usage.receipts(),
                            usage.deadlines(),
                            ended,
                            usage.children());
            var nodes = new java.util.ArrayList<>(checkpoint.nodes());
            nodes.set(
                    rootIndex,
                    new RegistryCheckpoint.Node(
                            root.document(),
                            root.state(),
                            root.secretDigest(),
                            root.session(),
                            root.heartbeatUntil(),
                            root.models(),
                            root.tools(),
                            root.data(),
                            root.retired(),
                            root.admissions(),
                            replacement,
                            root.flow(),
                            root.work()));
            byte[] full = codec.encode(new RegistryCheckpoint(1, nodes));
            assertEquals(target, full.length);
            try {
                replace(f.data, full);
                var small = new JdbcGrantRegistry(f.data, "primary", f.clock, null, 2, cap);
                assertEquals(
                        "delegation_checkpoint_capacity_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                small.usageSettle(
                                                        child.lease(),
                                                        child.session(),
                                                        "receipt",
                                                        "fixture",
                                                        40,
                                                        10,
                                                        50))
                                .code());
                assertEquals(
                        "delegation_checkpoint_capacity_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                f.registry()
                                                        .commit(
                                                                child.lease(),
                                                                child.session(),
                                                                "next",
                                                                "tool",
                                                                JsonValues.digest("call")))
                                .code());
                try (var connection = f.data.getConnection();
                        var query = connection.createStatement();
                        var rows = query.executeQuery("SELECT blocked FROM agt_authority_root")) {
                    assertTrue(rows.next());
                    assertTrue(rows.getBoolean(1));
                }
            } finally {
                replace(f.data, original);
                try (var connection = f.data.getConnection();
                        var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE agt_authority_root SET blocked=FALSE");
                }
            }
        }
    }

    private static Object invoke(Object target, Method method, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
