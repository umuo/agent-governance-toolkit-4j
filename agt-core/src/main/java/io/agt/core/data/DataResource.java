package io.agt.core.data;

import io.agt.core.GovernanceException;
import io.agt.core.Identifiers;
import io.agt.core.JsonValues;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 宿主绑定实际数据源的描述符。
 *
 * <p>不能从提示词或文档元数据创建可信所有权。instanceId 必须指向实际分区或记忆实例，选择器及来源 metadata 不证明所有权。
 */
public record DataResource(
        String name, Kind kind, String tenantId, String principalId, String instanceId) {
    public enum Kind {
        RETRIEVAL,
        MEMORY
    }

    public DataResource {
        Identifiers.require(name);
        Objects.requireNonNull(kind);
        Identifiers.require(tenantId);
        if (principalId != null) {
            Identifiers.require(principalId);
        }
        Identifiers.require(instanceId);
    }

    public Map<String, Object> descriptor() {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("kind", kind.name());
        result.put("tenantId", tenantId);
        result.put("principalId", principalId);
        result.put("instanceId", instanceId);
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> target(DataOperation operation, Object value) {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        Map.of(
                                "resource",
                                descriptor(),
                                "operation",
                                operation.name(),
                                "value",
                                Collections.singletonList(value))));
    }

    public Object value(Object effective, DataOperation operation) {
        if (!(effective instanceof Map<?, ?> target)
                || !target.keySet().equals(Set.of("resource", "operation", "value"))
                || !descriptor().equals(target.get("resource"))
                || !operation.name().equals(target.get("operation"))
                || !(target.get("value") instanceof List<?> values)
                || values.size() != 1) {
            throw new GovernanceException("data_identity_transform_unsupported");
        }
        return values.get(0);
    }

    public static Map<String, Object> resource(Object target) {
        if (!(target instanceof Map<?, ?> map)
                || !(map.get("resource") instanceof Map<?, ?> resource)) {
            throw new GovernanceException("invalid_data_target");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonValues.freeze(resource);
        return result;
    }
}
