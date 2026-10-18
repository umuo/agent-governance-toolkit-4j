package io.agt.delegation;

import io.agt.core.execution.ExternalExecutionState;
import io.agt.core.flow.*;
import io.agt.core.usage.*;
import java.util.*;

/** 宿主存储专用的纯状态格式；不包含租约明文、签名私钥、模型正文或可执行类型信息。 */
public record RegistryCheckpoint(int format, List<Node> nodes) {
    public RegistryCheckpoint {
        nodes = List.copyOf(nodes);
        if (format != 1 || nodes.isEmpty() || nodes.size() > 10000)
            throw new IllegalArgumentException("Invalid registry checkpoint");
    }

    public record Admission(
            String kind,
            String fingerprint,
            String tool,
            boolean guarded,
            DataLabel label,
            String dataIdentity) {
        public Admission(
                String kind, String fingerprint, String tool, boolean guarded, DataLabel label) {
            this(kind, fingerprint, tool, guarded, label, null);
        }
    }

    public record Node(
            String document,
            String state,
            String secretDigest,
            String session,
            long heartbeatUntil,
            int models,
            int tools,
            int data,
            boolean retired,
            Map<String, Admission> admissions,
            Usage usage,
            DataFlowState.Checkpoint flow,
            Work work) {
        public Node(
                String document,
                String state,
                String secretDigest,
                String session,
                long heartbeatUntil,
                int models,
                int tools,
                int data,
                boolean retired,
                Map<String, Admission> admissions,
                Usage usage,
                DataFlowState.Checkpoint flow) {
            this(
                    document,
                    state,
                    secretDigest,
                    session,
                    heartbeatUntil,
                    models,
                    tools,
                    data,
                    retired,
                    admissions,
                    usage,
                    flow,
                    null);
        }

        public Node {
            admissions = Map.copyOf(admissions);
            if (admissions.size() > 100000) throw new IllegalArgumentException();
        }

        @Override
        public String toString() {
            return "RegistryNode[redacted]";
        }
    }

    public record Work(ExternalExecutionState.Checkpoint state, Map<String, String> owners) {
        public Work {
            Objects.requireNonNull(state);
            owners = Map.copyOf(owners);
            if (owners.size() > ExternalExecutionState.MAX_RECORDS)
                throw new IllegalArgumentException();
        }
    }

    public record Receipt(
            String grant,
            String id,
            String stage,
            String fingerprint,
            String settlement,
            ModelUsageReservation.Checkpoint reservation) {}

    public record Usage(
            UsageBudget.Snapshot baseline,
            UsageBudget.Snapshot balance,
            List<Receipt> receipts,
            Map<String, Long> deadlines,
            Set<String> ended,
            Map<String, Set<String>> children) {
        public Usage {
            receipts = List.copyOf(receipts);
            deadlines = Map.copyOf(deadlines);
            ended = Set.copyOf(ended);
            var copy = new HashMap<String, Set<String>>();
            children.forEach((parent, values) -> copy.put(parent, Set.copyOf(values)));
            children = Map.copyOf(copy);
            if (receipts.size() > 100000
                    || deadlines.size() > 10000
                    || ended.size() > 1000000
                    || children.size() > 1000000) throw new IllegalArgumentException();
        }

        @Override
        public String toString() {
            return "RegistryUsage[redacted]";
        }
    }

    @Override
    public String toString() {
        return "RegistryCheckpoint[redacted]";
    }
}
