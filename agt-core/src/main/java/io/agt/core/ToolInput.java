package io.agt.core;

import io.agt.core.tools.ToolLease;
import java.util.Objects;

/** 内部可信版本绑定；租约和制品不会进入模型 JSON、审批正文或审计投影。 */
record ToolInput(Object parameters, ToolLease lease) {
    ToolInput {
        Objects.requireNonNull(lease);
        if (!(parameters instanceof EffectInput)) parameters = JsonValues.freeze(parameters);
    }

    public String toString() {
        return "ToolInput[redacted]";
    }
}
