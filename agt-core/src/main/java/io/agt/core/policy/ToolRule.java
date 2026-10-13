package io.agt.core.policy;

import io.agt.core.Identifiers;
import java.util.Set;

/**
 * 单个工具的治理规则。
 *
 * <p>声明工具所需权限集合、参数校验规则以及是否需要人工审批确认。
 */
public record ToolRule(
        Set<String> permissions, ParameterRule parameters, boolean approvalRequired) {
    public ToolRule {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        permissions.forEach(Identifiers::require);
        if (parameters == null || parameters.type() != ParameterRule.Type.OBJECT) {
            throw new IllegalArgumentException("Tool arguments need object rule");
        }
    }
}
