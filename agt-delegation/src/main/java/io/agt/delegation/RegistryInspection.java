package io.agt.delegation;

import io.agt.core.execution.ExternalExecutionRegistry;
import io.agt.core.flow.DataFlowState;
import io.agt.core.usage.UsageBudget;

/** 仅宿主已认证审计入口使用，不经接收端状态协议暴露；调用次数表示交接后的准入。 */
public record RegistryInspection(
        String rootId,
        String taskId,
        String tenantId,
        String principalId,
        boolean active,
        long expiresAt,
        int modelCalls,
        int toolCalls,
        int dataCalls,
        UsageBudget.Snapshot usage,
        DataFlowState.Snapshot flow,
        ExternalExecutionRegistry.Snapshot external) {}
