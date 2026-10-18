package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.execution.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** 同步登记/开始、异步回执发布的元数据端；只查询完整一致的分页快照。 */
final class RemoteExternalLedger implements ExternalExecutionLedger {
    private final GrantStatusClient client;
    private final GrantLease lease;
    private final Supplier<String> session;
    private final String taskId;

    RemoteExternalLedger(
            GrantStatusClient client, GrantLease lease, Supplier<String> session, String taskId) {
        this.client = client;
        this.lease = lease;
        this.session = session;
        this.taskId = taskId;
    }

    private void owner(String value) {
        if (!Objects.equals(value, session.get()))
            throw new GovernanceException("external_execution_owner_denied");
    }

    public void register(String id, String owner, String kind) {
        owner(owner);
        client.workRegister(lease, owner, id, kind);
    }

    public void start(String id, String owner) {
        owner(owner);
        client.workStart(lease, owner, id);
    }

    public void cancel(String id, String owner) {
        owner(owner);
        client.workCancel(lease, owner, id);
    }

    public void finish(String id, String owner, TerminationEvidence proof) {
        owner(owner);
        client.workFinish(lease, owner, id, proof);
    }

    public ExternalExecutionRegistry.Snapshot snapshot() {
        var summary = client.workStatus(lease, session.get());
        if (!taskId.equals(summary.taskId())
                || summary.records() < 0
                || summary.records() > ExternalExecutionState.MAX_RECORDS)
            throw new GovernanceException("external_execution_snapshot_denied");
        var rows = new ArrayList<ExternalExecutionState.Row>();
        while (rows.size() < summary.records()) {
            var page = client.workPage(lease, session.get(), summary.revision(), rows.size());
            if (page.revision() != summary.revision()
                    || page.offset() != rows.size()
                    || page.total() != summary.records()
                    || page.rows().isEmpty()
                    || page.rows().size() > ExternalExecutionState.PAGE_SIZE
                    || page.rows().size() > summary.records() - rows.size())
                throw new GovernanceException("external_execution_snapshot_denied");
            rows.addAll(page.rows());
        }
        // 仅验证服务端观察的切片，不拿客户端时钟重新解释权威截止时间。
        var state =
                ExternalExecutionState.restored(
                        new ExternalExecutionState.Checkpoint(
                                taskId, summary.revision(), summary.fault(), rows),
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        if (!summary.equals(state.summary()))
            throw new GovernanceException("external_execution_snapshot_denied");
        return state.snapshot();
    }
}
