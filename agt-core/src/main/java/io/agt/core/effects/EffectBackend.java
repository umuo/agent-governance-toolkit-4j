package io.agt.core.effects;

import io.agt.core.*;
import java.util.Objects;

/** 可信后端契约；执行端须原子验证预期状态、幂等键和 fence，查询不到记录本身不证明未应用。 */
public interface EffectBackend {
    record Call(
            EffectLedger.Key key,
            EffectLedger.Phase phase,
            Object parameters,
            String expectedState,
            String idempotencyKey,
            String requestDigest,
            long attempt) {
        public Call {
            Objects.requireNonNull(key);
            Objects.requireNonNull(phase);
            parameters = JsonValues.freeze(parameters);
            Identifiers.require(expectedState);
            EffectLedger.requireDigest(idempotencyKey);
            EffectLedger.requireDigest(requestDigest);
            if (attempt < 1) throw new IllegalArgumentException("Invalid effect attempt");
        }

        @Override
        public String toString() {
            return "EffectCall[redacted]";
        }
    }

    record Reply(EffectLedger.Status status, String receipt, Object value) {
        public Reply {
            if (status != EffectLedger.Status.COMMITTED
                    && status != EffectLedger.Status.NOT_APPLIED
                    && status != EffectLedger.Status.UNKNOWN)
                throw new IllegalArgumentException("Invalid backend outcome");
            if (status != EffectLedger.Status.UNKNOWN) Identifiers.require(receipt);
            else if (receipt != null || value != null)
                throw new IllegalArgumentException("Unknown outcome has no result");
            if (status == EffectLedger.Status.NOT_APPLIED && value != null)
                throw new IllegalArgumentException("Not applied has no result");
            value = JsonValues.freeze(value);
        }

        public static Reply committed(String receipt, Object value) {
            return new Reply(EffectLedger.Status.COMMITTED, receipt, value);
        }

        public static Reply notApplied(String receipt) {
            return new Reply(EffectLedger.Status.NOT_APPLIED, receipt, null);
        }

        public static Reply unknown() {
            return new Reply(EffectLedger.Status.UNKNOWN, null, null);
        }

        @Override
        public String toString() {
            return "EffectReply[" + status + ",redacted]";
        }
    }

    String currentState(Call call) throws Exception;

    Reply execute(Call call) throws Exception;

    Object recover(Call call, EffectLedger.Proof proof) throws Exception;
}
