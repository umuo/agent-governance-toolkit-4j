package io.agt.core.injection;

import io.agt.core.*;
import io.agt.core.data.*;
import java.util.*;

/** 来源由治理干预点与已绑定 handle 推导；忽略内容中的 trusted/role/classification 自报。 */
public record DetectionInput(
        InterventionPoint point,
        Stage stage,
        Source source,
        String sessionId,
        String actionId,
        Object content) {
    public enum Stage {
        ORIGINAL,
        EFFECTIVE
    }

    public enum Origin {
        USER_INPUT,
        MODEL_CONTEXT,
        MODEL_RESULT,
        TOOL_ARGUMENTS,
        TOOL_RESULT,
        RETRIEVAL_RESULT,
        MEMORY_RESULT,
        DATA_INPUT,
        CALLER_OUTPUT
    }

    public enum Trust {
        UNTRUSTED,
        MODEL_GENERATED,
        MIXED
    }

    public record Source(Origin origin, Trust trust, String handle) {
        public Source {
            Objects.requireNonNull(origin);
            Objects.requireNonNull(trust);
            if (handle != null) Identifiers.require(handle);
        }
    }

    public DetectionInput {
        Objects.requireNonNull(point);
        Objects.requireNonNull(stage);
        Objects.requireNonNull(source);
        Identifiers.require(sessionId);
        Identifiers.require(actionId);
        content = JsonValues.freeze(content);
    }

    public static DetectionInput from(PolicyRequest request, Stage stage) {
        var point = request.point();
        var value = request.target();
        String handle = request.toolName();
        Origin origin;
        Trust trust;
        switch (point) {
            case INPUT -> {
                origin = Origin.USER_INPUT;
                trust = Trust.UNTRUSTED;
            }
            case PRE_MODEL_CALL -> {
                origin = Origin.MODEL_CONTEXT;
                trust = Trust.MIXED;
            }
            case POST_MODEL_CALL -> {
                origin = Origin.MODEL_RESULT;
                trust = Trust.MODEL_GENERATED;
            }
            case PRE_TOOL_CALL -> {
                origin = Origin.TOOL_ARGUMENTS;
                trust = Trust.MIXED;
            }
            case POST_TOOL_CALL -> {
                origin = Origin.TOOL_RESULT;
                trust = Trust.UNTRUSTED;
            }
            case PRE_DATA_ACCESS, POST_DATA_ACCESS -> {
                var descriptor = DataResource.resource(value);
                handle = JsonValues.digest(descriptor);
                var target = (Map<?, ?>) value;
                if (!(target.get("value") instanceof List<?> values) || values.size() != 1)
                    throw new GovernanceException("injection_source_invalid");
                value = values.get(0);
                boolean read =
                        DataOperation.RETRIEVE.name().equals(target.get("operation"))
                                || DataOperation.MEMORY_READ.name().equals(target.get("operation"));
                origin =
                        point == InterventionPoint.POST_DATA_ACCESS && read
                                ? (DataResource.Kind.RETRIEVAL.name().equals(descriptor.get("kind"))
                                        ? Origin.RETRIEVAL_RESULT
                                        : Origin.MEMORY_RESULT)
                                : Origin.DATA_INPUT;
                trust = origin == Origin.DATA_INPUT ? Trust.MIXED : Trust.UNTRUSTED;
            }
            case OUTPUT -> {
                origin = Origin.CALLER_OUTPUT;
                trust = Trust.MIXED;
            }
            default -> throw new GovernanceException("injection_point_unsupported");
        }
        return new DetectionInput(
                point,
                stage,
                new Source(origin, trust, handle),
                request.sessionId(),
                request.actionId(),
                value);
    }

    @Override
    public String toString() {
        return "DetectionInput[" + point + "," + stage + ",redacted]";
    }
}
