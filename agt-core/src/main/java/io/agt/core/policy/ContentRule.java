package io.agt.core.policy;

import io.agt.core.GovernanceException;
import io.agt.core.Identifiers;
import io.agt.core.InterventionPoint;
import io.agt.core.JsonValues;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 内容匹配与改写规则。
 *
 * <p>字面规则：先检查所有 deny，再按声明顺序替换字符串值，不替换 object 的键。
 */
public record ContentRule(
        String id,
        Set<InterventionPoint> points,
        List<String> denyContains,
        Map<String, String> replacements) {
    public ContentRule {
        Identifiers.require(id);
        points = Set.copyOf(points);
        if (points.isEmpty()) {
            throw new IllegalArgumentException("Content rule needs points");
        }
        denyContains = denyContains == null ? List.of() : List.copyOf(denyContains);
        replacements =
                replacements == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(replacements));
        if (denyContains.stream().anyMatch(String::isEmpty)
                || replacements.keySet().stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Empty content pattern");
        }
        denyContains.forEach(JsonValues::freeze);
        replacements.forEach(
                (k, v) -> {
                    JsonValues.freeze(k);
                    Objects.requireNonNull(v);
                    JsonValues.freeze(v);
                });
    }

    public boolean denies(Object target) {
        if (target instanceof String s) {
            return denyContains.stream().anyMatch(s::contains);
        }
        if (target instanceof Map<?, ?> m) {
            return m.entrySet().stream().anyMatch(e -> denies(e.getKey()) || denies(e.getValue()));
        }
        if (target instanceof List<?> l) {
            return l.stream().anyMatch(this::denies);
        }
        return false;
    }

    public Object transform(Object target) {
        if (target instanceof String s) {
            for (var e : replacements.entrySet()) {
                long projected = s.length();
                int offset = 0;
                int found;
                while ((found = s.indexOf(e.getKey(), offset)) >= 0) {
                    projected += (long) e.getValue().length() - e.getKey().length();
                    if (projected > 1_048_576) {
                        throw new GovernanceException("payload_limit");
                    }
                    offset = found + e.getKey().length();
                }
                s = s.replace(e.getKey(), e.getValue());
            }
            return s;
        }
        if (target instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            m.forEach((k, v) -> result.put((String) k, transform(v)));
            return result;
        }
        if (target instanceof List<?> l) {
            return l.stream().map(this::transform).toList();
        }
        return target;
    }
}
