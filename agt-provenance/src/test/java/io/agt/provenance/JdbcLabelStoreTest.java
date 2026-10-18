package io.agt.provenance;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.data.DataFragment;
import io.agt.core.data.DataResource;
import io.agt.core.flow.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JdbcLabelStoreTest {
    @Test
    void fragmentNamespaceTenantAndPartitionAreIndependentAndLabelsRemainMonotonic()
            throws Exception {
        var store = new JdbcLabelStore(source("fragments", true), 2);
        store.initializeSchema();
        var fragment = DataFragment.message(Map.of("role", "USER", "text", "synthetic"));
        var otherPartition = resource("tenant-a", "user", "other-memory");
        var otherTenant = resource("tenant-b", "user", "stable-memory");
        store.record(
                MEMORY, DataLabel.INTERNAL, new LabelProvenance("parent", "classify", List.of()));
        var pool = Executors.newFixedThreadPool(8);
        try {
            var calls = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) {
                int n = i;
                calls.add(
                        pool.submit(
                                () ->
                                        store.recordFragment(
                                                MEMORY,
                                                fragment,
                                                n == 0 ? DataLabel.SECRET : DataLabel.PUBLIC,
                                                new LabelProvenance(
                                                        "writer",
                                                        "write-" + n,
                                                        List.of("host-input")))));
            }
            for (var call : calls) call.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        var reopened = new JdbcLabelStore(source("fragments", true), 2);
        assertEquals(
                DataLabel.SECRET, reopened.readFragment(MEMORY, fragment).orElseThrow().label());
        assertEquals(8, reopened.readFragment(MEMORY, fragment).orElseThrow().revision());
        assertEquals(DataLabel.INTERNAL, reopened.read(MEMORY).orElseThrow().label());
        assertTrue(reopened.readFragment(otherPartition, fragment).isEmpty());
        assertTrue(reopened.readFragment(otherTenant, fragment).isEmpty());
        assertNotEquals(JsonValues.digest(MEMORY.descriptor()), fragment.key(MEMORY));
        assertEquals(
                LabelProvenance.Kind.WRITE,
                reopened.fragmentHistory(MEMORY, fragment, 8).orElseThrow().provenance().kind());
    }

    @Test
    void maximumBoundedProvenanceReferencesRoundTripWithoutTruncation() {
        var store = new JdbcLabelStore(source("bounds", false), 2);
        store.initializeSchema();
        var refs = new ArrayList<String>();
        for (int i = 0; i < 512; i++)
            refs.add(("reference-" + i + "-" + "x".repeat(512)).substring(0, 512));
        var provenance = new LabelProvenance("task", "max-bound", refs);
        store.record(MEMORY, DataLabel.SECRET, provenance);
        assertEquals(provenance, store.history(MEMORY, 1).orElseThrow().provenance());
    }

    @TempDir Path directory;

    static DataResource resource(String tenant, String owner, String instance) {
        return new DataResource("memory", DataResource.Kind.MEMORY, tenant, owner, instance);
    }

    static final DataResource MEMORY = resource("tenant-a", "user", "stable-memory");

    JdbcDataSource source(String name, boolean file) {
        var source = new JdbcDataSource();
        source.setURL(
                file
                        ? "jdbc:h2:file:"
                                + directory.resolve(name)
                                + ";WRITE_DELAY=0;LOCK_TIMEOUT=2000"
                        : "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000");
        return source;
    }

    LabelProvenance origin(String action) {
        return new LabelProvenance("producer-task", action, List.of("host-input", "tool:read"));
    }

    @Test
    void fileReopenRetainsLabelsOriginsAndTenantPrincipalInstanceIsolation() throws Exception {
        var source = source("labels", true);
        var store = new JdbcLabelStore(source, 2);
        store.initializeSchema();
        var written = store.record(MEMORY, DataLabel.CONFIDENTIAL, origin("first"));
        assertEquals(1, written.revision());
        var reopenedSource = source("labels", true);
        var reopened = new JdbcLabelStore(reopenedSource, 2);
        assertEquals(
                written,
                reopened.read(resource("tenant-a", "user", "stable-memory")).orElseThrow());
        assertEquals(origin("first"), reopened.history(MEMORY, 1).orElseThrow().provenance());
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                LabelStoreReadWorker.class.getName())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        var reader = Executors.newSingleThreadExecutor();
        try {
            try (var input =
                    new java.io.OutputStreamWriter(
                            process.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                input.write(JsonValues.write(Map.of("jdbc", source.getURL())) + "\n");
            }
            var output =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    process.getInputStream(),
                                    java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(
                    "CONFIDENTIAL:1:producer-task:WRITE",
                    reader.submit(output::readLine).get(8, TimeUnit.SECONDS));
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
            reader.shutdownNow();
        }
        for (var other :
                List.of(
                        resource("tenant-b", "user", "stable-memory"),
                        resource("tenant-a", "other", "stable-memory"),
                        resource("tenant-a", "user", "other-memory")))
            assertTrue(reopened.read(other).isEmpty());
    }

    @Test
    void lowerWritesCannotDowngradeAndIdempotentActionsCannotChangeProvenance() {
        var store = new JdbcLabelStore(source("memory", false), 2);
        store.initializeSchema();
        var first = store.record(MEMORY, DataLabel.CONFIDENTIAL, origin("first"));
        var secret = store.record(MEMORY, DataLabel.SECRET, origin("secret"));
        var lower = store.record(MEMORY, DataLabel.PUBLIC, origin("lower"));
        assertEquals(DataLabel.SECRET, lower.label());
        assertEquals(3, lower.revision());
        assertEquals(2, store.history(MEMORY, 3).orElseThrow().previousRevision());
        assertEquals(first, store.record(MEMORY, DataLabel.CONFIDENTIAL, origin("first")));
        assertEquals(lower, store.read(MEMORY).orElseThrow());
        assertEquals(
                "label_store_action_mismatch_denied",
                assertThrows(
                                GovernanceException.class,
                                () -> store.record(MEMORY, DataLabel.PUBLIC, origin("first")))
                        .code());
        assertEquals(2, secret.revision());
        assertTrue(store.history(MEMORY, 4).isEmpty());
    }

    @Test
    void independentStoreInstancesSerializeConcurrentUpdatesWithoutDroppingHistory()
            throws Exception {
        var source = source("parallel", false);
        var reader = new JdbcLabelStore(source, 2);
        reader.initializeSchema();
        var pool = Executors.newFixedThreadPool(20);
        var gate = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<StoredLabel>>();
            for (int i = 0; i < 20; i++) {
                final int id = i;
                results.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return new JdbcLabelStore(source, 2)
                                            .record(
                                                    MEMORY,
                                                    id % 5 == 0
                                                            ? DataLabel.SECRET
                                                            : DataLabel.PUBLIC,
                                                    origin("parallel-" + id));
                                }));
            }
            gate.countDown();
            var revisions = new HashSet<Long>();
            for (var result : results) revisions.add(result.get(10, TimeUnit.SECONDS).revision());
            assertEquals(20, revisions.size());
            assertEquals(new StoredLabel(DataLabel.SECRET, 20), reader.read(MEMORY).orElseThrow());
            DataLabel previous = DataLabel.PUBLIC;
            for (int i = 1; i <= 20; i++) {
                var event = reader.history(MEMORY, i).orElseThrow();
                assertEquals(i - 1, event.previousRevision());
                assertTrue(event.mark().label().permits(previous));
                previous = event.mark().label();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingSchemaAndBrokenCurrentHistoryFailClosed() throws Exception {
        var source = source("corrupt", false);
        var store = new JdbcLabelStore(source, 2);
        assertEquals(
                "label_store_unavailable",
                assertThrows(GovernanceException.class, () -> store.read(MEMORY)).code());
        store.initializeSchema();
        store.record(MEMORY, DataLabel.SECRET, origin("first"));
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM agt_resource_label");
        }
        assertEquals(
                "label_store_integrity_failed",
                assertThrows(GovernanceException.class, () -> store.read(MEMORY)).code());
        assertEquals(
                "label_store_integrity_failed",
                assertThrows(
                                GovernanceException.class,
                                () -> store.record(MEMORY, DataLabel.PUBLIC, origin("new")))
                        .code());
    }

    @Test
    void malformedOriginAndUnavailableConnectionNeverBecomePublicFallback() throws Exception {
        var source = source("malformed", false);
        var store = new JdbcLabelStore(source, 2);
        store.initializeSchema();
        store.record(MEMORY, DataLabel.SECRET, origin("first"));
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE agt_label_origin SET source_refs='{}'");
        }
        assertEquals(
                "label_store_integrity_failed",
                assertThrows(GovernanceException.class, () -> store.read(MEMORY)).code());
        var unavailable =
                (DataSource)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {DataSource.class},
                                (proxy, method, args) -> {
                                    throw new SQLException("hidden-test-connection-detail");
                                });
        var failed = new JdbcLabelStore(unavailable, 1);
        var denied = assertThrows(GovernanceException.class, () -> failed.read(MEMORY));
        assertEquals("label_store_unavailable", denied.code());
        assertFalse(denied.toString().contains("hidden-test-connection-detail"));
    }
}
