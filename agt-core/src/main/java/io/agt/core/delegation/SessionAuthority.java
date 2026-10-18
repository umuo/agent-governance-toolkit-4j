package io.agt.core.delegation;

import io.agt.core.*;
import io.agt.core.data.DataLimits;
import io.agt.core.execution.ExternalExecutionLedger;
import io.agt.core.flow.DataFlowLedger;
import io.agt.core.usage.UsageLedger;
import java.util.List;
import java.util.function.Consumer;

/** 宿主提供的外部授权租约；受控操作在检查、提交和交付前持续验证，状态服务失败必须拒绝。 每个外部授权绑定一个会话，继续派生需要新的外部授权，不能直接创建未注册的本机子会话。 */
public interface SessionAuthority extends AutoCloseable {
    TrustedContext identity();

    String taskId();

    int remainingDelegationDepth();

    long remainingMillis();

    List<AuthorityFrame> frames();

    default UsageLedger usageLedger() {
        return null;
    }

    default ExternalExecutionLedger externalExecutionLedger() {
        return null;
    }

    default DataFlowLedger dataFlowLedger() {
        return null;
    }

    void validateRuntime(
            PolicyEvaluator policy,
            BudgetLimits budget,
            DataLimits dataLimits,
            EnforcementMode mode);

    void validate(PolicyRequest request);

    void attach(String sessionId, Consumer<String> terminate);

    void checkActive();

    void commit(String actionId, InterventionPoint point, String toolName, Object effective);

    default void commit(io.agt.core.PolicyRequest request) {
        if (request.toolBinding() != null)
            throw new io.agt.core.GovernanceException("delegation_tool_binding_unsupported");
        commit(request.actionId(), request.point(), request.toolName(), request.target());
    }

    @Override
    void close();
}
