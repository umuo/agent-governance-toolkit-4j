package io.agt.core;

public final class Identifiers {
  private Identifiers() {}

  public static String require(String value) {
    if (value == null || !value.matches("[a-zA-Z0-9_.:@/-]{1,128}"))
      throw new IllegalArgumentException("Invalid governance identifier");
    return value;
  }
}
