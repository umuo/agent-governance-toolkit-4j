package io.agt.langchain4j;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.query.Query;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.InterventionPoint;
import io.agt.core.JsonValues;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据访问投影保留身份和来源元数据；只改写查询文本、文档文本和文本消息。
 *
 * <p>InvocationContext 保留原生关联；来源字段复建使用检查后的快照。
 */
final class DataProjection {
    private DataProjection() {}

    static void limit(int size, HostSession session) {
        if (size > session.dataLimits().maxItems()) {
            throw new GovernanceException("data_items_exceeded");
        }
    }

    static List<Object> messages(List<ChatMessage> messages, HostSession session) {
        limit(messages.size(), session);
        return messages.stream().map(m -> (Object) TextProjection.message(m)).toList();
    }

    static List<ChatMessage> replaceMessages(
            List<ChatMessage> original, Object value, HostSession session) {
        var values = TextProjection.list(value);
        limit(values.size(), session);
        if (original.size() != values.size()) {
            TextProjection.unsupported();
        }
        var result = new ArrayList<ChatMessage>();
        for (int i = 0; i < values.size(); i++) {
            result.add(TextProjection.replace(original.get(i), values.get(i)));
        }
        return List.copyOf(result);
    }

    static Map<String, Object> query(Query query, HostSession session) {
        var map = new LinkedHashMap<String, Object>();
        map.put("text", query.text());
        Object metadata = null;
        if (query.metadata() != null) {
            var md = new LinkedHashMap<String, Object>();
            md.put("memoryId", query.metadata().chatMemoryId());
            md.put(
                    "message",
                    query.metadata().chatMessage() == null
                            ? null
                            : TextProjection.message(query.metadata().chatMessage()));
            md.put(
                    "history",
                    query.metadata().chatMemory() == null
                            ? null
                            : messages(query.metadata().chatMemory(), session));
            md.put(
                    "system",
                    query.metadata().systemMessage() == null
                            ? null
                            : TextProjection.message(query.metadata().systemMessage()));
            metadata = md;
        }
        map.put("metadata", metadata);
        return TextProjection.object(JsonValues.freeze(map));
    }

    static Query replaceQuery(Query original, Object effective, HostSession session) {
        var before = query(original, session);
        var value = TextProjection.object(effective);
        TextProjection.sameKeys(before, value);
        String text = TextProjection.requiredString(value.get("text"));
        if (text.isBlank()) {
            TextProjection.unsupported();
        }
        if (original.metadata() == null) {
            TextProjection.fixed(before, value, "metadata");
            return Query.from(text);
        }
        var oldMetadata = TextProjection.object(before.get("metadata"));
        var metadata = TextProjection.object(value.get("metadata"));
        TextProjection.sameKeys(oldMetadata, metadata);
        TextProjection.fixed(oldMetadata, metadata, "memoryId");
        var originalMetadata = original.metadata();
        var builder =
                dev.langchain4j.rag.query.Metadata.builder()
                        .invocationContext(originalMetadata.invocationContext())
                        .chatMessage(
                                TextProjection.replace(
                                        originalMetadata.chatMessage(), metadata.get("message")));
        if (originalMetadata.systemMessage() == null) {
            TextProjection.fixed(oldMetadata, metadata, "system");
        } else {
            builder.systemMessage(
                    (SystemMessage)
                            TextProjection.replace(
                                    originalMetadata.systemMessage(), metadata.get("system")));
        }
        if (originalMetadata.chatMemory() == null) {
            TextProjection.fixed(oldMetadata, metadata, "history");
        } else {
            builder.chatMemory(
                    replaceMessages(
                            originalMetadata.chatMemory(), metadata.get("history"), session));
        }
        return Query.from(text, builder.build());
    }

    static List<Object> contents(List<Content> contents, HostSession session) {
        limit(contents.size(), session);
        var result = new ArrayList<Object>();
        for (var content : contents) {
            var value = new LinkedHashMap<String, Object>();
            value.put("text", content.textSegment().text());
            var documentMetadata = new LinkedHashMap<String, Object>();
            content.textSegment()
                    .metadata()
                    .toMap()
                    .forEach(
                            (k, v) ->
                                    documentMetadata.put(
                                            k, v instanceof UUID uuid ? uuid.toString() : v));
            value.put("documentMetadata", documentMetadata);
            var metadata = new LinkedHashMap<String, Object>();
            if (content.metadata() != null) {
                content.metadata().forEach((k, v) -> metadata.put(k.name(), v));
            }
            value.put("metadata", metadata);
            result.add(JsonValues.freeze(value));
        }
        return List.copyOf(result);
    }

    static List<Content> replaceContents(
            List<Content> original, Object effective, HostSession session) {
        var values = TextProjection.list(effective);
        var before = contents(original, session);
        if (before.size() != values.size()) {
            TextProjection.unsupported();
        }
        var result = new ArrayList<Content>();
        for (int i = 0; i < values.size(); i++) {
            var old = TextProjection.object(before.get(i));
            var value = TextProjection.object(values.get(i));
            TextProjection.sameKeys(old, value);
            TextProjection.fixed(old, value, "documentMetadata");
            TextProjection.fixed(old, value, "metadata");
            var metadata = new EnumMap<ContentMetadata, Object>(ContentMetadata.class);
            TextProjection.object(value.get("metadata"))
                    .forEach((k, v) -> metadata.put(ContentMetadata.valueOf(k), v));
            var restored = new LinkedHashMap<String, Object>();
            var nativeMetadata = original.get(i).textSegment().metadata().toMap();
            TextProjection.object(value.get("documentMetadata"))
                    .forEach(
                            (k, v) ->
                                    restored.put(
                                            k,
                                            nativeMetadata.get(k) instanceof UUID
                                                    ? UUID.fromString(
                                                            TextProjection.requiredString(v))
                                                    : v));
            result.add(
                    Content.from(
                            TextSegment.from(
                                    TextProjection.requiredString(value.get("text")),
                                    Metadata.from(restored)),
                            metadata));
        }
        return List.copyOf(result);
    }

    static GovernanceException failure(HostSession session, RuntimeException error) {
        String code = error instanceof GovernanceException g ? g.code() : "data_access_failed";
        if (session.budgetSnapshot().failed()) {
            return session.refuse(code);
        }
        return session.fail(InterventionPoint.PRE_DATA_ACCESS, code);
    }
}
