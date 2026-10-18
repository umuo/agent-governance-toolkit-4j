package io.agt.core;

import java.util.Objects;

/**
 * 策略评估请求对象。
 *
 * <p>封装干预点、可信上下文、会话标识、动作标识、可选工具名以及待受检目标数据。 effect 仅由副作用入口绑定，供审批展示并纳入动作摘要；普通请求为空。
 */
public record PolicyRequest(
        InterventionPoint point,
        TrustedContext context,
        String sessionId,
        String actionId,
        String toolName,
        Object target,
        io.agt.core.effects.EffectTarget effect,
        String toolBinding) {
    public PolicyRequest(
            InterventionPoint point,
            TrustedContext context,
            String sessionId,
            String actionId,
            String toolName,
            Object target,
            io.agt.core.effects.EffectTarget effect) {
        this(point, context, sessionId, actionId, toolName, target, effect, null);
    }

    public PolicyRequest(
            InterventionPoint point,
            TrustedContext context,
            String sessionId,
            String actionId,
            String toolName,
            Object target) {
        this(point, context, sessionId, actionId, toolName, target, null);
    }

    public PolicyRequest {
        if (toolBinding != null
                && (!toolBinding.matches("sha256:[0-9a-f]{64}")
                        || toolName == null
                        || !(point == InterventionPoint.PRE_TOOL_CALL
                                || point == InterventionPoint.POST_TOOL_CALL)))
            throw new IllegalArgumentException("Invalid tool binding");
        Objects.requireNonNull(point);
        Objects.requireNonNull(context);
        Identifiers.require(sessionId);
        Identifiers.require(actionId);
        if (toolName != null) {
            Identifiers.require(toolName);
        }
        if (effect != null && (point != InterventionPoint.PRE_TOOL_CALL || toolName == null))
            throw new IllegalArgumentException("Effect target requires a tool preparation");
        target = JsonValues.freeze(target);
    }

    public PolicyRequest withTarget(Object replacement) {
        return new PolicyRequest(
                point, context, sessionId, actionId, toolName, replacement, effect, toolBinding);
    }

    @Override
    public String toString() {
        return "PolicyRequest[" + point + ", redacted]";
    }
}
