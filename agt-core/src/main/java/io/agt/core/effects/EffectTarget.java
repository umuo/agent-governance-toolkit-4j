package io.agt.core.effects;

import io.agt.core.*;
import java.util.*;

/** 可信业务目标随审批动作传递；摘要绑定目标、预期状态和补偿父凭据，不接受正文自报。 */
public record EffectTarget(EffectRule rule, EffectIntent intent) {
    public EffectTarget {
        Objects.requireNonNull(rule);
        Objects.requireNonNull(intent);
        if ((rule.phase() == EffectLedger.Phase.COMPENSATION) != (intent.parent() != null))
            throw new GovernanceException("effect_parent_required");
    }

    public String identity() {
        return JsonValues.digest(
                Arrays.asList(
                        "AGT-EFFECT-TARGET-V1",
                        rule.namespace(),
                        rule.schema(),
                        rule.phase().name(),
                        rule.ledgerId(),
                        intent.id(),
                        intent.expectedState(),
                        intent.parent() == null
                                ? null
                                : List.of(
                                        intent.parent().receipt(),
                                        intent.parent().resultDigest())));
    }

    @Override
    public String toString() {
        return "EffectTarget[redacted]";
    }
}
