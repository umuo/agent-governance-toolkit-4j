package io.agt.core;

import java.util.Objects;

public record PolicyRequest(
    InterventionPoint point,
    TrustedContext context,
    String sessionId,
    String actionId,
    String toolName,
    Object target) {
  public PolicyRequest {
    Objects.requireNonNull(point);
    Objects.requireNonNull(context);
    Identifiers.require(sessionId);
    Identifiers.require(actionId);
    if (toolName != null) Identifiers.require(toolName);
    target = JsonValues.freeze(target);
  }

  public PolicyRequest withTarget(Object replacement) {
    return new PolicyRequest(point, context, sessionId, actionId, toolName, replacement);
  }

  @Override
  public String toString() {
    return "PolicyRequest[" + point + ", redacted]";
  }
}
