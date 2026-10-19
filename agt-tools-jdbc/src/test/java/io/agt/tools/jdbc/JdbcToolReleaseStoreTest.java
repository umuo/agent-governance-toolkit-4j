package io.agt.tools.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.audit.jdbc.*;
import io.agt.core.*;
import io.agt.core.audit.*;
import io.agt.core.policy.*;
import io.agt.core.tools.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 持久发布目录的实际身份、撤销、故障恢复和运行时边界验收。 */
class JdbcToolReleaseStoreTest {
    static final TrustedContext RUNTIME =
            new TrustedContext("agent", "tenant", "user", Set.of("read"));

    static AgentControl control(ToolLease lease) {
        return AgentControl.builder(
                        StructuredPolicy.builder("released-tool-v1")
                                .tool(
                                        "lookup",
                                        new ToolRule(
                                                Set.of("read"),
                                                ParameterRule.object(Map.of()),
                                                false,
                                                null,
                                                lease.identity()))
                                .build())
                .reviewedTools(List.of(lease))
                .audit(r -> {})
                .build();
    }

    static ToolLease register(ReviewedToolCatalog catalog, ToolDescriptor descriptor) {
        return catalog.register(
                descriptor.name(), ReleaseFixture.ARTIFACT, descriptor.specification());
    }

