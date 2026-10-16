package io.agt.langchain4j.internal.service;

import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.AiServiceStreamingEvent;
import io.agt.core.GovernanceException;
import io.agt.langchain4j.internal.message.TextProjection;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;

/**
 * 原生流事件的公开构造器投影。
 *
 * <p>保留事件类型及调用元数据，检查事件正文；不读取私有字段或假定字段序号。
 */
public final class ReactiveEventValues {
    private ReactiveEventValues() {}

    private record Shape(Constructor<?> constructor, Method payload) {}

    private static final ClassValue<Shape> SHAPES =
            new ClassValue<>() {
                @Override
                protected Shape computeValue(Class<?> type) {
                    for (var constructor : type.getConstructors()) {
                        var parameters = constructor.getParameterTypes();
                        if (parameters.length == 2 && parameters[1] == InvocationContext.class) {
                            for (var method : type.getDeclaredMethods()) {
                                if (method.getParameterCount() == 0
                                        && method.getReturnType() == parameters[0]) {
                                    return new Shape(constructor, method);
                                }
                            }
                        }
                    }
                    throw new GovernanceException("stream_event_shape_unsupported");
                }
            };

    private static Shape shape(AiServiceStreamingEvent event) {
        return SHAPES.get(event.getClass());
    }

    public static Object project(AiServiceStreamingEvent event) {
        var shape = shape(event);
        try {
            var value = new LinkedHashMap<String, Object>();
            value.put("kind", event.getClass().getName());
            value.put("value", TypedValues.project(shape.payload().invoke(event)));
            value.put(
                    "context",
                    event.invocationContext() == null
                            ? null
                            : FrameworkValues.project(event.invocationContext()));
            return value;
        } catch (ReflectiveOperationException error) {
            throw new GovernanceException("stream_event_projection_failed");
        }
    }

    public static AiServiceStreamingEvent restore(
            AiServiceStreamingEvent original, Object effective) {
        var before = TextProjection.object(project(original));
        var value = TextProjection.object(effective);
        TextProjection.sameKeys(before, value);
        TextProjection.fixed(before, value, "kind");
        var shape = shape(original);
        try {
            Object payload = shape.payload().invoke(original);
            Object restored =
                    TypedValues.restore(
                            payload, value.get("value"), shape.payload().getGenericReturnType());
            Object context =
                    original.invocationContext() == null
                            ? null
                            : FrameworkValues.restore(
                                    original.invocationContext(), value.get("context"));
            return (AiServiceStreamingEvent) shape.constructor().newInstance(restored, context);
        } catch (ReflectiveOperationException error) {
            throw new GovernanceException("stream_event_shape_mismatch");
        }
    }
}
