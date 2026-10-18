package io.agt.provenance;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.data.*;
import io.agt.core.flow.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DocumentCatalogTest {
    @Test
    void callerCannotChangePreparedManifestWhileWaitingForDatabase() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var original = data();
        var gated =
                (javax.sql.DataSource)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {javax.sql.DataSource.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getConnection")
                                            && armed.compareAndSet(true, false)) {
                                        entered.countDown();
                                        if (!release.await(3, TimeUnit.SECONDS))
                                            throw new AssertionError("Missing database release");
                                    }
                                    try {
                                        return method.invoke(original, args);
                                    } catch (java.lang.reflect.InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                });
        var store = new JdbcLabelStore(gated, 2);
        store.initializeSchema();
        var input = new HashMap<>(segments("before"));
        var pool = Executors.newSingleThreadExecutor();
        armed.set(true);
        try {
            var future =
                    pool.submit(
                            () ->
                                    store.publishDocument(
                                            RESOURCE,
                                            "immutable",
                                            0,
                                            input,
                                            DataLabel.PUBLIC,
                                            origin("publish")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            input.put("chunk", DataFragment.document("after").digest());
            release.countDown();
            var published = future.get(3, TimeUnit.SECONDS);
            assertEquals(segments("before"), published.segments());
            assertEquals(
                    published,
                    store().publishDocument(
                                    RESOURCE,
                                    "immutable",
                                    0,
                                    segments("before"),
                                    DataLabel.PUBLIC,
                                    origin("publish")));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void maximumManifestRoundTripsAndOverflowIsRejectedWithoutTruncation() {
        var store = store();
        store.initializeSchema();
        var segments = new TreeMap<String, String>();
        for (int i = 0; i < DocumentVersion.MAX_SEGMENTS; i++)
            segments.put("chunk-" + i, DataFragment.document("synthetic-" + i).digest());
        var published =
                store.publishDocument(
                        RESOURCE, "large", 0, segments, DataLabel.PUBLIC, origin("publish"));
        assertEquals(
                DocumentVersion.MAX_SEGMENTS,
                store().currentDocument(RESOURCE, "large").orElseThrow().segments().size());
        assertEquals(published, store().documentHistory(RESOURCE, "large", 1).orElseThrow());
        segments.put("overflow", DataFragment.document("extra").digest());
        // 调用方容器的 size/迭代可能不一致；容量约束必须针对实际快照。
        Map<String, String> changing =
                new AbstractMap<>() {
                    public int size() {
                        return 0;
                    }

                    public Set<Entry<String, String>> entrySet() {
                        return segments.entrySet();
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.publishDocument(
                                RESOURCE,
                                "large",
                                1,
                                changing,
                                DataLabel.PUBLIC,
                                origin("changing-overflow")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.publishDocument(
                                RESOURCE,
                                "large",
                                1,
                                segments,
                                DataLabel.PUBLIC,
                                origin("overflow")));
        assertEquals(1, store.currentDocument(RESOURCE, "large").orElseThrow().version());
    }

    static final DataResource RESOURCE =
            new DataResource("docs", DataResource.Kind.RETRIEVAL, "tenant", "user", "partition");
    @TempDir Path directory;

    JdbcDataSource data() {
        var data = new JdbcDataSource();
        data.setURL(
                "jdbc:h2:file:"
                        + directory.resolve("catalog")
                        + ";WRITE_DELAY=0;LOCK_TIMEOUT=3000");
        return data;
    }

    JdbcLabelStore store() {
        return new JdbcLabelStore(data(), 3);
    }

    static LabelProvenance origin(String action) {
        return new LabelProvenance(
                "ingestion", action, List.of("host-policy"), LabelProvenance.Kind.CLASSIFICATION);
    }

    static Map<String, String> segments(String text) {
        return Map.of("chunk", DataFragment.document(text).digest());
    }

    @Test
    void publicationHistoryIsDurableIdempotentAndMonotonicAcrossWithdrawal() {
        var store = store();
        store.initializeSchema();
        var first =
                store.publishDocument(
                        RESOURCE,
                        "policy",
                        0,
                        segments("old"),
                        DataLabel.CONFIDENTIAL,
                        origin("one"));
        assertEquals(
                first,
                store().publishDocument(
                                RESOURCE,
                                "policy",
                                0,
                                segments("old"),
                                DataLabel.CONFIDENTIAL,
                                origin("one")));
        var second =
                store().publishDocument(
                                RESOURCE,
                                "policy",
                                1,
                                segments("new"),
                                DataLabel.PUBLIC,
                                origin("two"));
        assertEquals(2, second.version());
        assertEquals(DataLabel.CONFIDENTIAL, second.label());
        assertEquals(first, store().documentHistory(RESOURCE, "policy", 1).orElseThrow());
        assertEquals(
                first,
                store().publishDocument(
                                RESOURCE,
                                "policy",
                                0,
                                segments("old"),
                                DataLabel.CONFIDENTIAL,
                                origin("one")));
        var withdrawn =
                store().publishDocument(
                                RESOURCE,
                                "policy",
                                2,
                                Map.of(),
                                DataLabel.PUBLIC,
                                origin("withdraw"));
        assertEquals(3, withdrawn.version());
        assertTrue(withdrawn.segments().isEmpty());
        assertEquals(DataLabel.CONFIDENTIAL, withdrawn.label());
        assertEquals(
                "document_version_stale",
                assertThrows(
                                GovernanceException.class,
                                () ->
                                        withdrawn.require(
                                                DataFragment.document(
                                                        "old",
                                                        new DocumentSegment("policy", 1, "chunk"))))
                        .code());
        assertEquals(
                "document_segment_mismatch_denied",
                assertThrows(
                                GovernanceException.class,
                                () ->
                                        withdrawn.require(
                                                DataFragment.document(
                                                        "new",
                                                        new DocumentSegment("policy", 3, "chunk"))))
                        .code());
        var other =
                new DataResource(
                        "docs", DataResource.Kind.RETRIEVAL, "other-tenant", "user", "partition");
        assertTrue(store().currentDocument(other, "policy").isEmpty());
        var partition =
                new DataResource(
                        "docs", DataResource.Kind.RETRIEVAL, "tenant", "user", "other-partition");
        assertTrue(store().currentDocument(partition, "policy").isEmpty());
    }

    @Test
    void concurrentFreshPublishersCannotOverwriteTheSameExpectedVersion() throws Exception {
        store().initializeSchema();
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            var calls = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                int n = i;
                calls.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        store().publishDocument(
                                                        RESOURCE,
                                                        "doc",
                                                        0,
                                                        segments("body-" + n),
                                                        DataLabel.PUBLIC,
                                                        origin("publish-" + n));
                                        return true;
                                    } catch (GovernanceException denied) {
                                        assertEquals(
                                                "document_publication_conflict", denied.code());
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int accepted = 0;
            for (var call : calls) if (call.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            assertEquals(1, store().currentDocument(RESOURCE, "doc").orElseThrow().version());
            assertTrue(store().documentHistory(RESOURCE, "doc", 2).isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void wrongExpectedVersionAndChangedIdempotencyActionCannotMutateHead() {
        var store = store();
        store.initializeSchema();
        var first =
                store.publishDocument(
                        RESOURCE, "doc", 0, segments("one"), DataLabel.PUBLIC, origin("one"));
        assertEquals(
                "document_publication_conflict",
                assertThrows(
                                GovernanceException.class,
                                () ->
                                        store.publishDocument(
                                                RESOURCE,
                                                "doc",
                                                0,
                                                segments("two"),
                                                DataLabel.SECRET,
                                                origin("two")))
                        .code());
        assertEquals(
                "document_publication_action_mismatch_denied",
                assertThrows(
                                GovernanceException.class,
                                () ->
                                        store.publishDocument(
                                                RESOURCE,
                                                "doc",
                                                0,
                                                segments("changed"),
                                                DataLabel.PUBLIC,
                                                origin("one")))
                        .code());
        assertEquals(first, store.currentDocument(RESOURCE, "doc").orElseThrow());
    }

    @Test
    void damagedManifestAndMissingCatalogFailClosed() throws Exception {
        var store = store();
        assertEquals(
                "document_catalog_unavailable",
                assertThrows(
                                GovernanceException.class,
                                () -> store.currentDocument(RESOURCE, "doc"))
                        .code());
        store.initializeSchema();
        store.publishDocument(RESOURCE, "doc", 0, segments("one"), DataLabel.PUBLIC, origin("one"));
        try (var connection = data().getConnection();
                var update =
                        connection.prepareStatement("UPDATE agt_document_version SET segments=?")) {
            update.setString(1, JsonValues.write(segments("tampered")));
            assertEquals(1, update.executeUpdate());
        }
        assertEquals(
                "document_catalog_integrity_failed",
                assertThrows(
                                GovernanceException.class,
                                () -> store().currentDocument(RESOURCE, "doc"))
                        .code());
        assertEquals(
                "document_catalog_integrity_failed",
                assertThrows(
                                GovernanceException.class,
                                () ->
                                        store().publishDocument(
                                                        RESOURCE,
                                                        "doc",
                                                        1,
                                                        segments("new"),
                                                        DataLabel.PUBLIC,
                                                        origin("two")))
                        .code());
    }
}
