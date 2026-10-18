package io.agt.core.policy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.agt.core.JsonValues;
import java.io.IOException;
import java.util.*;

/** 现有委托协议的策略摘要；公共化以供不同外部授权适配器复用，保持旧指纹。 */
public final class PolicyFingerprints {
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    static {
        var module = new SimpleModule();
        @SuppressWarnings("rawtypes")
        Class<Set> type = Set.class;
        module.addSerializer(
                type,
                new JsonSerializer<Set>() {
                    public void serialize(
                            Set values, JsonGenerator generator, SerializerProvider provider)
                            throws IOException {
                        var sorted = new ArrayList<Object>(values);
                        if (sorted.stream()
                                .anyMatch(
                                        value ->
                                                !(value instanceof String)
                                                        && !(value instanceof Enum<?>)))
                            throw new IOException("Unsupported policy set");
                        sorted.sort(Comparator.comparing(Object::toString));
                        generator.writeStartArray();
                        for (Object value : sorted) generator.writeObject(value);
                        generator.writeEndArray();
                    }
                });
        JSON.registerModule(module);
    }

    private PolicyFingerprints() {}

    @SuppressWarnings("unchecked")
    public static String digest(StructuredPolicy.Document policy) {
        var value = (Map<String, Object>) JSON.convertValue(policy, Object.class);
        ((Map<?, ?>) value.get("tools"))
                .values()
                .forEach(
                        tool -> {
                            var fields = (Map<?, ?>) tool;
                            if (fields.get("effect") == null) fields.remove("effect");
                            if (fields.get("binding") == null) fields.remove("binding");
                        });
        if (policy.injectionChecks() == null || policy.injectionChecks().isEmpty())
            value.remove("injectionChecks");
        var rules = (List<Map<String, Object>>) value.get("contentRules");
        for (int i = 0; i < rules.size(); i++)
            rules.get(i)
                    .put(
                            "replacements",
                            policy.contentRules().get(i).replacements().entrySet().stream()
                                    .map(entry -> List.of(entry.getKey(), entry.getValue()))
                                    .toList());
        return JsonValues.digest(value);
    }
}
