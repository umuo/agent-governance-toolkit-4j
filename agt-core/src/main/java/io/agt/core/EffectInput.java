package io.agt.core;

import io.agt.core.effects.EffectTarget;
import java.util.Objects;

/** 仅供受控副作用入口携带宿主目标；模型 JSON 仍是独立的闭合工具参数。 */
record EffectInput(Object parameters, EffectTarget target) {
    EffectInput {
        parameters = JsonValues.freeze(parameters);
        Objects.requireNonNull(target);
    }

    @Override
    public String toString() {
        return "EffectInput[redacted]";
    }
}
