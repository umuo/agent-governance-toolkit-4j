package io.agt.core;

import java.util.List;

public record PolicyDecision(
    Kind kind,
    String reasonCode,
    Object replacement,
    boolean approvalRequired,
    List<String> warnings) {
  public enum Kind {
    ALLOW,
    DENY,
    TRANSFORM
  }

  public PolicyDecision {
    java.util.Objects.requireNonNull(kind);
    Identifiers.require(reasonCode);
    warnings = List.copyOf(warnings);
    warnings.forEach(Identifiers::require);
    if (approvalRequired && kind != Kind.DENY)
      throw new IllegalArgumentException("Approval needs deny");
    if (kind != Kind.TRANSFORM && replacement != null)
      throw new IllegalArgumentException("Invalid replacement");
    replacement = JsonValues.freeze(replacement);
  }

  public static PolicyDecision allow() {
    return new PolicyDecision(Kind.ALLOW, "allowed", null, false, List.of());
  }

  public static PolicyDecision deny(String code) {
    return new PolicyDecision(Kind.DENY, code, null, false, List.of());
  }

  public static PolicyDecision approval(String code) {
    return new PolicyDecision(Kind.DENY, code, null, true, List.of());
  }

  public static PolicyDecision transform(String code, Object value) {
    return new PolicyDecision(Kind.TRANSFORM, code, value, false, List.of());
  }

  @Override
  public String toString() {
    return "PolicyDecision[" + kind + ", " + reasonCode + "]";
  }
}
