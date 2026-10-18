package io.agt.core.data;

import io.agt.core.*;
import java.util.*;

/** 内容寻址的来源引用；实际资源身份仍由宿主绑定，不采信文档自报的 ID 或等级。 */
public record DataFragment(Kind kind, String digest, DocumentSegment document) {
    public DataFragment(Kind kind, String digest) {
        this(kind, digest, null);
    }

    public enum Kind {
        DOCUMENT_SEGMENT,
        MEMORY_MESSAGE
    }

    public DataFragment {
        Objects.requireNonNull(kind);
        if (digest == null || !digest.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid fragment digest");
        if (document != null && kind != Kind.DOCUMENT_SEGMENT)
            throw new IllegalArgumentException("Only documents have document versions");
    }

    public static DataFragment document(String text) {
        return new DataFragment(
                Kind.DOCUMENT_SEGMENT,
                JsonValues.digest(List.of("DOCUMENT_SEGMENT", Objects.requireNonNull(text))));
    }

    public static DataFragment message(Object canonicalMessage) {
        return new DataFragment(
                Kind.MEMORY_MESSAGE,
                JsonValues.digest(
                        List.of("MEMORY_MESSAGE", Objects.requireNonNull(canonicalMessage))));
    }

    public static DataFragment document(String text, DocumentSegment reference) {
        return new DataFragment(
                Kind.DOCUMENT_SEGMENT, document(text).digest(), Objects.requireNonNull(reference));
    }

    public Map<String, Object> descriptor() {
        return document == null
                ? Map.of("kind", kind.name(), "digest", digest)
                : Map.of("kind", kind.name(), "digest", digest, "document", document.descriptor());
    }

    public void requireResource(DataResource resource) {
        if ((kind == Kind.DOCUMENT_SEGMENT) != (resource.kind() == DataResource.Kind.RETRIEVAL))
            throw new GovernanceException("data_fragment_kind_denied");
    }

    /** 独立摘要域，不能与普通实例目录键混淆。 */
    public String key(DataResource resource) {
        requireResource(resource);
        return JsonValues.digest(
                List.of(
                        document == null ? "AGT-FRAGMENT-V1" : "AGT-FRAGMENT-V2",
                        resource.descriptor(),
                        descriptor()));
    }

    public static List<DataFragment> fromTarget(Object target, DataResource resource) {
        if (!(target instanceof Map<?, ?> map))
            throw new GovernanceException("invalid_data_target");
        if (!map.containsKey("fragments")) return List.of();
        if (!(map.get("fragments") instanceof List<?> values) || values.size() > 128)
            throw new GovernanceException("data_fragment_capacity_denied");
        var result = new ArrayList<DataFragment>();
        try {
            for (var value : values) {
                if (!(value instanceof Map<?, ?> fields)
                        || !(fields.keySet().equals(Set.of("kind", "digest"))
                                || fields.keySet().equals(Set.of("kind", "digest", "document"))))
                    throw new IllegalArgumentException();
                var fragment =
                        new DataFragment(
                                Kind.valueOf((String) fields.get("kind")),
                                (String) fields.get("digest"),
                                fields.containsKey("document")
                                        ? DocumentSegment.from(fields.get("document"))
                                        : null);
                fragment.requireResource(resource);
                result.add(fragment);
            }
        } catch (GovernanceException denied) {
            throw denied;
        } catch (RuntimeException invalid) {
            throw new GovernanceException("data_fragment_identity_denied");
        }
        if (new HashSet<>(result).size() != result.size())
            throw new GovernanceException("data_fragment_identity_denied");
        return List.copyOf(result);
    }

    public static Object target(
            DataResource resource,
            DataOperation operation,
            Object value,
            List<DataFragment> fragments) {
        if (fragments.size() > 128) throw new GovernanceException("data_fragment_capacity_denied");
        var unique = fragments.stream().distinct().toList();
        if (unique.size() > 128) throw new GovernanceException("data_fragment_capacity_denied");
        unique.forEach(fragment -> fragment.requireResource(resource));
        if (unique.isEmpty()) return resource.target(operation, value);
        var target = new LinkedHashMap<>(resource.target(operation, value));
        target.put("fragments", unique.stream().map(DataFragment::descriptor).toList());
        return Collections.unmodifiableMap(target);
    }

    public static Object value(
            DataResource resource,
            DataOperation operation,
            Object target,
            List<DataFragment> expected) {
        var actual = fromTarget(target, resource);
        if (!actual.equals(expected.stream().distinct().toList()))
            throw new GovernanceException("data_fragment_transform_denied");
        var stripped = new LinkedHashMap<>((Map<?, ?>) target);
        stripped.remove("fragments");
        return resource.value(stripped, operation);
    }
}
