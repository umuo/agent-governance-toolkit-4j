package io.agt.core.delegation;

import io.agt.core.execution.ExternalExecutionState;
import io.agt.core.flow.DataFlowState;
import io.agt.core.usage.UsageBudget;
import java.util.Objects;

/** 仅在授权服务本机传递的交接状态；usage 为已结算快照派生的权威账本，不放入模型上下文。 */
public record DelegationState(
        DelegationQuota quota,
        UsageBudget usage,
        DataFlowState flow,
        ExternalExecutionState.Checkpoint external) {
    public DelegationState(DelegationQuota quota, UsageBudget usage, DataFlowState flow) {
        this(quota, usage, flow, null);
    }

    public DelegationState(DelegationQuota quota, UsageBudget usage) {
        this(quota, usage, null);
    }

    public DelegationState {
        Objects.requireNonNull(quota);
    }

    @Override
    public String toString() {
        return "DelegationState[redacted]";
    }
}
