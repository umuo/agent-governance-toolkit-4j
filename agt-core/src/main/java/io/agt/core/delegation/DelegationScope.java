package io.agt.core.delegation;

import io.agt.core.*;
import io.agt.core.data.DataOperation;
import io.agt.core.data.DataResource;
import io.agt.core.policy.ParameterRule;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 宿主声明的任务能力：工具参数与数据实例均默认拒绝，所有祖先约束同时生效。 */
public record DelegationScope(
        String taskName,
        boolean modelAllowed,
        Map<String, ParameterRule> tools,
        Map<DataResource, Set<DataOperation>> resources) {
    public DelegationScope {
        Identifiers.require(taskName);
        tools = Map.copyOf(Objects.requireNonNull(tools));
        if (tools.size() > 128 || resources.size() > 128) {
            throw new IllegalArgumentException("Too many scoped capabilities");
        }
        tools.forEach(
                (name, rule) -> {
                    Identifiers.require(name);
                    if (rule.type() != ParameterRule.Type.OBJECT) {
                        throw new IllegalArgumentException("Scoped tool arguments must be objects");
                    }
                });
        var copy = new LinkedHashMap<DataResource, Set<DataOperation>>();
        resources.forEach(
                (resource, operations) -> {
                    Objects.requireNonNull(resource);
                    var allowed = Set.copyOf(operations);
                    if (allowed.isEmpty()
                            || allowed.stream()
                                    .anyMatch(
                                            operation ->
                                                    (resource.kind() == DataResource.Kind.RETRIEVAL)
                                                            != (operation
                                                                    == DataOperation.RETRIEVE))) {
                        throw new IllegalArgumentException("Invalid scoped data operations");
                    }
                    copy.put(resource, allowed);
                });
        resources = Map.copyOf(copy);
    }

    /** 参数规则不做不可靠的静态包含推断，执行时检查每一层祖先规则的交集。 */
    public void requireNarrowerThan(DelegationScope parent) {
        if (!taskName.equals(parent.taskName)
                || (modelAllowed && !parent.modelAllowed)
                || !parent.tools.keySet().containsAll(tools.keySet())
                || resources.entrySet().stream()
                        .anyMatch(
                                entry ->
                                        !parent.resources
                                                .getOrDefault(entry.getKey(), Set.of())
                                                .containsAll(entry.getValue()))) {
            throw new GovernanceException("delegation_scope_denied");
        }
    }

    public void validate(PolicyRequest request) {
        if (request.point() == InterventionPoint.PRE_MODEL_CALL && !modelAllowed) {
            throw new GovernanceException("delegation_model_denied");
        }
        if (request.point() == InterventionPoint.PRE_TOOL_CALL) {
            var rule = tools.get(request.toolName());
            if (rule == null) throw new GovernanceException("delegation_tool_denied");
            try {
                rule.validate(request.target(), request.context());
            } catch (GovernanceException denied) {
                throw new GovernanceException("delegation_arguments_denied");
            }
        }
        if (request.point() == InterventionPoint.PRE_DATA_ACCESS
                || request.point() == InterventionPoint.POST_DATA_ACCESS) {
            var target = (Map<?, ?>) request.target();
            var descriptor = DataResource.resource(request.target());
            var grant =
                    resources.entrySet().stream()
                            .filter(entry -> entry.getKey().descriptor().equals(descriptor))
                            .findFirst();
            if (grant.isEmpty()
                    || grant.get().getValue().stream()
                            .noneMatch(
                                    operation ->
                                            operation.name().equals(target.get("operation")))) {
                throw new GovernanceException("delegation_resource_denied");
            }
        }
    }

    @Override
    public String toString() {
        return "DelegationScope[redacted]";
    }
}
