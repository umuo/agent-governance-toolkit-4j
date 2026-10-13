package io.agt.core;

public record BudgetLimits(int maxModelCalls, int maxToolCalls, long maxDurationMillis) {
  public BudgetLimits {
    if (maxModelCalls < 1
        || maxToolCalls < 0
        || maxDurationMillis < 1
        || maxDurationMillis > 86_400_000)
      throw new IllegalArgumentException("Invalid finite budget");
  }

  public static BudgetLimits defaults() {
    return new BudgetLimits(20, 50, 300_000);
  }
}
