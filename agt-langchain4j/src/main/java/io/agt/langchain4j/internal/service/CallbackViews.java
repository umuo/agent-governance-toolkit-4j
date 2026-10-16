package io.agt.langchain4j.internal.service;

import dev.langchain4j.observability.api.event.AiServiceEvent;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * 原生回调的独立披露边界。
 *
 * <p>在调用用户代码前完成检查和副本构建，不把读取事件的时机变成未检查原文旁路。
 */
public final class CallbackViews {
    private CallbackViews() {}

    public static Object checked(HostSession session, Object value, Type type) {
        if (value == null) {
            return null;
        }
        return session.disclose(
                value,
                TypedValues::project,
                (original, effective) -> TypedValues.restore(original, effective, type));
    }

    public static AiServiceEvent event(HostSession session, AiServiceEvent original) {
        Class<?> type = original.eventClass();
        Map<Method, Object> values = new HashMap<>();
        try {
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0
                        && !method.getName().equals("toBuilder")
                        && !method.getName().equals("eventClass")
                        && method.getDeclaringClass() != Object.class) {
                    Object value = method.invoke(original);
                    try {
                        values.put(method, checked(session, value, method.getGenericReturnType()));
                    } catch (GovernanceException error) {
                        throw new GovernanceException(
                                "event_" + method.getName() + "_" + error.code());
                    }
                }
            }
        } catch (GovernanceException error) {
            throw error;
        } catch (ReflectiveOperationException error) {
            throw new GovernanceException("event_projection_failed");
        }
        return (AiServiceEvent)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "toString" -> "CheckedEvent[" + type.getSimpleName() + "]";
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == args[0];
                                    default ->
                                            throw new GovernanceException(
                                                    "event_method_unsupported");
                                };
                            }
                            if (method.getName().equals("eventClass")) {
                                return type;
                            }
                            if (method.getName().equals("toBuilder") && method.isDefault()) {
                                return InvocationHandler.invokeDefault(proxy, method, args);
                            }
                            return values.get(method);
                        });
    }
}
