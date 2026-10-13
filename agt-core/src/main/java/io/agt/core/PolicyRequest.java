package io.agt.core;

import java.util.Objects;

/**
 * 策略评估请求对象。
 *
 * <p>封装干预点、可信上下文、会话标识、动作标识、可选工具名以及待受检目标数据。
 */
public record PolicyRequest(
        InterventionPoint point,
        TrustedContext context,
        String sessionId,
        String actionId,
        String toolName,
        Object target) {
    public PolicyRequest {
        Objects.requireNonNull(point);
        Objects.requireNonNull(context);
        Identifiers.require(sessionId);
        Identifiers.require(actionId);
        if (toolName != null) {
            Identifiers.require(toolName);
        }
        target = JsonValues.freeze(target);
    }

    public PolicyRequest withTarget(Object replacement) {
        return new PolicyRequest(point, context, sessionId, actionId, toolName, replacement);
    }

    @Override
    public String toString() {
        return "PolicyRequest[" + point + ", redacted]";
    }
}
