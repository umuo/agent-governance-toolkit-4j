package io.agt.core.delegation;

/** 导出给单一权威协调器的剩余调用配额，不是可在多个远端各自复制的本地额度。 */
public record DelegationQuota(int modelCalls, int toolCalls, int dataCalls, int sessionSlots) {
    public DelegationQuota {
        if (modelCalls < 0
                || toolCalls < 0
                || dataCalls < 0
                || sessionSlots < 0
                || sessionSlots > 10000)
            throw new IllegalArgumentException("Invalid delegation quota");
    }
}
