package io.agt.audit.jdbc;

import io.agt.core.*;
import io.agt.core.audit.*;
import java.util.*;

final class AuditEncoding {
    private AuditEncoding() {}

    static Map<String, Object> metadata(AuditEvent event) {
        var r = event.record();
        for (String id :
                List.of(
                        r.sessionId(),
                        r.agentId(),
                        r.actionId(),
                        r.policyVersion(),
                        r.verdict(),
                        r.reasonCode(),
                        r.approvalOutcome(),
                        r.executionOutcome())) Identifiers.require(id);
        if (r.warnings().size() > 64
                || r.durationNanos() < 0
                || r.schemaVersion() < 1
                || r.schemaVersion() > 3) throw new GovernanceException("audit_record_invalid");
        r.warnings().forEach(Identifiers::require);
        var record = new LinkedHashMap<String, Object>();
        record.put("schemaVersion", r.schemaVersion());
        record.put("timestamp", r.timestamp().toString());
        record.put("sessionId", r.sessionId());
        record.put("agentId", r.agentId());
        record.put("actionId", r.actionId());
        record.put("point", r.point().wireName());
        record.put("mode", r.mode().name());
        record.put("policyVersion", r.policyVersion());
        record.put("verdict", r.verdict());
        record.put("reasonCode", r.reasonCode());
        record.put("transformApplied", r.transformApplied());
        record.put("approvalOutcome", r.approvalOutcome());
        record.put("executionOutcome", r.executionOutcome());
        record.put("inputIdentity", r.inputIdentity());
        record.put("enforcedIdentity", r.enforcedIdentity());
        record.put("durationNanos", r.durationNanos());
        record.put("warnings", r.warnings());
        record.put("dataOperation", r.dataOperation());
        record.put("resourceIdentity", r.resourceIdentity());
        var execution = r.execution();
        if (execution != null) {
            var trace = new LinkedHashMap<String, Object>();
            trace.put("traceId", execution.traceId());
            trace.put("spanId", execution.spanId());
            trace.put("parentSpanId", execution.parentSpanId());
            trace.put("runId", execution.runId());
            trace.put("parentRunId", execution.parentRunId());
            trace.put("agentInstanceId", execution.agentInstanceId());
            record.put("execution", trace);
        }
        return Map.of(
                "scope",
                Map.of(
                        "tenantId",
                        event.scope().tenantId(),
                        "principalId",
                        event.scope().principalId(),
                        "taskId",
                        event.scope().taskId(),
                        "sessionId",
                        event.scope().sessionId()),
                "record",
                record,
                "captureStatus",
                event.body().status());
    }
}
