package io.agt.core.effects;

import io.agt.core.*;
import java.util.*;

/** 宿主可信副作用日志；私有租约不进入模型参数、后端请求或公开链路。 */
public interface EffectLedger {
    enum Phase {
        FORWARD,
        COMPENSATION
    }

    enum Status {
        PREPARED,
        RUNNING,
        COMMITTED,
        NOT_APPLIED,
        UNKNOWN
    }

    record Key(String tenantId, String principalId, String namespace, String intentId) {
        public Key {
            Identifiers.require(tenantId);
            Identifiers.require(principalId);
            Identifiers.require(namespace);
            Identifiers.require(intentId);
        }

        public String digest() {
            return JsonValues.digest(
                    List.of("AGT-EFFECT-V1", tenantId, principalId, namespace, intentId));
        }

        public void requireOwner(TrustedContext actor) {
            if (!tenantId.equals(actor.tenantId()) || !principalId.equals(actor.principalId()))
                throw new GovernanceException("effect_owner_denied");
        }

        @Override
        public String toString() {
            return "EffectKey[redacted]";
        }
    }

    record Owner(String taskId, String sessionId, String actionId, String agentId) {
        public Owner {
            Identifiers.require(taskId);
            Identifiers.require(sessionId);
            Identifiers.require(actionId);
            Identifiers.require(agentId);
        }
    }

    record Proof(String receipt, String resultDigest) {
        public Proof {
            Identifiers.require(receipt);
            requireDigest(resultDigest);
        }
    }

    record Lease(
            Key key,
            Phase phase,
            long attempt,
            String secret,
            String requestDigest,
            long expiresAt) {
        public Lease {
            Objects.requireNonNull(key);
            Objects.requireNonNull(phase);
            Identifiers.require(secret);
            requireDigest(requestDigest);
            if (attempt < 1 || expiresAt < 1)
                throw new IllegalArgumentException("Invalid effect lease");
        }

        @Override
        public String toString() {
            return "EffectLease[redacted]";
        }
    }

    record Claim(Lease lease, Entry replay) {
        public Claim {
            if ((lease == null) == (replay == null))
                throw new IllegalArgumentException("Invalid effect claim");
        }
    }

    record Entry(
            Status status,
            long attempt,
            String requestDigest,
            long expiresAt,
            Owner owner,
            Proof proof) {
        public Entry {
            Objects.requireNonNull(status);
            Objects.requireNonNull(owner);
            requireDigest(requestDigest);
            if (attempt < 1 || expiresAt < 1)
                throw new IllegalArgumentException("Invalid effect entry");
        }
    }

    record View(Entry forward, Entry compensation) {}

    String identity();

    Claim claim(
            Key key,
            Phase phase,
            String requestDigest,
            Proof parent,
            Owner owner,
            long leaseMillis);

    void start(Lease lease);

    void finish(Lease lease, Status status, Proof proof);

    View inspect(Key key, TrustedContext actor);

    void reconcile(
            Key key,
            Phase phase,
            long expectedAttempt,
            Status status,
            Proof proof,
            TrustedContext actor,
            String reviewId);

    static void requireDigest(String digest) {
        if (digest == null || !digest.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid effect digest");
    }
}
