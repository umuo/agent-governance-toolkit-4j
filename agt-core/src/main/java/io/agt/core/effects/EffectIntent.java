package io.agt.core.effects;

import io.agt.core.Identifiers;

/** 由真实宿主业务流程提供；不能直接使用模型可更换的工具调用 ID。 */
public record EffectIntent(String id, String expectedState, EffectLedger.Proof parent) {
    public EffectIntent {
        Identifiers.require(id);
        Identifiers.require(expectedState);
    }
}
