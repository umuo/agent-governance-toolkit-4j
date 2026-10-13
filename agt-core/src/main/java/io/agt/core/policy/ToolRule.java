package io.agt.core.policy;

import io.agt.core.Identifiers;
import java.util.Set;

public record ToolRule(
    Set<String> permissions, ParameterRule parameters, boolean approvalRequired) {
  public ToolRule {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    permissions.forEach(Identifiers::require);
    if (parameters == null || parameters.type() != ParameterRule.Type.OBJECT)
      throw new IllegalArgumentException("Tool arguments need object rule");
  }
}
