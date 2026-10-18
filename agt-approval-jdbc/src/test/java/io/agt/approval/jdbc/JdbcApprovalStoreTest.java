package io.agt.approval.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.audit.jdbc.*;
import io.agt.core.*;
import io.agt.core.approval.*;
import io.agt.identity.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class JdbcApprovalStoreTest {
    @Test
    void fullReviewThenSingleConsumptionPersistsAndErasesBody() throws Exception {
        try (var f = new ApprovalFixture()) {
            var requester = f.requester();
            var reviewer = f.reviewer();
            var request = f.request(requester);
            var claim = f.store.submit(requester, request);
            assertTrue(f.store.consume(requester, claim).isEmpty());
            var view = f.store.review(reviewer, request.requestId());
            assertEquals(request, view.request());
            assertTrue(decrypted(f, request.requestId()).contains("private-review-body"));
            assertEquals(Map.of("value", "private-review-body"), view.request().action().target());
            assertFalse(databaseBytes(f, request.requestId()).contains("private-review-body"));
            f.store.decide(reviewer, view, true);
            var reopened = f.store(f.witness);
            assertTrue(reopened.consume(requester, claim).orElseThrow().approved());
            assertThrows(GovernanceException.class, () -> f.store.consume(requester, claim));
            assertThrows(
                    GovernanceException.class,
                    () -> reopened.review(reviewer, request.requestId()));
            assertFalse(decrypted(f, request.requestId()).contains("private-review-body"));
            var receipt = reopened.receipt(requester, request.requestId());
            assertEquals(JdbcApprovalStore.Status.CONSUMED, receipt.status());
            assertEquals(
                    List.of("SUBMIT", "VIEW", "APPROVE", "CONSUME", "RECEIPT"),
                    receipt.events().stream().map(JdbcApprovalStore.Event::operation).toList());
            assertFalse(claim.toString().contains(claim.secret()));
            assertFalse(view.toString().contains(view.reviewSecret()));
        }
    }

    @Test
    void selfReviewCrossTenantServicesAndWrongRolesAreDenied() {
        try (var f = new ApprovalFixture()) {
            var requester = f.actor("requester", "tenant-a", "human", "requester", "reviewer");
            var request = f.request(requester);
            f.store.submit(requester, request);
            for (var actor :
                    List.of(
                            requester,
                            f.actor("other", "tenant-b", "human", "reviewer"),
                            f.actor("other", "tenant-a", "service", "reviewer"),
                            f.actor("other", "tenant-a", "human", "requester")))
                assertThrows(
                        GovernanceException.class,
                        () -> f.store.review(actor, request.requestId()));
            var reviewer = f.reviewer();
            var view = f.store.review(reviewer, request.requestId());
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.store.decide(
                                    f.actor("other-reviewer", "tenant-a", "human", "reviewer"),
                                    view,
                                    true));
            var refreshed = f.actor("reviewer", "tenant-a", "human", "reviewer");
            assertThrows(GovernanceException.class, () -> f.store.decide(refreshed, view, true));
        }
    }

    @Test
    void changedRequestDigestIdentityAndUnreviewedParameterProofCannotPass() {
        try (var f = new ApprovalFixture()) {
            var requester = f.requester();
            var request = f.request(requester);
            var altered = request.action().withTarget(Map.of("value", "other"));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.store.submit(
                                    requester,
                                    new ApprovalRequest(
                                            request.requestId(),
                                            request.actionIdentity(),
                                            request.policyVersion(),
                                            altered,
                                            request.expiresAtEpochMillis())));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.store.submit(
                                    f.actor("other", "tenant-a", "human", "requester"), request));
            var claim = f.store.submit(requester, request);
            assertThrows(GovernanceException.class, () -> f.store.submit(requester, request));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.store.consume(
                                    requester,
                                    new ApprovalClaim(
                                            claim.requestId(),
                                            claim.actionIdentity(),
                                            "A".repeat(43))));
            var reviewer = f.reviewer();
            var view = f.store.review(reviewer, request.requestId());
            var fakeRequest =
                    new ApprovalRequest(
                            request.requestId(),
                            request.actionIdentity(),
                            request.policyVersion(),
                            altered,
                            request.expiresAtEpochMillis());
            assertThrows(
                    GovernanceException.class,
                    () ->
                            f.store.decide(
                                    reviewer,
                                    new ApprovalReview(
                                            fakeRequest, view.revision(), view.reviewSecret()),
                                    true));
            var newer = f.store.review(reviewer, request.requestId());
            assertThrows(GovernanceException.class, () -> f.store.decide(reviewer, view, true));
            f.store.decide(reviewer, newer, false);
            assertFalse(f.store.consume(requester, claim).orElseThrow().approved());
            assertThrows(GovernanceException.class, () -> f.store.decide(reviewer, newer, true));
        }
    }

    @Test
    void expiryCancellationAndRevocationPreventConsumingApproval() {
        try (var f = new ApprovalFixture()) {
            var requester = f.requester();
            var reviewer = f.reviewer();
            var request = f.request(requester);
            var claim = f.store.submit(requester, request);
            var view = f.store.review(reviewer, request.requestId());
            f.store.cancel(claim);
            assertThrows(GovernanceException.class, () -> f.store.decide(reviewer, view, true));
            assertThrows(GovernanceException.class, () -> f.store.consume(requester, claim));
            var next = f.request(requester);
            var nextClaim = f.store.submit(requester, next);
            f.store.decide(reviewer, f.store.review(reviewer, next.requestId()), true);
            f.clock.value.addAndGet(6000);
            assertEquals(
                    "approval_expired",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> f.store.consume(requester, nextClaim))
                            .code());
            f.clock.value.addAndGet(-6000);
            assertThrows(GovernanceException.class, () -> f.store.consume(requester, nextClaim));
            f.clock.value.addAndGet(6000);
            var third = f.request(requester);
            var thirdClaim = f.store.submit(requester, third);
            f.store.decide(reviewer, f.store.review(reviewer, third.requestId()), true);
            f.revoked.add(requester.tokenIdentity());
            assertThrows(GovernanceException.class, () -> f.store.consume(requester, thirdClaim));
            f.store.cancel(thirdClaim);
        }
    }

    @Test
    void competingStoreInstancesCannotConsumeTwice() throws Exception {
        try (var f = new ApprovalFixture()) {
            var requester = f.requester();
            var reviewer = f.reviewer();
            var request = f.request(requester);
            var claim = f.store.submit(requester, request);
            f.store.decide(reviewer, f.store.review(reviewer, request.requestId()), true);
            var other = f.store(f.witness);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            var approved = new AtomicInteger();
            try {
                var jobs = new ArrayList<Future<?>>();
                for (var store : List.of(f.store, other))
                    jobs.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            start.await();
                                            if (store.consume(requester, claim)
                                                    .orElseThrow()
                                                    .approved()) approved.incrementAndGet();
                                        } catch (GovernanceException expected) {
                                        } catch (Exception failure) {
                                            throw new AssertionError(failure);
                                        }
                                    }));
                start.countDown();
                for (var job : jobs) job.get(5, TimeUnit.SECONDS);
                assertEquals(1, approved.get());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void stalePrimaryRollbackAndDeletedWitnessCannotReviveApprovedRows() throws Exception {
        try (var f = new ApprovalFixture()) {
            var requester = f.requester();
            var reviewer = f.reviewer();
            var request = f.request(requester);
            var claim = f.store.submit(requester, request);
            f.store.decide(reviewer, f.store.review(reviewer, request.requestId()), true);
            Object[] backup;
            try (var c = f.primary.getConnection();
                    var q =
                            c.prepareStatement(
                                    "SELECT revision,key_id,previous_hash,payload FROM agt_approval_request WHERE request_id=?")) {
                q.setString(1, request.requestId());
                try (var rows = q.executeQuery()) {
                    rows.next();
                    backup =
                            new Object[] {
                                rows.getLong(1),
                                rows.getString(2),
                                rows.getString(3),
                                rows.getBytes(4)
                            };
                }
            }
            assertTrue(f.store.consume(requester, claim).orElseThrow().approved());
            try (var c = f.primary.getConnection();
                    var q =
                            c.prepareStatement(
                                    "UPDATE agt_approval_request SET revision=?,key_id=?,previous_hash=?,payload=? WHERE request_id=?")) {
                for (int i = 0; i < 4; i++) q.setObject(i + 1, backup[i]);
                q.setString(5, request.requestId());
                q.executeUpdate();
            }
            assertEquals(
                    "approval_integrity_failed",
                    assertThrows(GovernanceException.class, () -> f.store.consume(requester, claim))
                            .code());
            try (var c = f.witnessData.getConnection();
                    var q = c.createStatement()) {
                q.executeUpdate("DELETE FROM agt_audit_witness");
            }
            assertThrows(GovernanceException.class, () -> f.store.consume(requester, claim));
        }
    }

    @Test
    void commitWithoutWitnessIsRecoveredButLostConsumptionNeverReturnsAllowAgain() {
        try (var f = new ApprovalFixture()) {
            var fail = new AtomicBoolean(false);
            var unreliable =
                    new AuditWitness() {
                        public AuditCheckpoint read(String c, String s) {
                            return f.witness.read(c, s);
                        }

                        public boolean advance(AuditCheckpoint old, AuditCheckpoint next) {
                            if (fail.get())
                                throw new GovernanceException("synthetic_witness_unavailable");
                            return f.witness.advance(old, next);
                        }
                    };
            var store = f.store(unreliable);
            var requester = f.requester();
            var reviewer = f.reviewer();
            var request = f.request(requester);
            var claim = store.submit(requester, request);
            var view = store.review(reviewer, request.requestId());
            fail.set(true);
            assertThrows(GovernanceException.class, () -> store.decide(reviewer, view, true));
            fail.set(false);
            assertTrue(store.consume(requester, claim).orElseThrow().approved());
            var second = f.request(requester);
            var next = store.submit(requester, second);
            store.decide(reviewer, store.review(reviewer, second.requestId()), true);
            fail.set(true);
            assertThrows(GovernanceException.class, () -> store.consume(requester, next));
            fail.set(false);
            assertThrows(GovernanceException.class, () -> store.consume(requester, next));
            assertEquals(
                    JdbcApprovalStore.Status.CONSUMED,
                    store.receipt(requester, second.requestId()).status());
        }
    }

    @Test
    void corruptedCiphertextAndConfigurationSubstitutionFailClosed() throws Exception {
        try (var f = new ApprovalFixture()) {
            var requester = f.requester();
            var request = f.request(requester);
            f.store.submit(requester, request);
            try (var c = f.primary.getConnection();
                    var q =
                            c.prepareStatement(
                                    "UPDATE agt_approval_request SET payload=? WHERE request_id=?")) {
                q.setBytes(1, new byte[64]);
                q.setString(2, request.requestId());
                q.executeUpdate();
            }
            assertThrows(
                    GovernanceException.class,
                    () -> f.store.review(f.reviewer(), request.requestId()));
            var changed =
                    new JdbcApprovalStore(
                            f.primary,
                            "approval-demo",
                            "different-catalog",
                            f.policy,
                            f.keys,
                            f.witness,
                            f.clock,
                            2);
            assertThrows(GovernanceException.class, () -> changed.initializeSchema());
        }
    }

    @Test
    void delayedWriterCanConfirmItsAncestorAfterAnotherInstanceAdvances() throws Exception {
        try (var f = new ApprovalFixture()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var once = new AtomicBoolean();
            var delayed =
                    new AuditWitness() {
                        public AuditCheckpoint read(String catalog, String stream) {
                            return f.witness.read(catalog, stream);
                        }

                        public boolean advance(AuditCheckpoint old, AuditCheckpoint next) {
                            if (next.sequence() == 0 && once.compareAndSet(false, true)) {
                                entered.countDown();
                                try {
                                    if (!release.await(3, TimeUnit.SECONDS))
                                        throw new AssertionError(
                                                "Test publication was not released");
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(interrupted);
                                }
                            }
                            return f.witness.advance(old, next);
                        }
                    };
            var requester = f.requester();
            var reviewer = f.reviewer();
            var request = f.request(requester);
            var worker = Executors.newSingleThreadExecutor();
            try {
                var submitted = worker.submit(() -> f.store(delayed).submit(requester, request));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var reviewed = f.store.review(reviewer, request.requestId());
                release.countDown();
                var claim = submitted.get(3, TimeUnit.SECONDS);
                f.store.decide(reviewer, reviewed, true);
                assertTrue(f.store.consume(requester, claim).orElseThrow().approved());
            } finally {
                release.countDown();
                worker.shutdownNow();
            }
        }
    }

    @Test
    void metadataReadDoesNotInvalidateTheReviewedParameters() {
        try (var f = new ApprovalFixture()) {
            var requester = f.requester();
            var reviewer = f.reviewer();
            var request = f.request(requester);
            var claim = f.store.submit(requester, request);
            var view = f.store.review(reviewer, request.requestId());
            f.store.receipt(requester, request.requestId());
            f.store.decide(reviewer, view, true);
            assertTrue(f.store.consume(requester, claim).orElseThrow().approved());
        }
    }

    @Test
    void trustedExpirySweepErasesUnvisitedBodiesAndPreservesTombstones() throws Exception {
        try (var f = new ApprovalFixture()) {
            var requester = f.requester();
            var first = f.request(requester);
            var second = f.request(requester);
            f.store.submit(requester, first);
            f.store.submit(requester, second);
            assertEquals(0, f.store.expirePending());
            f.clock.value.addAndGet(6000);
            assertEquals(2, f.store.expirePending());
            assertEquals(0, f.store.expirePending());
            assertFalse(decrypted(f, first.requestId()).contains("private-review-body"));
            assertEquals(
                    JdbcApprovalStore.Status.EXPIRED,
                    f.store.receipt(requester, first.requestId()).status());
            assertThrows(GovernanceException.class, () -> f.store.submit(requester, first));
        }
    }

    @Test
    void requestBodyAndRetainedRequestCapacityHaveHardBounds() {
        try (var f = new ApprovalFixture()) {
            var policy =
                    new ApprovalPolicy(
                            f.policy.identityPolicyId(),
                            f.runtime.document(),
                            f.policy.requesterPermissions(),
                            f.policy.reviewerPermissions(),
                            1,
                            1024,
                            10000,
                            4,
                            2,
                            50);
            var tiny =
                    new JdbcApprovalStore(
                            f.primary,
                            "tiny-store",
                            "tiny-catalog",
                            policy,
                            f.keys,
                            f.witness,
                            f.clock,
                            2);
            tiny.initializeSchema();
            var requester = f.requester();
            var request = f.request(requester);
            var claim = tiny.submit(requester, request);
            tiny.cancel(claim);
            assertEquals(
                    "approval_store_capacity",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> tiny.submit(requester, f.request(requester)))
                            .code());
            var oversized = f.request(requester, Map.of("value", "x".repeat(2000)));
            assertEquals(
                    "approval_request_capacity",
                    assertThrows(GovernanceException.class, () -> tiny.submit(requester, oversized))
                            .code());
        }
    }

    @Test
    void businessIntentAndPolicyEffectArePartOfTheReviewedAction() {
        try (var f = new ApprovalFixture()) {
            var effect =
                    new io.agt.core.effects.EffectRule(
                            "operations",
                            "receipt-v1",
                            io.agt.core.effects.EffectLedger.Phase.FORWARD,
                            "reviewed-ledger");
            var runtime =
                    io.agt.core.policy.StructuredPolicy.builder("approval-v1")
                            .budget(f.budget)
                            .tool(
                                    "read",
                                    new io.agt.core.policy.ToolRule(
                                            Set.of("read"),
                                            f.runtime.document().tools().get("read").parameters(),
                                            true,
                                            effect))
                            .build();
            var policy =
                    new ApprovalPolicy(
                            f.identity.identity(),
                            runtime.document(),
                            f.policy.requesterPermissions(),
                            f.policy.reviewerPermissions(),
                            100,
                            65536,
                            10000,
                            4,
                            2,
                            50);
            var store =
                    new JdbcApprovalStore(
                            f.primary,
                            "effects-store",
                            "effects-catalog",
                            policy,
                            f.keys,
                            f.witness,
                            f.clock,
                            2);
            store.initializeSchema();
            var requester = f.requester();
            var reviewer = f.reviewer();
            var plain = f.request(requester);
            assertEquals(
                    "approval_effect_binding_denied",
                    assertThrows(GovernanceException.class, () -> store.submit(requester, plain))
                            .code());
            var target =
                    new io.agt.core.effects.EffectTarget(
                            effect,
                            new io.agt.core.effects.EffectIntent("intent-a", "revision-7", null));
            var action =
                    new PolicyRequest(
                            plain.action().point(),
                            plain.action().context(),
                            plain.action().sessionId(),
                            plain.action().actionId(),
                            "read",
                            plain.action().target(),
                            target);
            var request =
                    new ApprovalRequest(
                            plain.requestId(),
                            ActionIdentity.of("approval-v1", action),
                            "approval-v1",
                            action,
                            plain.expiresAtEpochMillis());
            var claim = store.submit(requester, request);
            var view = store.review(reviewer, request.requestId());
            assertEquals(target, view.request().action().effect());
            var changed =
                    new PolicyRequest(
                            action.point(),
                            action.context(),
                            action.sessionId(),
                            action.actionId(),
                            "read",
                            action.target(),
                            new io.agt.core.effects.EffectTarget(
                                    effect,
                                    new io.agt.core.effects.EffectIntent(
                                            "intent-a", "revision-8", null)));
            var altered =
                    new ApprovalRequest(
                            request.requestId(),
                            request.actionIdentity(),
                            request.policyVersion(),
                            changed,
                            request.expiresAtEpochMillis());
            assertThrows(
                    GovernanceException.class,
                    () ->
                            store.decide(
                                    reviewer,
                                    new ApprovalReview(
                                            altered, view.revision(), view.reviewSecret()),
                                    true));
            store.decide(reviewer, view, true);
            assertTrue(store.consume(requester, claim).orElseThrow().approved());
        }
    }

    @Test
    void revocationWhileConfirmingViewPreventsDeliveryOfItsBody() {
        try (var f = new ApprovalFixture()) {
            var requester = f.requester();
            var reviewer = f.reviewer();
            var request = f.request(requester);
            var claim = f.store.submit(requester, request);
            var witness =
                    new AuditWitness() {
                        public AuditCheckpoint read(String c, String s) {
                            return f.witness.read(c, s);
                        }

                        public boolean advance(AuditCheckpoint old, AuditCheckpoint next) {
                            boolean written = f.witness.advance(old, next);
                            if (next.sequence() == 1) f.revoked.add(reviewer.tokenIdentity());
                            return written;
                        }
                    };
            assertThrows(
                    GovernanceException.class,
                    () -> f.store(witness).review(reviewer, request.requestId()));
            assertTrue(f.store.consume(requester, claim).isEmpty());
            f.store.cancel(claim);
        }
    }

    @Test
    void revocationWhileConfirmingConsumptionLosesGrantInsteadOfDeliveringIt() {
        try (var f = new ApprovalFixture()) {
            var requester = f.requester();
            var reviewer = f.reviewer();
            var request = f.request(requester);
            var claim = f.store.submit(requester, request);
            f.store.decide(reviewer, f.store.review(reviewer, request.requestId()), true);
            var witness =
                    new AuditWitness() {
                        public AuditCheckpoint read(String c, String s) {
                            return f.witness.read(c, s);
                        }

                        public boolean advance(AuditCheckpoint old, AuditCheckpoint next) {
                            boolean written = f.witness.advance(old, next);
                            if (next.sequence() == 3) f.revoked.add(requester.tokenIdentity());
                            return written;
                        }
                    };
            assertThrows(
                    GovernanceException.class, () -> f.store(witness).consume(requester, claim));
            assertEquals(
                    JdbcApprovalStore.Status.CONSUMED,
                    f.store.receipt(reviewer, request.requestId()).status());
            f.store.cancel(claim);
        }
    }

    private String decrypted(ApprovalFixture f, String id) throws Exception {
        try (var c = f.primary.getConnection();
                var query =
                        c.prepareStatement(
                                "SELECT revision,key_id,previous_hash,payload FROM agt_approval_request WHERE request_id=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                rows.next();
                var aad =
                        List.of(
                                "AGT-APPROVAL-ROW-V1",
                                "approval-demo",
                                "reviewed-catalog",
                                f.policy.identity(),
                                id,
                                rows.getLong(1),
                                rows.getString(2),
                                rows.getString(3));
                return new String(
                        io.agt.core.storage.StorageCrypto.decrypt(
                                f.keys.find(rows.getString(2)).encryption(), aad, rows.getBytes(4)),
                        StandardCharsets.UTF_8);
            }
        }
    }

    private String databaseBytes(ApprovalFixture f, String id) throws Exception {
        try (var c = f.primary.getConnection();
                var q =
                        c.prepareStatement(
                                "SELECT payload FROM agt_approval_request WHERE request_id=?")) {
            q.setString(1, id);
            try (var rows = q.executeQuery()) {
                rows.next();
                return new String(rows.getBytes(1), StandardCharsets.ISO_8859_1);
            }
        }
    }
}
