package io.agt.delegation;

import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.flow.DataFlowState;
import io.agt.core.usage.UsageBudget;

/** 接收端最小在线接口。远端实现必须认证传输、限制超时，未知/不可用状态不得默认为有效。 */
public interface GrantStatusClient {
    default void workRegister(GrantLease lease, String session, String executionId, String kind) {
        throw new io.agt.core.GovernanceException("external_execution_registry_unavailable");
    }

    default void workStart(GrantLease lease, String session, String executionId) {
        throw new io.agt.core.GovernanceException("external_execution_registry_unavailable");
    }

    default void workCancel(GrantLease lease, String session, String executionId) {
        throw new io.agt.core.GovernanceException("external_execution_registry_unavailable");
    }

    default void workFinish(
            GrantLease lease,
            String session,
            String executionId,
            io.agt.core.execution.TerminationEvidence evidence) {
        throw new io.agt.core.GovernanceException("external_execution_registry_unavailable");
    }

    default io.agt.core.execution.ExternalExecutionState.Summary workStatus(
            GrantLease lease, String session) {
        throw new io.agt.core.GovernanceException("external_execution_registry_unavailable");
    }

    default io.agt.core.execution.ExternalExecutionState.Page workPage(
            GrantLease lease, String session, long revision, int offset) {
        throw new io.agt.core.GovernanceException("external_execution_registry_unavailable");
    }

    GrantDocument claim(
            String grantId,
            String digest,
            String authenticatedAgent,
            String audience,
            GrantLease lease);

    void bind(GrantLease lease, String sessionId);

    void check(GrantLease lease, String sessionId);

    void commit(GrantLease lease, String sessionId, String actionId, String kind, String digest);

    void complete(GrantLease lease, String sessionId);

    default void usageReserve(
            GrantLease lease,
            String sessionId,
            String reservationId,
            String model,
            int input,
            int output) {
        throw unsupported();
    }

    default void usageDispatch(GrantLease lease, String sessionId, String reservationId) {
        throw unsupported();
    }

    default void usageSettle(
            GrantLease lease,
            String sessionId,
            String reservationId,
            String servedModel,
            Integer input,
            Integer output,
            Integer total) {
        throw unsupported();
    }

    default void usageClose(GrantLease lease, String sessionId, String reservationId) {
        throw unsupported();
    }

    default UsageBudget.Snapshot usageSnapshot(GrantLease lease, String sessionId) {
        throw unsupported();
    }

    default void guardedCommit(
            GrantLease lease,
            String sessionId,
            String actionId,
            InterventionPoint point,
            String tool,
            Object projection,
            String digest) {
        throw flowUnavailable();
    }

    default void flowValidate(
            GrantLease lease,
            String sessionId,
            InterventionPoint point,
            String tool,
            Object projection) {
        throw flowUnavailable();
    }

    default void flowComplete(GrantLease lease, String sessionId, String actionId, String tool) {
        throw flowUnavailable();
    }

    default void flowValidate(
            GrantLease lease,
            String sessionId,
            String actionId,
            InterventionPoint point,
            String tool,
            Object projection) {
        // 旧端点不能静默丢弃因果动作 ID。
        throw flowUnavailable();
    }

    default DataFlowState.Snapshot flowSnapshot(GrantLease lease, String sessionId) {
        throw flowUnavailable();
    }

    private static GovernanceException flowUnavailable() {
        return new GovernanceException("delegation_flow_unavailable");
    }

    private static GovernanceException unsupported() {
        return new GovernanceException("delegation_usage_unavailable");
    }
}
