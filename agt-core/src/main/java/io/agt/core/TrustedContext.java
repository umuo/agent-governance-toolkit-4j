package io.agt.core;

import java.util.Objects;
import java.util.Set;

/** 仅由宿主认证入口构造；此类型本身不验证 token。 */
public record TrustedContext(
    String agentId, String tenantId, String principalId, Set<String> permissions) {
  public TrustedContext {
    Identifiers.require(agentId);
    Identifiers.require(tenantId);
    Identifiers.require(principalId);
    permissions = Set.copyOf(Objects.requireNonNull(permissions));
    if (permissions.size() > 128) throw new IllegalArgumentException("Too many permissions");
    permissions.forEach(Identifiers::require);
  }

  @Override
  public String toString() {
    return "TrustedContext[redacted]";
  }
}
