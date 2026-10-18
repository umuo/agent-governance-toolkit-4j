package io.agt.examples.live.web.tracing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 保留 JSON 结构，屏蔽常见凭据字段与 Bearer/API-key 标记，不接收 HTTP 头。 */
final class TraceBodies {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SECRETS =
            Set.of(
                    "authorization",
                    "cookie",
                    "setcookie",
                    "password",
                    "secret",
                    "accesstoken",
                    "refreshtoken");

    private TraceBodies() {}

    static int bytes(JsonNode node) {
        return node.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    static TraceStore.Payload capture(String kind, JsonNode source, int limit) {
        JsonNode clean;
        try {
            // 内存和历史恢复使用相同 JSON 数字表示，例如值为 120000 的 long 字段。
            clean = redact(JSON.readTree(JSON.writeValueAsBytes(source)));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Invalid trace body", error);
        }
        int size = bytes(clean);
        if (size <= limit) return new TraceStore.Payload(kind, clean, false, size);
        // 超限时展示原 JSON 的文本片段，不伪装成完整 JSON；按最坏转义长度预留空间。
        String text = clean.toString();
        int characters = Math.min(text.length(), Math.max(0, (limit - 2) / 6));
        if (characters > 0 && Character.isHighSurrogate(text.charAt(characters - 1))) characters--;
        JsonNode prefix =
                characters == 0
                        ? NullNode.instance
                        : TextNode.valueOf(text.substring(0, characters));
        return new TraceStore.Payload(kind, prefix, true, size);
    }

    private static JsonNode redact(JsonNode value) {
        if (value.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            value.fields()
                    .forEachRemaining(
                            entry -> {
                                String key =
                                        entry.getKey()
                                                .toLowerCase(java.util.Locale.ROOT)
                                                .replace("-", "")
                                                .replace("_", "");
                                result.set(
                                        entry.getKey(),
                                        SECRETS.contains(key) || key.endsWith("apikey")
                                                ? TextNode.valueOf("[REDACTED]")
                                                : redact(entry.getValue()));
                            });
            return result;
        }
        if (value.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            value.forEach(child -> result.add(redact(child)));
            return result;
        }
        if (value.isTextual())
            return TextNode.valueOf(
                    value.asText()
                            .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                            .replaceAll("sk-[A-Za-z0-9_-]{16,}", "[REDACTED]"));
        return value.deepCopy();
    }
}
