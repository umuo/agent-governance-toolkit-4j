package io.agt.core;

import io.agt.core.flow.DataFlowPolicy;
import io.agt.core.usage.UsageLimits;
import java.util.Objects;

/** 根任务共享配额；活跃会话数包含根会话，深度 0 表示根。 */
public record TaskLimits(
        BudgetLimits calls,
        int maxDataCalls,
        int maxConcurrentSessions,
        int maxDelegationDepth,
        DataFlowPolicy dataFlow,
        UsageLimits usage) {
    public TaskLimits(
            BudgetLimits calls,
            int maxDataCalls,
            int maxConcurrentSessions,
            int maxDelegationDepth) {
        this(calls, maxDataCalls, maxConcurrentSessions, maxDelegationDepth, null);
    }

    public TaskLimits(
            BudgetLimits calls,
            int maxDataCalls,
            int maxConcurrentSessions,
            int maxDelegationDepth,
            DataFlowPolicy dataFlow) {
        this(calls, maxDataCalls, maxConcurrentSessions, maxDelegationDepth, dataFlow, null);
    }

    public TaskLimits {
        Objects.requireNonNull(calls);
        if (maxDataCalls < 0
                || maxConcurrentSessions < 1
                || maxConcurrentSessions > 10000
                || maxDelegationDepth < 0
                || maxDelegationDepth > 32) {
            throw new IllegalArgumentException("Invalid task limits");
        }
    }
}
