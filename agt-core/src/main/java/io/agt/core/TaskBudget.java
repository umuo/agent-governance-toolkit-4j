package io.agt.core;

import io.agt.core.delegation.DelegationQuota;
import io.agt.core.delegation.DelegationState;
import io.agt.core.execution.*;
import io.agt.core.flow.DataFlowLedger;
import io.agt.core.flow.DataFlowState;
import io.agt.core.flow.LabelStore;
import io.agt.core.usage.ModelUsageReservation;
import io.agt.core.usage.UsageBudget;
import io.agt.core.usage.UsageLedger;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 仅在根任务内部共享，不调用会话或外部回调，锁序固定为会话到任务。 */
final class TaskBudget {
    enum Kind {
        MODEL,
        TOOL,
        DATA
    }

    private final String id;
    private final TaskLimits limits;
    private final long started = System.nanoTime();
    private int models;
    private int tools;
    private int data;
    private int sessions;
    private boolean exported;
    volatile DataFlowLedger flow;
    volatile UsageLedger usage;
    private final Set<String> operations = new HashSet<>();
    private volatile Runnable exportedActive;
    private boolean viewBound;
    private ExternalExecutionRegistry external;
    private ExternalExecutionLedger externalView;
    private final Consumer<String> failure;

    TaskBudget(TaskLimits limits, Consumer<String> usageFault) {
        this(limits, usageFault, UUID.randomUUID().toString());
    }

    TaskBudget(TaskLimits limits, Consumer<String> usageFault, String taskId) {
        this(limits, usageFault, taskId, null);
    }

    TaskBudget(
            TaskLimits limits,
            Consumer<String> usageFault,
            String taskId,
            UsageLedger externalUsage) {
        this(limits, usageFault, taskId, externalUsage, null, null);
    }

    TaskBudget(
            TaskLimits limits,
            Consumer<String> usageFault,
            String taskId,
            UsageLedger externalUsage,
            DataFlowLedger externalFlow,
            LabelStore labelStore) {
        this(limits, usageFault, taskId, externalUsage, externalFlow, labelStore, null);
    }

    TaskBudget(
            TaskLimits limits,
            Consumer<String> usageFault,
            String taskId,
            UsageLedger externalUsage,
            DataFlowLedger externalFlow,
            LabelStore labelStore,
            ExternalExecutionLedger externalView) {
        id = Identifiers.require(taskId);
        failure = usageFault;
        this.externalView = externalView;
        this.limits = limits;
        if (externalFlow != null && limits.dataFlow() != null)
            throw new GovernanceException("data_flow_local_override_denied");
        if (externalFlow != null && labelStore != null)
            throw new GovernanceException("data_flow_label_store_override_denied");
        flow =
                externalFlow != null
                        ? externalFlow
                        : limits.dataFlow() == null
                                ? null
                                : new DataFlowState(limits.dataFlow(), id, labelStore);
        if (externalUsage != null && limits.usage() != null)
            throw new GovernanceException("model_usage_local_override_denied");
        usage =
                externalUsage != null
                        ? externalUsage
                        : limits.usage() == null
                                ? null
                                : new UsageBudget(limits.usage(), usageFault);
    }

    synchronized void attach(int depth) {
        if (exported) throw new GovernanceException("delegation_budget_exported_denied");
        if (remainingMillis() <= 0) throw new GovernanceException("task_expired");
        if (depth > limits.maxDelegationDepth())
            throw new GovernanceException("delegation_depth_exceeded");
        if (sessions >= limits.maxConcurrentSessions())
            throw new GovernanceException("task_session_limit");
        sessions++;
    }

    synchronized void detach() {
        sessions--;
    }

    synchronized void reserve(Kind kind) {
        if (exported) throw new GovernanceException("delegation_budget_exported_denied");
        switch (kind) {
            case MODEL -> {
                if (models >= limits.calls().maxModelCalls())
                    throw new GovernanceException("task_model_budget_exceeded");
                models++;
            }
            case TOOL -> {
                if (tools >= limits.calls().maxToolCalls())
                    throw new GovernanceException("task_tool_budget_exceeded");
                tools++;
            }
            case DATA -> {
                if (data >= limits.maxDataCalls())
                    throw new GovernanceException("task_data_budget_exceeded");
                data++;
            }
        }
    }

    synchronized void release(Kind kind, int count) {
        switch (kind) {
            case MODEL -> models -= count;
            case TOOL -> tools -= count;
            case DATA -> data -= count;
        }
    }

