package io.agt.core.effects;

import static io.agt.core.effects.EffectLedger.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EffectJournalTest {
    static final Key KEY = new Key("tenant", "user", "orders", "order-7");
    static final Owner OWNER = new Owner("task", "session", "action", "agent");
    static final String DIGEST = JsonValues.digest(Map.of("amount", 10));
    static final Proof PROOF = new Proof("receipt-1", JsonValues.digest("paid"));

    static EffectJournal fresh() {
        var journal = new EffectJournal(KEY);
        journal.observe(1000);
        return journal;
    }

    static Lease claim(EffectJournal journal) {
        return journal.claim(Phase.FORWARD, DIGEST, null, OWNER, 100).lease();
    }

    @Test
    void expiredPreparationCanRetryButOldCapabilityCannotDispatchOrSettle() {
        var journal = fresh();
        var old = claim(journal);
        journal.observe(1100);
        assertEquals(Status.NOT_APPLIED, journal.view().forward().status());
        var next = claim(journal);
        assertEquals(2, next.attempt());
        assertNotEquals(old.secret(), next.secret());
        denied("effect_lease_denied", () -> journal.start(old));
        denied("effect_lease_denied", () -> journal.finish(old, Status.COMMITTED, PROOF));
        journal.start(next);
        journal.finish(next, Status.COMMITTED, PROOF);
        assertEquals(PROOF, journal.view().forward().proof());
    }

    @Test
    void dispatchedExpiryIsUnknownAcrossRestoreAndClockRollback() {
        var journal = fresh();
        var lease = claim(journal);
        journal.start(lease);
        journal.observe(1100);
        journal = EffectJournal.restored(journal.checkpoint());
        journal.observe(1);
        assertEquals(Status.UNKNOWN, journal.view().forward().status());
        var restored = journal;
        denied("effect_outcome_unknown", () -> claim(restored));
        journal.finish(lease, Status.COMMITTED, PROOF);
        assertEquals(PROOF, claimResult(journal).replay().proof());
        denied("effect_receipt_conflict", () -> restored.finish(lease, Status.UNKNOWN, null));
    }

    static Claim claimResult(EffectJournal journal) {
        return journal.claim(Phase.FORWARD, DIGEST, null, OWNER, 100);
    }

    @Test
    void semanticFingerprintStaysPinnedAfterProvenNonApplication() {
        var journal = fresh();
        var lease = claim(journal);
        journal.finish(lease, Status.NOT_APPLIED, new Proof("not-sent", JsonValues.digest(null)));
        denied(
                "effect_request_mismatch",
                () ->
                        journal.claim(
                                Phase.FORWARD, JsonValues.digest("different"), null, OWNER, 100));
        assertEquals(2, claim(journal).attempt());
    }

    @Test
    void concurrentInProgressAndForgedLeaseCannotComplete() {
        var journal = fresh();
        var lease = claim(journal);
        denied("effect_in_progress", () -> claim(journal));
        denied("effect_receipt_denied", () -> journal.finish(lease, Status.COMMITTED, PROOF));
        var fake =
                new Lease(KEY, Phase.FORWARD, lease.attempt(), "forged", DIGEST, lease.expiresAt());
        denied("effect_lease_denied", () -> journal.start(fake));
        journal.start(lease);
        journal.finish(lease, Status.COMMITTED, PROOF);
        journal.finish(lease, Status.COMMITTED, PROOF);
        assertNull(claimResult(journal).lease());
    }

    @Test
    void compensationRequiresExactForwardProofAndNeverRerunsForwardAfterCommit() {
        var journal = fresh();
        String refund = JsonValues.digest("refund-request");
        denied(
                "effect_compensation_parent_denied",
                () -> journal.claim(Phase.COMPENSATION, refund, PROOF, OWNER, 100));
        var lease = claim(journal);
        journal.start(lease);
        journal.finish(lease, Status.COMMITTED, PROOF);
        denied(
                "effect_compensation_parent_denied",
                () ->
                        journal.claim(
                                Phase.COMPENSATION,
                                refund,
                                new Proof("other", PROOF.resultDigest()),
                                OWNER,
                                100));
        var undo = journal.claim(Phase.COMPENSATION, refund, PROOF, OWNER, 100).lease();
        denied("effect_compensation_pending", () -> claim(journal));
        journal.start(undo);
        var proof = new Proof("refund-receipt", JsonValues.digest("refunded"));
        journal.finish(undo, Status.COMMITTED, proof);
        denied("effect_already_compensated", () -> claim(journal));
        assertEquals(
                proof,
                journal.claim(Phase.COMPENSATION, refund, PROOF, OWNER, 100).replay().proof());
    }

    @Test
    void reconciliationRequiresUnknownAndPinsReviewToAttemptAndProof() {
        var journal = fresh();
        var lease = claim(journal);
        journal.start(lease);
        denied(
                "effect_reconciliation_state_denied",
                () -> journal.reconcile(Phase.FORWARD, 1, Status.COMMITTED, PROOF, "review-1"));
        journal.observe(1100);
        denied(
                "effect_reconciliation_stale",
                () -> journal.reconcile(Phase.FORWARD, 2, Status.COMMITTED, PROOF, "review-1"));
        journal.reconcile(Phase.FORWARD, 1, Status.COMMITTED, PROOF, "review-1");
        var restored = EffectJournal.restored(journal.checkpoint());
        restored.reconcile(Phase.FORWARD, 1, Status.COMMITTED, PROOF, "review-1");
        denied(
                "effect_review_mismatch",
                () ->
                        restored.reconcile(
                                Phase.FORWARD,
                                1,
                                Status.NOT_APPLIED,
                                new Proof("absent", JsonValues.digest(null)),
                                "review-1"));
    }

    @Test
    void restoredStateCannotInventProofOrCompensationParent() {
        var journal = fresh();
        var lease = claim(journal);
        var checkpoint = journal.checkpoint();
        var bad =
                new EffectJournal.Slot(
                        new Entry(Status.COMMITTED, 1, DIGEST, lease.expiresAt(), OWNER, null),
                        checkpoint.forward().secretDigest());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EffectJournal.restored(
                                new EffectJournal.Checkpoint(KEY, 1000, bad, null, List.of())));
        denied(
                "effect_checkpoint_denied",
                () ->
                        EffectJournal.restored(
                                new EffectJournal.Checkpoint(
                                        KEY, 1000, null, checkpoint.forward(), List.of())));
        assertFalse(lease.toString().contains(lease.secret()));
        assertFalse(KEY.toString().contains(KEY.intentId()));
    }

    static void denied(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(GovernanceException.class, action).code());
    }
}
