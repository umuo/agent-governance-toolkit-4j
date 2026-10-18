package io.agt.core.usage;

import java.util.function.Consumer;

/** 宿主绑定的权威用量账本；实现不得在状态未知时退款或退回本机独立额度。 */
public interface UsageLedger {
    UsageLimits limits();

    void checkActive();

    ModelUsageReservation reserve(
            String owner,
            String model,
            int inputUpperBound,
            int outputUpperBound,
            Consumer<ModelUsageReservation> finished);

    UsageBudget.Snapshot snapshot();
}