    long remainingMillis() {
        return limits.calls().maxDurationMillis()
                - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    String taskId() {
        return id;
    }

    synchronized HostSession.TaskSnapshot snapshot() {
        return new HostSession.TaskSnapshot(id, models, tools, data, sessions, limits);
    }

    synchronized DelegationQuota exportQuota() {
        if (exported || flow != null || usage != null)
            throw new GovernanceException("delegation_export_constraints_unsupported");
        return exportState().quota();
    }

    synchronized DelegationState exportState() {
        var externalState =
                external == null
                        ? new ExternalExecutionState.Checkpoint(id, 0, null, java.util.List.of())
                        : external.settledCheckpoint();
        if (!operations.isEmpty())
            throw new GovernanceException("delegation_operations_inflight_denied");
        if (exported
                || (flow != null && !(flow instanceof DataFlowState))
                || (usage != null && !(usage instanceof UsageBudget)))
            throw new GovernanceException("delegation_export_constraints_unsupported");
        var transferred = usage == null ? null : ((UsageBudget) usage).transferred();
        var originalFlow = (DataFlowState) flow;
        exported = true;
        exportedActive =
                () -> {
                    throw new GovernanceException("delegation_transfer_pending");
                };
        if (flow != null)
            flow =
                    new DataFlowLedger() {
                        private GovernanceException pending() {
                            return new GovernanceException("delegation_transfer_pending");
                        }

                        public void validate(PolicyRequest request) {
                            if (request.point() != InterventionPoint.AGENT_SHUTDOWN)
                                throw pending();
                        }

                        public void beforeTool(String name, Object args) {
                            throw pending();
                        }

                        public void beforeModel() {
                            throw pending();
                        }

                        public void beforeData(String id, Object target) {
                            throw pending();
                        }

                        public void completedTool(String id, String name) {
                            throw pending();
                        }

                        public DataFlowState.Snapshot snapshot() {
                            throw pending();
                        }
                    };
        return new DelegationState(
                new DelegationQuota(
                        limits.calls().maxModelCalls() - models,
                        limits.calls().maxToolCalls() - tools,
                        limits.maxDataCalls() - data,
                        limits.maxConcurrentSessions() - sessions),
                transferred,
                originalFlow,
                externalState);
    }

    synchronized void bindExportedView(
            Runnable active, DataFlowLedger flowView, UsageLedger usageView) {
        if (!exported
                || (flow != null) != (flowView != null)
                || (usage != null) != (usageView != null))
            throw new GovernanceException("delegation_transfer_denied");
        flow = flowView;
        usage = usageView;
        exportedActive = java.util.Objects.requireNonNull(active);
        viewBound = true;
    }

    void checkExportedActive() {
        var active = exportedActive;
        if (active != null) active.run();
    }

    synchronized AutoCloseable beginOperation(String id) {
        if (exported) throw new GovernanceException("delegation_budget_exported_denied");
        if (!operations.add(id)) throw new GovernanceException("operation_identity_denied");
        return () -> {
            synchronized (this) {
                operations.remove(id);
            }
        };
    }

    synchronized ModelUsageReservation reserveUsage(
            String owner,
            String model,
            int input,
            int output,
            Consumer<ModelUsageReservation> finished) {
        if (exported) throw new GovernanceException("delegation_budget_exported_denied");
        if (usage == null) throw new GovernanceException("model_usage_not_configured");
        return usage.reserve(owner, model, input, output, finished);
    }

    synchronized ExternalExecution registerExternal(
            HostSession session, String kind, ExternalCancellation cancellation) {
        if (exported) throw new GovernanceException("delegation_budget_exported_denied");
        if (external == null)
            external =
                    new ExternalExecutionRegistry(
                            id,
                            session.control().externalCancellations,
                            session.control().deadlines,
                            failure,
                            session.control().externalRegistries,
                            externalView);
        return external.register(session.sessionId(), kind, cancellation, session::checkActive);
    }

    synchronized void checkExternalActive() {
        if (external != null) external.checkActive();
    }

    synchronized void cancelExternal(String sessionId) {
        if (external != null) external.cancelOwner(sessionId);
    }

    ExternalExecutionRegistry.Snapshot externalSnapshot() {
        ExternalExecutionLedger view;
        ExternalExecutionRegistry local;
        synchronized (this) {
            view = externalView;
            local = external;
        }
        // 分页诊断可以走网络，不能持有任务锁阻止会话心跳/取消。
        if (view != null) return view.snapshot();
        return local == null
                ? new ExternalExecutionRegistry.Snapshot(
                        id, java.util.List.of(), 0, 0, 0, true, null)
                : local.snapshot();
    }

    synchronized void bindExternalView(ExternalExecutionLedger view) {
        if (!exported) throw new GovernanceException("delegation_transfer_denied");
        externalView = java.util.Objects.requireNonNull(view);
    }

    synchronized UsageBudget.Snapshot usageSnapshot() {
        if (usage == null) return null;
        if (exported && !viewBound) throw new GovernanceException("delegation_transfer_pending");
        return usage.snapshot();
    }
}
