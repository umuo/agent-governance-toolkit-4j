package io.agt.core.injection;

import io.agt.core.*;
import java.util.*;

/** 可签名的检测声明；实现与凭据由宿主绑定，正文不能改变故障策略。 */
public record InjectionCheck(
        String id,
        String detector,
        String revision,
        Set<InterventionPoint> points,
        long timeoutMillis,
        int maxCharacters,
        Failure failure) {
    public enum Failure {
        DENY,
        WARN
    }

    public InjectionCheck {
        Identifiers.require(id);
        Identifiers.require(detector);
        Identifiers.require(revision);
        points = Set.copyOf(points);
        Objects.requireNonNull(failure);
        if (points.isEmpty()
                || points.contains(InterventionPoint.AGENT_STARTUP)
                || points.contains(InterventionPoint.AGENT_SHUTDOWN)
                || timeoutMillis < 1
                || timeoutMillis > 30000
                || maxCharacters < 1
                || maxCharacters > JsonValues.MAX_CHARACTERS)
            throw new IllegalArgumentException("Invalid injection check");
    }
}
