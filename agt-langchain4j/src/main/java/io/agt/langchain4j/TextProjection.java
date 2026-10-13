package io.agt.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import io.agt.core.GovernanceException;
import io.agt.core.JsonValues;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 消息文本与结构投影工具。
 *
 * <p>只读元数据和可改写正文分开；不允许策略改写角色、工具名、调用 ID 或消息数量。
 */
final class TextProjection {
    private TextProjection() {}

    static Map<String, Object> message(ChatMessage message) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("role", message.type().name());
        if (message instanceof SystemMessage m) {
            p.put("text", m.text());
        } else if (message instanceof UserMessage m) {
            if (!m.hasSingleText()) {
                unsupported();
            }
            p.put("text", m.singleText());
            p.put("name", m.name());
            p.put("attributes", m.attributes());
        } else if (message instanceof AiMessage m) {
            if (m.images() != null && !m.images().isEmpty()) {
                unsupported();
            }
            p.put("text", m.text());
            p.put("thinking", m.thinking());
            p.put("attributes", m.attributes());
            p.put(
                    "toolCalls",
                    m.toolExecutionRequests() == null
                            ? List.of()
                            : m.toolExecutionRequests().stream()
                                    .map(TextProjection::call)
                                    .toList());
        } else if (message instanceof ToolExecutionResultMessage m) {
            if (!m.hasSingleText()) {
                unsupported();
            }
            p.put("text", m.text());
            p.put("id", m.id());
            p.put("toolName", m.toolName());
            p.put("attributes", m.attributes());
            p.put("isError", m.isError());
        } else {
            unsupported();
        }
        return object(JsonValues.freeze(p));
    }

    private static Map<String, Object> call(ToolExecutionRequest r) {
        var p = new LinkedHashMap<String, Object>();
        p.put("id", r.id());
        p.put("name", r.name());
        p.put("arguments", JsonValues.parseObject(r.arguments()));
        return p;
    }

    static ChatMessage replace(ChatMessage original, Object value) {
        Map<String, Object> p = object(value);
        Map<String, Object> before = message(original);
        sameKeys(before, p);
        fixed(before, p, "role");
        String text = nullableString(p.get("text"));
        if (original instanceof SystemMessage) {
            return SystemMessage.from(requiredString(text));
        }
        if (original instanceof UserMessage m) {
            fixed(before, p, "name");
            return m.toBuilder()
                    .contents(List.of(TextContent.from(requiredString(text))))
                    .attributes(nullableObject(p.get("attributes")))
                    .build();
        }
        if (original instanceof AiMessage m) {
            List<?> calls = list(p.get("toolCalls"));
            List<ToolExecutionRequest> originalCalls =
                    m.toolExecutionRequests() == null ? List.of() : m.toolExecutionRequests();
            if (calls.size() != originalCalls.size()) {
                unsupported();
            }
            List<ToolExecutionRequest> rewritten = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                var c = object(calls.get(i));
                var old = call(originalCalls.get(i));
                sameKeys(old, c);
                fixed(old, c, "id");
                fixed(old, c, "name");
                rewritten.add(
                        ToolExecutionRequest.builder()
                                .id(originalCalls.get(i).id())
                                .name(originalCalls.get(i).name())
                                .arguments(JsonValues.write(object(c.get("arguments"))))
                                .build());
            }
            return m.toBuilder()
                    .text(text)
                    .thinking(nullableString(p.get("thinking")))
                    .toolExecutionRequests(rewritten)
                    .attributes(nullableObject(p.get("attributes")))
                    .build();
        }
        if (original instanceof ToolExecutionResultMessage m) {
            fixed(before, p, "id");
            fixed(before, p, "toolName");
            fixed(before, p, "isError");
            return m.toBuilder()
                    .contents(List.of(TextContent.from(requiredString(text))))
                    .attributes(nullableObject(p.get("attributes")))
                    .build();
        }
        unsupported();
        return null;
    }

    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> m)
                || m.keySet().stream().anyMatch(k -> !(k instanceof String))) {
            unsupported();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    static Map<String, Object> nullableObject(Object value) {
        return value == null ? null : object(value);
    }

    static List<?> list(Object v) {
        if (!(v instanceof List<?>)) {
            unsupported();
        }
        return (List<?>) v;
    }

    static String nullableString(Object v) {
        if (v != null && !(v instanceof String)) {
            unsupported();
        }
        return (String) v;
    }

    static String requiredString(Object v) {
        if (!(v instanceof String)) {
            unsupported();
        }
        return (String) v;
    }

    static void sameKeys(Map<String, Object> before, Map<String, Object> after) {
        if (!before.keySet().equals(after.keySet())) {
            unsupported();
        }
    }

    static void fixed(Map<String, Object> before, Map<String, Object> after, String key) {
        if (!Objects.equals(before.get(key), after.get(key))) {
            unsupported();
        }
    }

    static void unsupported() {
        throw new GovernanceException("transform_or_shape_unsupported");
    }
}
