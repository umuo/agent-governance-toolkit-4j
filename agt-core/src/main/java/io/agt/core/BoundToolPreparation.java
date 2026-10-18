package io.agt.core;

import io.agt.core.tools.ToolLease;

/** 准备阶段至真实派发前重验同一不可伪造的本机租约。 */
record BoundToolPreparation<E>(ToolLease lease, E value) {
    public String toString() {
        return "BoundToolPreparation[redacted]";
    }
}
