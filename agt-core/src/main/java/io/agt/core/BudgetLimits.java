package io.agt.core;

/**
 * 会话预算与生命周期上限配置。
 *
 * <p>限制单会话内允许的模型调用次数、工具调用次数及最长运行毫秒数。
 */
public record BudgetLimits(int maxModelCalls, int maxToolCalls, long maxDurationMillis) {
    public BudgetLimits {
        if (maxModelCalls < 1
                || maxToolCalls < 0
                || maxDurationMillis < 1
                || maxDurationMillis > 86_400_000) {
            throw new IllegalArgumentException("Invalid finite budget");
        }
    }

    public static BudgetLimits defaults() {
        return new BudgetLimits(20, 50, 300_000);
    }
}
