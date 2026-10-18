package io.agt.core.effects;

import static io.agt.core.effects.EffectLedger.*;

import io.agt.core.*;
import java.util.*;

/** 单个意图的纯状态机；超时不重放，只有可信未应用证明才允许新 fence。 */
public final class EffectJournal {
    public record Slot(Entry entry, String secretDigest) {
        public Slot {
            Objects.requireNonNull(entry);
            requireDigest(secretDigest);
        }
    }

    public record Review(String id, Phase phase, long attempt, Status status, Proof proof) {
        public Review {
            Identifiers.require(id);
            Objects.requireNonNull(phase);
            if (attempt < 1) throw new IllegalArgumentException("Invalid review");
            terminal(status, proof);
            if (status == Status.UNKNOWN)
                throw new IllegalArgumentException("Missing review proof");
        }
    }

    public record Checkpoint(
            Key key, long lastSeen, Slot forward, Slot compensation, List<Review> reviews) {
        public Checkpoint {
            Objects.requireNonNull(key);
            if (lastSeen < 0) throw new IllegalArgumentException("Invalid effect clock");
            reviews = List.copyOf(reviews);
            if (reviews.size() > 128) throw new IllegalArgumentException("Too many effect reviews");
        }
    }

    private final Key key;
    private long now;
    private Slot forward, compensation;
    private final List<Review> reviews = new ArrayList<>();

    public EffectJournal(Key key) {
        this.key = Objects.requireNonNull(key);
    }

    public static EffectJournal restored(Checkpoint checkpoint) {
        var journal = new EffectJournal(checkpoint.key());
        journal.now = checkpoint.lastSeen();
        for (var slot : new Slot[] {checkpoint.forward(), checkpoint.compensation()})
            if (slot != null) {
                var entry = slot.entry();
                terminalShape(entry.status(), entry.proof());
            }
        if (checkpoint.compensation() != null
                && (checkpoint.forward() == null
                        || checkpoint.forward().entry().status() != Status.COMMITTED))
            throw new GovernanceException("effect_checkpoint_denied");
        if (checkpoint.reviews().stream().map(Review::id).distinct().count()
                != checkpoint.reviews().size())
            throw new GovernanceException("effect_checkpoint_denied");
        journal.forward = checkpoint.forward();
        journal.compensation = checkpoint.compensation();
        journal.reviews.addAll(checkpoint.reviews());
        return journal;
    }

    public void observe(long clock) {
        now = Math.max(now, clock);
        for (var phase : Phase.values()) {
            var slot = slot(phase);
            if (slot == null || slot.entry().expiresAt() > now) continue;
            var entry = slot.entry();
            if (entry.status() == Status.PREPARED)
                put(
                        phase,
                        new Slot(
                                with(
                                        entry,
                                        Status.NOT_APPLIED,
                                        new Proof("sdk-not-dispatched", JsonValues.digest(null))),
                                slot.secretDigest()));
            else if (entry.status() == Status.RUNNING)
                put(phase, new Slot(with(entry, Status.UNKNOWN, null), slot.secretDigest()));
        }
    }

    public Claim claim(Phase phase, String digest, Proof parent, Owner owner, long duration) {
        Objects.requireNonNull(phase);
        requireDigest(digest);
        Objects.requireNonNull(owner);
        if (duration < 1 || duration > 300000)
            throw new IllegalArgumentException("Invalid effect lease duration");
        if (phase == Phase.COMPENSATION) {
            if (forward == null
                    || forward.entry().status() != Status.COMMITTED
                    || !forward.entry().proof().equals(parent))
                throw new GovernanceException("effect_compensation_parent_denied");
        } else {
            if (parent != null) throw new GovernanceException("effect_compensation_parent_denied");
            if (compensation != null && compensation.entry().status() != Status.NOT_APPLIED)
                throw new GovernanceException(
                        compensation.entry().status() == Status.COMMITTED
                                ? "effect_already_compensated"
                                : "effect_compensation_pending");
        }
        var old = slot(phase);
        if (old != null) {
            if (!digest.equals(old.entry().requestDigest()))
                throw new GovernanceException("effect_request_mismatch");
            if (old.entry().status() == Status.COMMITTED) return new Claim(null, old.entry());
            if (old.entry().status() != Status.NOT_APPLIED)
                throw new GovernanceException(
                        old.entry().status() == Status.UNKNOWN
                                ? "effect_outcome_unknown"
                                : "effect_in_progress");
        }
        long attempt = old == null ? 1 : Math.addExact(old.entry().attempt(), 1);
        var secret = UUID.randomUUID().toString();
        long expires = Math.addExact(now, duration);
        var lease = new Lease(key, phase, attempt, secret, digest, expires);
        put(
                phase,
                new Slot(
                        new Entry(Status.PREPARED, attempt, digest, expires, owner, null),
                        JsonValues.digest(secret)));
        return new Claim(lease, null);
    }

