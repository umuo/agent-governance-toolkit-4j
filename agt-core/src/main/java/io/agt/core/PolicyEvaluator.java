package io.agt.core;

import java.util.Set;

/**
 * 策略评估器核心接口。
 *
 * <p>负责在各干预点对请求执行合规判定、内容改写检查与审批需求判定。
 */
public interface PolicyEvaluator {
    String version();

    Set<InterventionPoint> interventionPoints();

    PolicyDecision evaluate(PolicyRequest request);

    default io.agt.core.effects.EffectRule effectRule(String tool) {
        return null;
    }

    default String toolBinding(String tool) {
        return null;
    }

    default java.util.List<io.agt.core.injection.InjectionCheck> injectionChecks() {
        return java.util.List.of();
    }

    /**
     * 改写后重新检查硬约束，不再重复改写或请求审批。
     *
     * @param request 待校验的策略请求
     */
    default void validateEffective(PolicyRequest request) {}

    default boolean requiresApproval(PolicyRequest request) {
        return false;
    }
}
