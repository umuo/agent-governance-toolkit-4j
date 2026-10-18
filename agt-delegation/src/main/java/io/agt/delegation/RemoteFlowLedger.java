package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.flow.*;
import java.util.Objects;
import java.util.function.*;

/** 只传输等级判定必需的元数据，动态状态全部留在权威服务。 */
final class RemoteFlowLedger implements DataFlowLedger {
    private final DataFlowPolicy policy;
    private final GrantStatusClient registry;
    private final GrantLease lease;
    private final Supplier<String> session;
    private final Runnable active;

    RemoteFlowLedger(
            DataFlowPolicy policy,
            GrantStatusClient registry,
            GrantLease lease,
            Supplier<String> session,
            Runnable active) {
        this.policy = policy;
        this.registry = registry;
        this.lease = lease;
        this.session = session;
        this.active = active;
    }

    Object projection(InterventionPoint point, String tool, Object value) {
        return FlowProjection.target(policy, point, tool, value);
    }

    private <T> T query(Supplier<T> query) {
        try {
            return query.get();
        } catch (GovernanceException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new GovernanceException("delegation_registry_unavailable");
        }
    }

    public void validate(PolicyRequest request) {
        if (!FlowProjection.relevant(request.point())) return;
        active.run();
        query(
                () -> {
                    var projected =
                            projection(request.point(), request.toolName(), request.target());
                    if (request.point() == InterventionPoint.POST_DATA_ACCESS
                            && projected instanceof java.util.Map<?, ?> fields
                            && fields.containsKey("fragments"))
                        registry.flowValidate(
                                lease,
                                session.get(),
                                request.actionId(),
                                request.point(),
                                request.toolName(),
                                projected);
                    else
                        registry.flowValidate(
                                lease,
                                session.get(),
                                request.point(),
                                request.toolName(),
                                projected);
                    return null;
                });
    }

    private void check(InterventionPoint point, String tool, Object target) {
        active.run();
        query(
                () -> {
                    registry.flowValidate(
                            lease, session.get(), point, tool, projection(point, tool, target));
                    return null;
                });
    }

    // 真正提交及来源登记在 guardedCommit 中原子完成；这里在 native 执行前再次检查最新等级。
    public void beforeTool(String name, Object arguments) {
        check(InterventionPoint.PRE_TOOL_CALL, name, arguments);
    }

    public void beforeModel() {
        check(InterventionPoint.PRE_MODEL_CALL, null, null);
    }

    public void beforeData(String action, Object target) {
        check(InterventionPoint.PRE_DATA_ACCESS, null, target);
    }

    public void completedTool(String action, String name) {
        active.run();
        query(
                () -> {
                    registry.flowComplete(lease, session.get(), action, name);
                    return null;
                });
    }

    public DataFlowState.Snapshot snapshot() {
        var snapshot = query(() -> registry.flowSnapshot(lease, session.get()));
        var steps =
                policy.sinkTools().values().stream()
                        .flatMap(rule -> rule.requiresCompletedTools().stream())
                        .toList();
        if (!snapshot.label().permits(policy.inputLabel())
                || !steps.containsAll(snapshot.completedTools()))
            throw new GovernanceException("delegation_document_denied");
        return Objects.requireNonNull(snapshot);
    }
}
