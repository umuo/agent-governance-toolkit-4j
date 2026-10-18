package io.agt.core.policy;

import io.agt.core.Identifiers;
import java.util.Set;

/**
 * 单个工具的治理规则。
 *
 * <p>声明工具所需权限集合、参数校验规则以及是否需要人工审批确认。
 */
public record ToolRule(
        Set<String> permissions,
        ParameterRule parameters,
        boolean approvalRequired,
        io.agt.core.effects.EffectRule effect,
        String binding) {
    public ToolRule(
            Set<String> permissions,
            ParameterRule parameters,
            boolean approvalRequired,
            io.agt.core.effects.EffectRule effect) {
        this(permissions, parameters, approvalRequired, effect, null);
    }

    public ToolRule(Set<String> permissions, ParameterRule parameters, boolean approvalRequired) {
        this(permissions, parameters, approvalRequired, null);
    }

    public ToolRule {
        if (binding != null && !binding.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid reviewed tool identity");
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        permissions.forEach(Identifiers::require);
        if (parameters == null || parameters.type() != ParameterRule.Type.OBJECT) {
            throw new IllegalArgumentException("Tool arguments need object rule");
        }
    }
}
