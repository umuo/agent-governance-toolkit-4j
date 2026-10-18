package io.agt.core.flow;

import io.agt.core.*;
import io.agt.core.data.DataResource;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 单个根任务的信息流策略：资源等级、工具来源、外发目的地和工具级前置步骤。 */
public record DataFlowPolicy(
        Map<DataResource, DataLabel> resources,
        Map<String, DataLabel> sourceTools,
        Map<String, SinkRule> sinkTools,
        Set<String> neutralTools,
        DataLabel inputLabel,
        DataLabel modelClearance,
        DataLabel outputClearance) {
    public DataFlowPolicy {
        resources = Map.copyOf(resources);
        sourceTools = Map.copyOf(sourceTools);
        sinkTools = Map.copyOf(sinkTools);
        neutralTools = Set.copyOf(neutralTools);
        Objects.requireNonNull(inputLabel);
        Objects.requireNonNull(modelClearance);
        Objects.requireNonNull(outputClearance);
        sourceTools.keySet().forEach(Identifiers::require);
        sinkTools.keySet().forEach(Identifiers::require);
        neutralTools.forEach(Identifiers::require);
        if (resources.size() > 128
                || sourceTools.size() > 128
                || sinkTools.size() > 128
                || neutralTools.size() > 128) {
            throw new IllegalArgumentException("Too many data flow rules");
        }
        for (var sink : sinkTools.values()) {
            for (var step : sink.requiresCompletedTools()) {
                if (!sourceTools.containsKey(step)
                        && !sinkTools.containsKey(step)
                        && !neutralTools.contains(step)) {
                    throw new IllegalArgumentException("Unclassified prerequisite tool");
                }
            }
        }
    }

    public record SinkRule(
            DataLabel maximum,
            String destinationParameter,
            Map<String, DataLabel> destinations,
            Set<String> requiresCompletedTools) {
        public SinkRule {
            Objects.requireNonNull(maximum);
            destinations = Map.copyOf(destinations);
            requiresCompletedTools = Set.copyOf(requiresCompletedTools);
            requiresCompletedTools.forEach(Identifiers::require);
            if (destinations.size() > 128
                    || requiresCompletedTools.size() > 128
                    || (destinationParameter == null) != destinations.isEmpty()
                    || destinations.values().stream().anyMatch(level -> !maximum.permits(level))) {
                throw new IllegalArgumentException("Invalid data flow sink rule");
            }
            if (destinationParameter != null) Identifiers.require(destinationParameter);
        }

        void validate(Object arguments, DataLabel label, Set<String> completed) {
            DataLabel clearance = maximum;
            if (destinationParameter != null) {
                Object destination =
                        arguments instanceof Map<?, ?> map ? map.get(destinationParameter) : null;
                clearance = destination instanceof String key ? destinations.get(key) : null;
                if (clearance == null)
                    throw new GovernanceException("data_flow_destination_denied");
            }
            if (!clearance.permits(label)) throw new GovernanceException("data_flow_egress_denied");
            if (!completed.containsAll(requiresCompletedTools)) {
                throw new GovernanceException("data_flow_prerequisite_denied");
            }
        }
    }
}
