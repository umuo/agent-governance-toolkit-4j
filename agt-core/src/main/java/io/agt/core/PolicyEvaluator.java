package io.agt.core;

import java.util.Set;

public interface PolicyEvaluator {
  String version();

  Set<InterventionPoint> interventionPoints();

  PolicyDecision evaluate(PolicyRequest request);

  /** 改写后重新检查硬约束，不再重复改写或请求审批。 */
  default void validateEffective(PolicyRequest request) {}

  default boolean requiresApproval(PolicyRequest request) {
    return false;
  }
}
