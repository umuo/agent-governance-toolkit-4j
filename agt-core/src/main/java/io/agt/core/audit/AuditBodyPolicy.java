package io.agt.core.audit;

import io.agt.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** 默认关闭；对象只采集宿主选定的顶层字段，自由文本需要单独启用，不承诺识别所有 PII。 */
public record AuditBodyPolicy(
        Set<InterventionPoint> points,
        Set<String> fields,
        boolean includeText,
        int maxInputCharacters,
        int maxStoredBytes,
        Set<String> excludedFields) {
    public AuditBodyPolicy(
            Set<InterventionPoint> points,
            Set<String> fields,
            boolean includeText,
            int maxInputCharacters,
            int maxStoredBytes) {
        this(points, fields, includeText, maxInputCharacters, maxStoredBytes, Set.of());
    }

    public static final AuditBodyPolicy NONE = new AuditBodyPolicy(Set.of(), Set.of(), false, 1, 1);
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern KEY = Pattern.compile("sk-[A-Za-z0-9_-]{16,}");
    private static final Pattern ASSIGNMENT =
            Pattern.compile(
                    "(?i)(password|api[_-]?key|access[_-]?token|refresh[_-]?token|secret)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)");
    private static final Set<String> SECRET =
            Set.of(
                    "authorization",
                    "headers",
                    "httpheaders",
                    "cookie",
                    "setcookie",
                    "password",
                    "secret",
                    "credential",
                    "credentials",
                    "privatekey");

    public AuditBodyPolicy {
        points = Set.copyOf(points);
        fields = Set.copyOf(fields);
        excludedFields =
                excludedFields.stream()
                        .map(AuditBodyPolicy::normalize)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (excludedFields.size() > 64
                || excludedFields.stream().anyMatch(f -> f.isEmpty() || f.length() > 128))
            throw new IllegalArgumentException("Invalid audit exclusions");
        if (points.contains(InterventionPoint.AGENT_STARTUP)
                || points.contains(InterventionPoint.AGENT_SHUTDOWN))
            throw new IllegalArgumentException("Lifecycle bodies cannot be collected");
        if (fields.size() > 64 || fields.stream().anyMatch(f -> f.isEmpty() || f.length() > 128))
            throw new IllegalArgumentException("Invalid audit field selection");
        if (maxInputCharacters < 1
                || maxInputCharacters > 1048576
                || maxStoredBytes < 1
                || maxStoredBytes > 65536)
            throw new IllegalArgumentException("Invalid audit body limits");
    }

    public String identity() {
        return JsonValues.digest(
                Map.of(
                        "points",
                        points.stream().map(InterventionPoint::wireName).sorted().toList(),
                        "fields",
                        fields.stream().sorted().toList(),
                        "excluded",
                        excludedFields.stream().sorted().toList(),
                        "text",
                        includeText,
                        "input",
                        maxInputCharacters,
                        "stored",
                        maxStoredBytes));
    }

    public AuditBody capture(InterventionPoint point, Object original, Object effective) {
        if (!points.contains(point)) return AuditBody.omitted("disabled");
        try {
            return captureBounded(original, effective);
        } catch (GovernanceException limited) {
            return AuditBody.omitted("projection_limit");
        }
    }

    private AuditBody captureBounded(Object original, Object effective) {
        String first = JsonValues.write(original), second = JsonValues.write(effective);
        if ((long) first.length() + second.length() > maxInputCharacters)
            return AuditBody.omitted("input_limit");
        var selected = new LinkedHashMap<String, Object>();
        selected.put("original", select(original));
        selected.put("effective", select(effective));
        if (JsonValues.write(selected).getBytes(StandardCharsets.UTF_8).length > maxStoredBytes)
            return AuditBody.omitted("storage_limit");
        return new AuditBody("captured", selected);
    }

    private Object select(Object value) {
        if (value instanceof Map<?, ?> map) {
            var selected = new TreeMap<String, Object>();
            map.forEach(
                    (key, item) -> {
                        if (fields.contains(key))
                            selected.put(
                                    (String) key,
                                    secret((String) key) ? "[REDACTED]" : redact(item));
                    });
            return selected;
        }
        return includeText ? redact(value) : "[OMITTED]";
    }

    private static String normalize(String key) {
        return java.text.Normalizer.normalize(key, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private boolean secret(String key) {
        String normalized = normalize(key);
        return SECRET.contains(normalized)
                || excludedFields.contains(normalized)
                || normalized.endsWith("apikey")
                || normalized.endsWith("token")
                || normalized.endsWith("password");
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new TreeMap<String, Object>();
            map.forEach(
                    (key, item) ->
                            copy.put(
                                    (String) key,
                                    secret((String) key) ? "[REDACTED]" : redact(item)));
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        if (value instanceof String text) {
            if (!includeText) return "[OMITTED]";
            String trimmed = text.stripLeading();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    return redact(JsonValues.parse(text));
                } catch (GovernanceException ignored) {
                    return "[UNPARSEABLE_JSON_OMITTED]";
                }
            }
            return ASSIGNMENT
                    .matcher(
                            KEY.matcher(BEARER.matcher(text).replaceAll("Bearer [REDACTED]"))
                                    .replaceAll("[REDACTED]"))
                    .replaceAll("$1=[REDACTED]");
        }
        return value;
    }
}
