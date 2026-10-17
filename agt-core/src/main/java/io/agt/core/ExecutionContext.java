package io.agt.core;

/** 宿主提供的执行关联元数据，不携带权限，也不构成认证或授权凭证。 */
public record ExecutionContext(
        String traceId,
        String spanId,
        String parentSpanId,
        String runId,
        String parentRunId,
        String agentInstanceId) {
    public ExecutionContext {
        hex(traceId, 32);
        hex(spanId, 16);
        if (parentSpanId != null) hex(parentSpanId, 16);
        Identifiers.require(runId);
        if (parentRunId != null) Identifiers.require(parentRunId);
        Identifiers.require(agentInstanceId);
    }

    private static void hex(String value, int length) {
        if (value == null
                || !value.matches("[0-9a-f]{" + length + "}")
                || value.chars().allMatch(c -> c == '0'))
            throw new IllegalArgumentException("Invalid trace identifier");
    }
}