    private Slot owned(Lease lease) {
        var slot = slot(lease.phase());
        if (!key.equals(lease.key())
                || slot == null
                || lease.attempt() != slot.entry().attempt()
                || !lease.requestDigest().equals(slot.entry().requestDigest())
                || lease.expiresAt() != slot.entry().expiresAt()
                || !slot.secretDigest().equals(JsonValues.digest(lease.secret())))
            throw new GovernanceException("effect_lease_denied");
        return slot;
    }

    public void start(Lease lease) {
        var slot = owned(lease);
        if (slot.entry().status() != Status.PREPARED || now >= lease.expiresAt())
            throw new GovernanceException("effect_dispatch_denied");
        put(lease.phase(), new Slot(with(slot.entry(), Status.RUNNING, null), slot.secretDigest()));
    }

    public void finish(Lease lease, Status status, Proof proof) {
        terminal(status, proof);
        var slot = owned(lease);
        var current = slot.entry().status();
        if (current == Status.COMMITTED || current == Status.NOT_APPLIED) {
            if (current != status || !Objects.equals(slot.entry().proof(), proof))
                throw new GovernanceException("effect_receipt_conflict");
            return;
        }
        if (current == Status.PREPARED && status != Status.NOT_APPLIED)
            throw new GovernanceException("effect_receipt_denied");
        put(lease.phase(), new Slot(with(slot.entry(), status, proof), slot.secretDigest()));
    }

    public void reconcile(Phase phase, long attempt, Status status, Proof proof, String id) {
        terminal(status, proof);
        if (status == Status.UNKNOWN)
            throw new GovernanceException("effect_reconciliation_proof_required");
        var review = new Review(id, phase, attempt, status, proof);
        var old = reviews.stream().filter(r -> r.id().equals(id)).findFirst();
        if (old.isPresent()) {
            if (!old.get().equals(review)) throw new GovernanceException("effect_review_mismatch");
            return;
        }
        var slot = slot(phase);
        if (slot == null || slot.entry().attempt() != attempt)
            throw new GovernanceException("effect_reconciliation_stale");
        if (reviews.size() >= 128) throw new GovernanceException("effect_review_capacity_denied");
        if (slot.entry().status() != Status.UNKNOWN)
            throw new GovernanceException("effect_reconciliation_state_denied");
        put(phase, new Slot(with(slot.entry(), status, proof), slot.secretDigest()));
        reviews.add(review);
    }

    private static void terminal(Status status, Proof proof) {
        if (status != Status.COMMITTED && status != Status.NOT_APPLIED && status != Status.UNKNOWN)
            throw new IllegalArgumentException("Invalid outcome");
        terminalShape(status, proof);
    }

    private static void terminalShape(Status status, Proof proof) {
        if ((status == Status.COMMITTED || status == Status.NOT_APPLIED) != (proof != null))
            throw new IllegalArgumentException("Invalid proof shape");
        if (status == Status.NOT_APPLIED && !JsonValues.digest(null).equals(proof.resultDigest()))
            throw new IllegalArgumentException("Invalid absence proof");
    }

    private Entry with(Entry old, Status status, Proof proof) {
        return new Entry(
                status, old.attempt(), old.requestDigest(), old.expiresAt(), old.owner(), proof);
    }

    private Slot slot(Phase phase) {
        return phase == Phase.FORWARD ? forward : compensation;
    }

    private void put(Phase phase, Slot slot) {
        if (phase == Phase.FORWARD) forward = slot;
        else compensation = slot;
    }

    public View view() {
        return new View(
                forward == null ? null : forward.entry(),
                compensation == null ? null : compensation.entry());
    }

    public Checkpoint checkpoint() {
        return new Checkpoint(key, now, forward, compensation, reviews);
    }
}
