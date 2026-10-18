package io.agt.core.effects;

import io.agt.core.Identifiers;
import java.util.Objects;

/** 策略固定后端命名空间、结果协议、阶段及持久目录身份，不包含业务幂等键或凭据。 */
public record EffectRule(
        String namespace, String schema, EffectLedger.Phase phase, String ledgerId) {
    public EffectRule {
        Identifiers.require(namespace);
        Identifiers.require(schema);
        Objects.requireNonNull(phase);
        Identifiers.require(ledgerId);
    }
}
