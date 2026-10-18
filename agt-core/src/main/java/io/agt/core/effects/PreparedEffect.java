package io.agt.core.effects;

import io.agt.core.*;
import java.util.Objects;

/** 治理准备标记；正文无法构造此 Java 对象，真实执行仍需会话内已提交动作。 */
public record PreparedEffect(
        EffectRule rule,
        EffectLedger ledger,
        EffectIntent intent,
        EffectBackend backend,
        Object parameters) {
    public PreparedEffect {
        Objects.requireNonNull(rule);
        Objects.requireNonNull(ledger);
        Objects.requireNonNull(intent);
        Objects.requireNonNull(backend);
        if (!rule.ledgerId().equals(ledger.identity()))
            throw new GovernanceException("effect_ledger_binding_denied");
        if ((rule.phase() == EffectLedger.Phase.COMPENSATION) != (intent.parent() != null))
            throw new GovernanceException("effect_parent_required");
        parameters = JsonValues.freeze(parameters);
    }

    @Override
    public String toString() {
        return "PreparedEffect[redacted]";
    }
}
