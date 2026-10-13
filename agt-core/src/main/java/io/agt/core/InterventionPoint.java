package io.agt.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum InterventionPoint {
  AGENT_STARTUP,
  INPUT,
  PRE_MODEL_CALL,
  POST_MODEL_CALL,
  PRE_TOOL_CALL,
  POST_TOOL_CALL,
  OUTPUT,
  AGENT_SHUTDOWN;

  @JsonValue
  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static InterventionPoint parse(String name) {
    return valueOf(name.toUpperCase(Locale.ROOT));
  }
}
