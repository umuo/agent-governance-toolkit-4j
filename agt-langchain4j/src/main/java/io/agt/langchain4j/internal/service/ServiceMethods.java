package io.agt.langchain4j.internal.service;

import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.MemoryId;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/** 服务实例拥有的不可变方法元数据；SPI 在装配时解析，参数值与授权决定始终逐次计算。 */
public final class ServiceMethods {
    private ServiceMethods() {}

    public record Shape(
            Type resultType,
            java.util.List<Type> parameters,
            java.util.List<Boolean> trusted,
            PublisherTypes.Binding publisher) {}

    public static Map<Method, Shape> inspect(Class<?> type) {
        var registry = new PublisherTypes.Registry();
        var result = new LinkedHashMap<Method, Shape>();
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            result.put(
                    method,
                    new Shape(
                            method.getGenericReturnType(),
                            java.util.List.of(method.getGenericParameterTypes()),
                            java.util.Arrays.stream(method.getParameters())
                                    .map(
                                            p ->
                                                    p.isAnnotationPresent(MemoryId.class)
                                                            || p.getType()
                                                                    == InvocationParameters.class)
                                    .toList(),
                            registry.bind(method.getGenericReturnType())));
        }
        return Map.copyOf(result);
    }
}
