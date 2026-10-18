package io.agt.core.injection;

import io.agt.core.Identifiers;
import java.util.Objects;

/** 宿主审核的实现版本声明，不声称远端模型权重的独立证明。 */
public record DetectorBinding(String revision, InjectionDetector detector) {
    public DetectorBinding {
        Identifiers.require(revision);
        Objects.requireNonNull(detector);
    }

    @Override
    public String toString() {
        return "DetectorBinding[" + revision + ",redacted]";
    }
}
