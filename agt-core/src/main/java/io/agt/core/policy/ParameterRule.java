package io.agt.core.policy;

import io.agt.core.*;
import java.math.BigDecimal;
import java.util.*;

/** 闭合参数规则，不是完整 JSON Schema。所有 object 均拒绝未知字段。 */
public record ParameterRule(
    Type type,
    Map<String, ParameterRule> properties,
    Set<String> required,
    List<Object> enumValues,
    BigDecimal minimum,
    BigDecimal maximum,
    Integer minLength,
    Integer maxLength,
    String equalsContext,
    ParameterRule items) {
  public enum Type {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    OBJECT,
    ARRAY
  }

  public ParameterRule {
    Objects.requireNonNull(type);
    properties = properties == null ? Map.of() : Map.copyOf(properties);
    required = required == null ? Set.of() : Set.copyOf(required);
    enumValues =
        enumValues == null ? List.of() : enumValues.stream().map(JsonValues::freeze).toList();
    if (!properties.keySet().containsAll(required))
      throw new IllegalArgumentException("Unknown required property");
    if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0)
      throw new IllegalArgumentException("Invalid numeric range");
    if ((minLength != null && minLength < 0)
        || (maxLength != null && maxLength < 0)
        || (minLength != null && maxLength != null && minLength > maxLength))
      throw new IllegalArgumentException("Invalid length range");
    if (equalsContext != null
        && !Set.of("tenantId", "principalId", "agentId").contains(equalsContext))
      throw new IllegalArgumentException("Unknown context field");
    if (type != Type.OBJECT && (!properties.isEmpty() || !required.isEmpty()))
      throw new IllegalArgumentException("Properties require object");
    if (type != Type.ARRAY && items != null)
      throw new IllegalArgumentException("Items require array");
    if (type == Type.ARRAY && items == null)
      throw new IllegalArgumentException("Array needs items");
    if (type != Type.STRING && equalsContext != null)
      throw new IllegalArgumentException("Context binding needs string");
    if (type != Type.STRING && type != Type.ARRAY && (minLength != null || maxLength != null))
      throw new IllegalArgumentException("Invalid length constraint");
    if (type != Type.INTEGER && type != Type.NUMBER && (minimum != null || maximum != null))
      throw new IllegalArgumentException("Invalid numeric constraint");
  }

  public static ParameterRule string(String contextField) {
    return new ParameterRule(
        Type.STRING, null, null, null, null, null, null, 4096, contextField, null);
  }

  public static ParameterRule object(Map<String, ParameterRule> properties, String... required) {
    return new ParameterRule(
        Type.OBJECT, properties, Set.of(required), null, null, null, null, null, null, null);
  }

  public void validate(Object value, TrustedContext context) {
    boolean valid =
        switch (type) {
          case STRING -> value instanceof String;
          case INTEGER -> value instanceof Number n && decimal(n).stripTrailingZeros().scale() <= 0;
          case NUMBER -> value instanceof Number;
          case BOOLEAN -> value instanceof Boolean;
          case OBJECT -> value instanceof Map<?, ?>;
          case ARRAY -> value instanceof List<?>;
        };
    if (!valid) fail();
    if (!enumValues.isEmpty() && enumValues.stream().noneMatch(v -> equivalent(v, value))) fail();
    if (value instanceof Number n) {
      BigDecimal d = decimal(n);
      if ((minimum != null && d.compareTo(minimum) < 0)
          || (maximum != null && d.compareTo(maximum) > 0)) fail();
    }
    if (value instanceof String s) {
      length(s.codePointCount(0, s.length()));
      if (equalsContext != null) {
        String expected =
            switch (equalsContext) {
              case "tenantId" -> context.tenantId();
              case "principalId" -> context.principalId();
              case "agentId" -> context.agentId();
              default -> throw new IllegalStateException();
            };
        if (!s.equals(expected)) throw new GovernanceException("context_binding_denied");
      }
    }
    if (value instanceof Map<?, ?> map) {
      if (!properties.keySet().containsAll(map.keySet()) || !map.keySet().containsAll(required))
        fail();
      map.forEach((key, item) -> properties.get(key).validate(item, context));
    }
    if (value instanceof List<?> list) {
      length(list.size());
      list.forEach(v -> items.validate(v, context));
    }
  }

  private void length(int n) {
    if ((minLength != null && n < minLength) || (maxLength != null && n > maxLength)) fail();
  }

  private static boolean equivalent(Object a, Object b) {
    if (a instanceof Number x && b instanceof Number y)
      return decimal(x).compareTo(decimal(y)) == 0;
    return Objects.equals(a, b);
  }

  private static BigDecimal decimal(Number n) {
    return new BigDecimal(n.toString());
  }

  private static void fail() {
    throw new GovernanceException("tool_arguments_denied");
  }
}