    @Test
    void approvalNeedsIndependentHumanAndLiveIdentity() {
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor("v1");
            var author = f.actor("same-person", "tenant", "human", "publisher", "reviewer");
            f.store.submit(author, descriptor);
            assertEquals(
                    "tool_release_self_review",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> f.store.review(author, descriptor.identity()))
                            .code());
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.store.review(
                                    f.actor("robot", "tenant", "service", "reviewer"),
                                    descriptor.identity()));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.store.review(
                                    f.actor("foreign", "other-tenant", "human", "reviewer"),
                                    descriptor.identity()));
            var actor = f.reviewer();
            var view = f.store.review(actor, descriptor.identity());
            assertEquals(descriptor, view.descriptor());
            assertEquals("ToolReleaseReview[redacted]", view.toString());
            assertThrows(GovernanceException.class, () -> f.store.decide(f.reviewer(), view, true));
            f.revoked.add(actor.tokenIdentity());
            assertThrows(GovernanceException.class, () -> f.store.decide(actor, view, true));
            assertThrows(GovernanceException.class, () -> f.store.check(descriptor));
            var replacement = f.actor("another-reviewer", "tenant", "human", "reviewer");
            f.store.decide(replacement, f.store.review(replacement, descriptor.identity()), true);
            f.store.check(descriptor);
        }
    }

    @Test
    void alreadyIssuedLeasesObserveRevocationAcrossReopenedCatalogs() throws Exception {
        try (var f = new ReleaseFixture();
                var peer = f.store(f.witness)) {
            var descriptor = f.descriptor("v1");
            f.approve(descriptor);
            try (var a = new ReviewedToolCatalog(List.of(descriptor), 1024, f.store);
                    var b = new ReviewedToolCatalog(List.of(descriptor), 1024, peer)) {
                var first = register(a, descriptor);
                var second = register(b, descriptor);
                long before = f.revision();
                for (int i = 0; i < 10; i++) {
                    first.check();
                    second.check();
                }
                assertEquals(
                        before, f.revision(), "Runtime reads cannot exhaust publication history");
                assertThrows(
                        GovernanceException.class,
                        () ->
                                second.validate(
                                        new TrustedContext(
                                                "other", "foreign", "user", Set.of("read"))));
                f.store.revoke(f.revoker(), descriptor.identity());
                assertEquals(
                        "tool_release_revoked",
                        assertThrows(GovernanceException.class, first::check).code());
                assertEquals(
                        "tool_release_revoked",
                        assertThrows(GovernanceException.class, second::check).code());
                try (var reopened = f.store(f.witness)) {
                    assertThrows(GovernanceException.class, () -> reopened.check(descriptor));
                }
                assertThrows(
                        GovernanceException.class, () -> f.store.submit(f.publisher(), descriptor));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revocationAfterBusinessReturnSuppressesSyncAndAsyncDelivery(boolean async)
            throws Exception {
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor("v1");
            f.approve(descriptor);
            var calls = new AtomicInteger();
            try (var catalog = new ReviewedToolCatalog(List.of(descriptor), 1024, f.store)) {
                var lease = register(catalog, descriptor);
                try (var control = control(lease);
                        var session = control.openSession(RUNTIME)) {
                    if (async) {
                        var future =
                                session.reviewedToolOperationAsync(
                                        lease,
                                        Map.of(),
                                        v -> v,
                                        v -> {
                                            calls.incrementAndGet();
                                            f.store.revoke(f.revoker(), descriptor.identity());
                                            return CompletableFuture.completedFuture(
                                                    "private-result");
                                        },
                                        v -> v,
                                        (o, v) -> (String) v);
                        assertInstanceOf(
                                GovernanceException.class,
                                assertThrows(
                                                ExecutionException.class,
                                                () -> future.get(10, TimeUnit.SECONDS))
                                        .getCause());
                    } else {
                        assertThrows(
                                GovernanceException.class,
                                () ->
                                        session.reviewedToolOperation(
                                                lease,
                                                Map.of(),
                                                v -> v,
                                                v -> {
                                                    calls.incrementAndGet();
                                                    f.store.revoke(
                                                            f.revoker(), descriptor.identity());
                                                    return "private-result";
                                                },
                                                v -> v,
                                                (o, v) -> (String) v));
                    }
                }
            }
            assertEquals(1, calls.get());
        }
    }

    @Test
    void authorityCannotBeRemovedAndSameVersionCannotChangeMeaning() {
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor("v1");
            assertEquals(
                    "tool_review_authority_required",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> new ReviewedToolCatalog(List.of(descriptor), 1024))
                            .code());
            var legacy =
                    new ToolDescriptor(
                            descriptor.name(),
                            descriptor.provider(),
                            descriptor.version(),
                            descriptor.artifactDigest(),
                            descriptor.specification(),
                            descriptor.permissions());
            assertNotEquals(legacy.identity(), descriptor.identity());
            try (var local = new ReviewedToolCatalog(List.of(legacy), 1024)) {
                var unbound = register(local, legacy);
                var required =
                        StructuredPolicy.builder("no-downgrade")
                                .tool(
                                        "lookup",
                                        new ToolRule(
                                                Set.of("read"),
                                                ParameterRule.object(Map.of()),
                                                false,
                                                null,
                                                descriptor.identity()))
                                .build();
                assertThrows(
                        GovernanceException.class,
                        () ->
                                AgentControl.builder(required)
                                        .reviewedTools(List.of(unbound))
                                        .audit(r -> {})
                                        .build());
            }

            f.approve(descriptor);
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.store.submit(
                                    f.publisher(),
                                    f.descriptor(f.store, "v1", "changed description")));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.store.submit(
                                    f.actor("other-publisher", "tenant", "service", "publisher"),
                                    descriptor));
            assertThrows(GovernanceException.class, () -> f.store.submit(f.publisher(), legacy));
        }
    }

    @Test
    void staleReviewAndRejectedVersionCannotBeApprovedAgain() {
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor("v1");
            f.store.submit(f.publisher(), descriptor);
            var actor = f.reviewer();
            var view = f.store.review(actor, descriptor.identity());
            f.clock.time.set(view.expires + 1);
            assertThrows(GovernanceException.class, () -> f.store.decide(actor, view, true));
            var next = f.reviewer();
            var fresh = f.store.review(next, descriptor.identity());
            f.store.decide(next, fresh, false);
            f.store.decide(next, fresh, false);
            assertThrows(GovernanceException.class, () -> f.store.decide(next, fresh, true));
            assertThrows(
                    GovernanceException.class, () -> f.store.submit(f.publisher(), descriptor));
            assertThrows(GovernanceException.class, () -> f.store.check(descriptor));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unacknowledgedApprovalIsNotReturnedAndRecoveryOnlyReconcilesMetadata(boolean receiptLost) {
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor("v1");
            f.store.submit(f.publisher(), descriptor);
            var actor = f.reviewer();
            var view = f.store.review(actor, descriptor.identity());
            AuditWitness fault =
                    new AuditWitness() {
                        public AuditCheckpoint read(String catalog, String stream) {
                            return f.witness.read(catalog, stream);
                        }

                        public boolean advance(AuditCheckpoint expected, AuditCheckpoint next) {
                            if (next.sequence() == 3) {
                                if (receiptLost) {
                                    f.witness.advance(expected, next);
                                }
                                throw new GovernanceException("synthetic_witness_failure");
                            }
                            return f.witness.advance(expected, next);
                        }
                    };
            try (var failed = f.store(fault)) {
                assertThrows(GovernanceException.class, () -> failed.decide(actor, view, true));
                if (!receiptLost) {
                    assertThrows(GovernanceException.class, () -> failed.check(descriptor));
                }
            }
            try (var recovered = f.store(f.witness)) {
                recovered.check(descriptor);
                recovered.decide(actor, view, true);
            }
        }
    }

    @Test
    void validEncryptedOldSnapshotAndDeletedRowsCannotRestoreRevokedApproval() throws Exception {
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor("v1");
            f.approve(descriptor);
            var old = snapshot(f);
            f.store.revoke(f.revoker(), descriptor.identity());
            restore(f, old);
            assertEquals(
                    "tool_release_integrity_denied",
                    assertThrows(GovernanceException.class, () -> f.store.check(descriptor))
                            .code());
            try (var c = f.primary.getConnection();
                    var q = c.createStatement()) {
                q.executeUpdate("DELETE FROM agt_tool_releases");
            }
            assertThrows(
                    GovernanceException.class, () -> f.store.submit(f.publisher(), descriptor));
        }
    }

    @Test
    void missingWitnessOrTamperedCiphertextNeverDefaultsToApproved() throws Exception {
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor("v1");
            f.approve(descriptor);
            try (var c = f.anchors.getConnection();
                    var q = c.createStatement()) {
                q.executeUpdate("DELETE FROM agt_audit_witness");
            }
            assertThrows(GovernanceException.class, () -> f.store.check(descriptor));
        }
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor("v1");
            f.approve(descriptor);
            var copy = snapshot(f);
            ((byte[]) copy[4])[0] ^= 1;
            restore(f, copy);
            assertThrows(GovernanceException.class, () -> f.store.check(descriptor));
        }
    }

    @Test
    void parallelReviewersCannotOverwriteFirstDecision() throws Exception {
        try (var f = new ReleaseFixture();
                var peer = f.store(f.witness)) {
            var descriptor = f.descriptor("v1");
            f.store.submit(f.publisher(), descriptor);
            var a = f.reviewer();
            var b = f.actor("second-reviewer", "tenant", "human", "reviewer");
            var va = f.store.review(a, descriptor.identity());
            var vb = peer.review(b, descriptor.identity());
            var executor = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var approve =
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        f.store.decide(a, va, true);
                                        return true;
                                    } catch (GovernanceException denied) {
                                        return false;
                                    }
                                });
                var reject =
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        peer.decide(b, vb, false);
                                        return true;
                                    } catch (GovernanceException denied) {
                                        return false;
                                    }
                                });
                start.countDown();
                boolean approved = approve.get(10, TimeUnit.SECONDS),
                        rejected = reject.get(10, TimeUnit.SECONDS);
                assertNotEquals(approved, rejected);
                if (approved) {
                    peer.check(descriptor);
                } else {
                    assertThrows(GovernanceException.class, () -> peer.check(descriptor));
                }
                assertEquals(4, f.revision());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void authorizedManagementLookupReturnsImmutableHistoryOnlyAfterAccessAudit() throws Exception {
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor(f.store, "v1", "synthetic-private-tool-description");
            f.approve(descriptor);
            var events = new ArrayList<AuditEvent>();
            AuditSink audit =
                    new AuditSink() {
                        public void write(AuditRecord record) {
                            fail("Actual actor scope must reach structured sink");
                        }

                        public void write(AuditEvent event) {
                            events.add(event);
                        }
                    };
            var human = f.reviewer();
            long revision = f.revision();
            assertEquals(descriptor.identity(), f.store.list(human, audit).get(0).binding());
            var info = f.store.inspect(human, descriptor.identity(), audit);
            assertEquals(descriptor, info.descriptor());
            assertEquals("APPROVED", info.status());
            assertEquals(
                    List.of("SUBMIT", "REVIEW", "APPROVE"),
                    info.events().stream().map(ToolReleaseInfo.Event::kind).toList());
            assertEquals(2, events.size());
            assertEquals(revision, f.revision());
            for (var event : events) {
                assertEquals(human.principalId(), event.scope().principalId());
                assertEquals("tenant", event.scope().tenantId());
                assertNull(event.body().content());
                assertFalse(
                        event.record().toString().contains("synthetic-private-tool-description"));
            }
            assertThrows(GovernanceException.class, () -> f.store.list(f.publisher(), audit));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.store.inspect(
                                    f.actor("foreign", "foreign", "human", "reviewer"),
                                    descriptor.identity(),
                                    audit));
            assertEquals(
                    "tool_release_access_audit_failed",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            f.store.inspect(
                                                    human,
                                                    descriptor.identity(),
                                                    r -> {
                                                        throw new java.io.IOException(
                                                                "synthetic failure");
                                                    }))
                            .code());
            f.store.check(descriptor);
        }
    }

    @Test
    void aggregateMetadataAndNestedDescriptorsDoNotConsumeRevocationReserve() {
        try (var f = new ReleaseFixture()) {
            var descriptors = new ArrayList<ToolDescriptor>();
            for (int i = 0; i < 3; i++) {
                var descriptor =
                        new ToolDescriptor(
                                "lookup",
                                "host-provider",
                                "v" + i,
                                "sha256:"
                                        + io.agt.core.storage.StorageCrypto.hash(
                                                ReleaseFixture.ARTIFACT),
                                Map.of(
                                        "name",
                                        "lookup",
                                        "metadata",
                                        Collections.nCopies(4000, "tag")),
                                Set.of("read"),
                                f.store.identity());
                f.store.submit(f.publisher(), descriptor);
                descriptors.add(descriptor);
            }
            Object nested =
                    Map.of(
                            "precise",
                            new java.math.BigDecimal("0.12345678901234567890123456789"),
                            "large",
                            new java.math.BigInteger("1234567890123456789012345678901234567890"));
            for (int i = 0; i < 28; i++) {
                nested = Map.of("nested", nested);
            }
            var deep =
                    new ToolDescriptor(
                            "lookup",
                            "host-provider",
                            "deep-v1",
                            "sha256:"
                                    + io.agt.core.storage.StorageCrypto.hash(
                                            ReleaseFixture.ARTIFACT),
                            Map.of("name", "lookup", "metadata", nested),
                            Set.of("read"),
                            f.store.identity());
            f.store.submit(f.publisher(), deep);
            descriptors.add(deep);
            var human = f.reviewer();
            for (var descriptor : descriptors) {
                f.store.decide(human, f.store.review(human, descriptor.identity()), true);
                f.store.check(descriptor);
            }
            assertEquals(deep, f.store.inspect(human, deep.identity(), r -> {}).descriptor());
            for (var descriptor : descriptors) {
                f.store.revoke(f.revoker(), descriptor.identity());
                assertEquals(
                        "tool_release_revoked",
                        assertThrows(GovernanceException.class, () -> f.store.check(descriptor))
                                .code());
            }
        }
    }

    @Test
    void capacityReservesSpaceForIrreversibleRevocation() {
        try (var f = new ReleaseFixture(2, 2, 20000, 5000)) {
            var descriptor = f.descriptor(f.store, "v1", "x".repeat(12000));
            f.approve(descriptor);
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.store.submit(
                                    f.publisher(), f.descriptor(f.store, "v2", "y".repeat(12000))));
            f.store.revoke(f.revoker(), descriptor.identity());
            assertThrows(GovernanceException.class, () -> f.store.check(descriptor));
        }
    }

    static Object[] snapshot(ReleaseFixture f) throws Exception {
        try (var c = f.primary.getConnection();
                var q = c.createStatement();
                var r =
                        q.executeQuery(
                                "SELECT seq,key_id,previous_hash,state_hash,payload FROM agt_tool_releases")) {
            assertTrue(r.next());
            return new Object[] {
                r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getBytes(5)
            };
        }
    }

    static void restore(ReleaseFixture f, Object[] snapshot) throws Exception {
        try (var c = f.primary.getConnection();
                var q =
                        c.prepareStatement(
                                "UPDATE agt_tool_releases SET seq=?,key_id=?,previous_hash=?,state_hash=?,payload=?")) {
            for (int i = 0; i < snapshot.length; i++) {
                q.setObject(i + 1, snapshot[i]);
            }
            assertEquals(1, q.executeUpdate());
        }
    }
}
