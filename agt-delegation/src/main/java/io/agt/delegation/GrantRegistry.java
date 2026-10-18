package io.agt.delegation;

import io.agt.core.GovernanceException;
import io.agt.core.execution.ExternalExecutionState;
import io.agt.core.flow.DataFlowState;
import io.agt.core.usage.UsageBudget;

/** 只有授权服务持有写入/签发接口；不要把注册根授权暴露给普通接收端。 */
public interface GrantRegistry extends GrantStatusClient {
    default void registerRoot(GrantDocument root, GrantLease lease, String sessionId) {
        registerRoot(root, lease, sessionId, null);
    }

    default void registerRoot(
            GrantDocument root, GrantLease lease, String sessionId, UsageBudget usage) {
        registerRoot(root, lease, sessionId, usage, null);
    }

    void registerRoot(
            GrantDocument root,
            GrantLease lease,
            String sessionId,
            UsageBudget usage,
            DataFlowState flow);

    GrantDocument parent(GrantLease lease);

    default void registerRoot(
            GrantDocument root,
            GrantLease lease,
            String sessionId,
            UsageBudget usage,
            DataFlowState flow,
            ExternalExecutionState.Checkpoint external) {
        if (external != null && !external.rows().isEmpty())
            throw new GovernanceException("external_execution_delegation_unsupported");
        registerRoot(root, lease, sessionId, usage, flow);
    }

    void issue(GrantDocument child, GrantLease parent);

    void heartbeatRoot(GrantLease lease);

    void revoke(String grantId);

    /** 仅服务端调用：从已认证宿主身份核对租约所属接收方，包含终止后的结算清理。 */
    void authorizeCaller(GrantLease lease, String authenticatedAgent);
}
