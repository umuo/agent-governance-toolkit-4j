package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.execution.*;
import java.time.Clock;
import java.util.*;

/** 仅权威元数据，所有实际取消回调留在执行节点。 */
final class GrantExternalWork {
    final ExternalExecutionState state;
    private final Map<String, String> owners = new HashMap<>();
    private final Map<String, Set<String>> byGrant = new HashMap<>();

    GrantExternalWork(
            String taskId, String root, ExternalExecutionState.Checkpoint baseline, Clock clock) {
        state =
                baseline == null
                        ? new ExternalExecutionState(taskId, clock)
                        : ExternalExecutionState.restored(baseline, clock);
        if (!state.summary().taskId().equals(taskId))
            throw new GovernanceException("external_execution_checkpoint_denied");
        for (var row : state.checkpoint().rows()) bind(row.entry().executionId(), root);
    }

    private GrantExternalWork(RegistryCheckpoint.Work checkpoint, Clock clock) {
        state = ExternalExecutionState.restored(checkpoint.state(), clock);
        owners.putAll(checkpoint.owners());
        var ids = new HashSet<String>();
        for (var row : checkpoint.state().rows()) ids.add(row.entry().executionId());
        if (!ids.equals(owners.keySet()))
            throw new GovernanceException("external_execution_checkpoint_denied");
        owners.values().forEach(Identifiers::require);
        owners.forEach(
                (id, grant) -> byGrant.computeIfAbsent(grant, ignored -> new HashSet<>()).add(id));
    }

    static GrantExternalWork restored(RegistryCheckpoint.Work checkpoint, Clock clock) {
        return new GrantExternalWork(checkpoint, clock);
    }

    RegistryCheckpoint.Work checkpoint() {
        return new RegistryCheckpoint.Work(state.checkpoint(), owners);
    }

    void register(String grant, String session, String id, String kind) {
        var previous = owners.get(id);
        if (previous != null && !previous.equals(grant))
            throw new GovernanceException("external_execution_owner_denied");
        state.register(id, session, kind);
        bind(id, grant);
    }

    void own(String grant, String id) {
        if (!grant.equals(owners.get(id)))
            throw new GovernanceException("external_execution_owner_denied");
    }

    void validateOwners(String rootId, java.util.function.Function<String, String> sessions) {
        for (var row : state.checkpoint().rows()) {
            String grant = owners.get(row.entry().executionId());
            if (grant.equals(rootId)) continue; // 交接的已结束本机子会话历史仍归源根授权保管。
            String session = sessions.apply(grant);
            var phase = row.entry().state();
            if (session == null
                    && (phase == ExternalExecutionRegistry.State.PREPARED
                            || phase == ExternalExecutionRegistry.State.RUNNING
                            || phase == ExternalExecutionRegistry.State.CANCEL_REQUESTED))
                throw new GovernanceException("external_execution_checkpoint_denied");
            if (session != null && !session.equals(row.entry().sessionId()))
                throw new GovernanceException("external_execution_checkpoint_denied");
        }
    }

    void end(String grant) {
        for (String id : byGrant.getOrDefault(grant, Set.of()))
            state.cancel(id, state.row(id).entry().sessionId());
    }

    private void bind(String id, String grant) {
        owners.put(id, grant);
        byGrant.computeIfAbsent(grant, ignored -> new HashSet<>()).add(id);
    }

    void endAll() {
        for (var row : state.checkpoint().rows())
            state.cancel(row.entry().executionId(), row.entry().sessionId());
    }
}
