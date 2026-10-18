package io.agt.core.data;

import io.agt.core.Identifiers;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** 可信后端提供的逻辑文档、发布版本与片段位置；不从正文或自报 metadata 自动推导。 */
public record DocumentSegment(String documentId, long version, String segmentId) {
    public DocumentSegment {
        Identifiers.require(documentId);
        Identifiers.require(segmentId);
        if (version < 1) throw new IllegalArgumentException("Invalid document version");
    }

    public Map<String, Object> descriptor() {
        return Map.of("documentId", documentId, "version", version, "segmentId", segmentId);
    }

    public static DocumentSegment from(Object value) {
        if (!(value instanceof Map<?, ?> fields)
                || !fields.keySet().equals(Set.of("documentId", "version", "segmentId")))
            throw new IllegalArgumentException("Invalid document segment");
        Object version = fields.get("version");
        if (!(version instanceof Byte
                || version instanceof Short
                || version instanceof Integer
                || version instanceof Long
                || version instanceof java.math.BigInteger
                || version instanceof BigDecimal))
            throw new IllegalArgumentException("Invalid document version");
        long number = new BigDecimal(version.toString()).longValueExact();
        return new DocumentSegment(
                (String) fields.get("documentId"), number, (String) fields.get("segmentId"));
    }
}
