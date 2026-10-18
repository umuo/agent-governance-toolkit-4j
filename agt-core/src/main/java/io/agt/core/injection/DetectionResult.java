package io.agt.core.injection;

import java.util.Objects;

/** 无正文、无可执行建议的分类结果；CLEAR 只表示本次检测未命中。 */
public record DetectionResult(Status status, Double score) {
    public enum Status {
        CLEAR,
        SUSPECT,
        UNKNOWN
    }

    public DetectionResult {
        Objects.requireNonNull(status);
        if (score != null && (!Double.isFinite(score) || score < 0 || score > 1))
            throw new IllegalArgumentException("Invalid detector score");
    }

    public static DetectionResult clear() {
        return new DetectionResult(Status.CLEAR, null);
    }

    public static DetectionResult suspect() {
        return new DetectionResult(Status.SUSPECT, null);
    }

    public static DetectionResult unknown() {
        return new DetectionResult(Status.UNKNOWN, null);
    }
}
