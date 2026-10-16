package io.agt.langchain4j.internal.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.internal.Json;
import io.agt.core.GovernanceException;
import io.agt.core.JsonValues;
import io.agt.langchain4j.internal.message.TextProjection;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * 原生声明类型和有界策略值之间的转换。
 *
 * <p>复用框架 JSON codec，恢复后再比较投影，拒绝隐式强转、未知属性丢失及数值截断。
 */
public final class TypedValues {
    private TypedValues() {}

    public static Object project(Object value) {
        try {
            return JsonValues.freeze(snapshot(value, 0));
        } catch (GovernanceException error) {
            throw error;
        } catch (RuntimeException | StackOverflowError error) {
            throw new GovernanceException("typed_value_unsupported");
        }
    }

    private static Object snapshot(Object value, int depth) {
        if (depth > 32) {
            throw new GovernanceException("payload_limit");
        }
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof java.time.Duration duration) return duration.toString();
        if (FrameworkValues.supports(value)) {
            return FrameworkValues.project(value);
        }
        if (value instanceof ChatMessage message) {
            return TextProjection.message(message);
        }
        if (value instanceof dev.langchain4j.service.Result<?> result) {
            return ServiceResultProjection.project(result);
        }
        if (value instanceof dev.langchain4j.data.message.Content content) {
            return MediaValues.project(List.of(content)).get(0);
        }
        if (value instanceof dev.langchain4j.data.image.Image image) {
            return MediaValues.image(image);
        }
        if (value instanceof Optional<?> optional) {
            return snapshot(optional.orElse(null), depth + 1);
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 4096) {
                throw new GovernanceException("payload_limit");
            }
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    // 非字符串键交给框架 codec，再由声明类型恢复和往返验证约束。
                    return JsonValues.parse(Json.toJson(value));
                }
                copy.put(key, snapshot(entry.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            if (collection.size() > 4096) {
                throw new GovernanceException("payload_limit");
            }
            var copy = new ArrayList<Object>();
            for (Object item : collection) {
                copy.add(snapshot(item, depth + 1));
            }
            return copy;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            if (length > 4096) {
                throw new GovernanceException("payload_limit");
            }
            var copy = new ArrayList<Object>();
            for (int i = 0; i < length; i++) {
                copy.add(snapshot(Array.get(value, i), depth + 1));
            }
            return copy;
        }
        return JsonValues.parse(Json.toJson(value));
    }

    public static Object restore(Object original, Object effective, Type type) {
        try {
            Object frozen = JsonValues.freeze(effective);
            Object result = recover(original, frozen, type);
            if (!equivalent(project(result), frozen)) {
                throw new GovernanceException("typed_value_shape_mismatch");
            }
            return result;
        } catch (GovernanceException error) {
            throw error;
        } catch (RuntimeException | StackOverflowError error) {
            throw new GovernanceException("typed_value_shape_mismatch");
        }
    }

    private static Object recover(Object original, Object effective, Type type) {
        if (original instanceof java.time.Duration)
            return java.time.Duration.parse(TextProjection.requiredString(effective));
        if (FrameworkValues.supports(original)) {
            return FrameworkValues.restore(original, effective);
        }
        if (original instanceof ChatMessage message) {
            return TextProjection.replace(message, effective);
        }
        if (original instanceof dev.langchain4j.service.Result<?> result) {
            return ServiceResultProjection.restore(result, effective, argument(type, 0));
        }
        if (original instanceof dev.langchain4j.data.message.Content content) {
            return MediaValues.restore(List.of(content), List.of(effective)).get(0);
        }
        if (original instanceof dev.langchain4j.data.image.Image image) {
            return MediaValues.restoreImage(image, effective);
        }
        Class<?> raw = raw(type);
        if (Optional.class == raw || original instanceof Optional<?>) {
            var prior = original instanceof Optional<?> optional ? optional.orElse(null) : null;
            return Optional.ofNullable(restore(prior, effective, argument(type, 0)));
        }
        if (effective == null) {
            if (raw.isPrimitive()) {
                throw new GovernanceException("typed_value_shape_mismatch");
            }
            return null;
        }
        if (Collection.class.isAssignableFrom(raw) || original instanceof Collection<?>) {
            var values = TextProjection.list(effective);
            var prior = original instanceof Collection<?> c ? new ArrayList<>(c) : List.of();
            Collection<Object> result =
                    Set.class.isAssignableFrom(raw) ? new LinkedHashSet<>() : new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                result.add(
                        restore(
                                i < prior.size() ? prior.get(i) : null,
                                values.get(i),
                                argument(type, 0)));
            }
            if (raw.isInterface() || raw == Object.class || raw.isInstance(result)) {
                return result;
            }
        }
        if ((Map.class.isAssignableFrom(raw) || original instanceof Map<?, ?>)
                && (argument(type, 0) == String.class || argument(type, 0) == Object.class)) {
            var values = TextProjection.object(effective);
            var prior = original instanceof Map<?, ?> map ? map : Map.of();
            var result = new LinkedHashMap<String, Object>();
            values.forEach(
                    (key, item) ->
                            result.put(key, restore(prior.get(key), item, argument(type, 1))));
            if (raw.isInterface() || raw == Object.class || raw.isInstance(result)) {
                return result;
            }
        }
        if (raw.isArray()) {
            var values = TextProjection.list(effective);
            Object array = Array.newInstance(raw.getComponentType(), values.size());
            for (int i = 0; i < values.size(); i++) {
                Object prior =
                        original != null && i < Array.getLength(original)
                                ? Array.get(original, i)
                                : null;
                Array.set(array, i, restore(prior, values.get(i), raw.getComponentType()));
            }
            return array;
        }
        Type actual = raw == Object.class && original != null ? original.getClass() : type;
        return Json.fromJson(JsonValues.write(effective), actual);
    }

    public static boolean equivalent(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            return a.keySet().equals(b.keySet())
                    && a.keySet().stream().allMatch(k -> equivalent(a.get(k), b.get(k)));
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!equivalent(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return java.util.Objects.equals(left, right);
    }

    public static Type resultType(Type type) {
        return CompletionStage.class.isAssignableFrom(raw(type)) ? argument(type, 0) : type;
    }

    public static Type argument(Type type, int index) {
        return type instanceof ParameterizedType generic
                ? generic.getActualTypeArguments()[index]
                : Object.class;
    }

    public static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p) {
            return raw(p.getRawType());
        }
        if (type instanceof GenericArrayType a) {
            return Array.newInstance(raw(a.getGenericComponentType()), 0).getClass();
        }
        throw new GovernanceException("unresolved_service_type");
    }

    public static void validate(Type type) {
        if (type instanceof TypeVariable<?> || type instanceof WildcardType) {
            throw new GovernanceException("unresolved_service_type");
        }
        if (type instanceof ParameterizedType parameterized) {
            for (Type item : parameterized.getActualTypeArguments()) {
                validate(item);
            }
        } else if (type instanceof GenericArrayType array) {
            validate(array.getGenericComponentType());
        }
    }
}
