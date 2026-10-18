package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.data.DataFragment;
import io.agt.core.data.DataResource;
import io.agt.core.flow.DataFlowPolicy;
import java.util.*;

/** 信息流服务只需要目的地或实例描述符；模型、工具正文与数据值不离开接收宿主。 */
final class FlowProjection {
    private FlowProjection() {}

    static boolean relevant(InterventionPoint point) {
        return point == InterventionPoint.PRE_TOOL_CALL
                || point == InterventionPoint.POST_TOOL_CALL
                || point == InterventionPoint.PRE_MODEL_CALL
                || point == InterventionPoint.POST_MODEL_CALL
                || point == InterventionPoint.PRE_DATA_ACCESS
                || point == InterventionPoint.POST_DATA_ACCESS
                || point == InterventionPoint.OUTPUT;
    }

    static Object target(
            DataFlowPolicy policy, InterventionPoint point, String tool, Object target) {
        if (point == InterventionPoint.PRE_DATA_ACCESS
                || point == InterventionPoint.POST_DATA_ACCESS) {
            var descriptor = DataResource.resource(target);
            var resource =
                    new DataResource(
                            (String) descriptor.get("name"),
                            DataResource.Kind.valueOf((String) descriptor.get("kind")),
                            (String) descriptor.get("tenantId"),
                            (String) descriptor.get("principalId"),
                            (String) descriptor.get("instanceId"));
            var projection =
                    new LinkedHashMap<String, Object>(
                            Map.of(
                                    "resource",
                                    descriptor,
                                    "operation",
                                    ((Map<?, ?>) target).get("operation"),
                                    "value",
                                    Collections.singletonList(null)));
            var fragments = DataFragment.fromTarget(target, resource);
            if (!fragments.isEmpty())
                projection.put(
                        "fragments", fragments.stream().map(DataFragment::descriptor).toList());
            return projection;
        }
        if (point == InterventionPoint.PRE_TOOL_CALL) {
            var sink = tool == null ? null : policy.sinkTools().get(tool);
            if (sink == null || sink.destinationParameter() == null) return Map.of();
            Object destination =
                    target instanceof Map<?, ?> values
                            ? values.get(sink.destinationParameter())
                            : null;
            return Collections.singletonMap(
                    sink.destinationParameter(),
                    destination instanceof String known && sink.destinations().containsKey(known)
                            ? known
                            : null);
        }
        return null;
    }

    static void require(
            DataFlowPolicy policy, InterventionPoint point, String tool, Object projection) {
        if (!relevant(point)
                || !Objects.equals(
                        JsonValues.write(projection),
                        JsonValues.write(target(policy, point, tool, projection))))
            throw new GovernanceException("delegation_flow_projection_denied");
    }
}
