package io.agt.core.audit;

import io.agt.core.EnforcementMode;
import io.agt.core.ExecutionContext;
import io.agt.core.InterventionPoint;
import java.time.Instant;
import java.util.List;

/**
 * 结构化治理审计记录。
 *
 * <p>不含上下文身份原文与策略目标，只保留宿主 agent ID 和内容摘要。
 */
public record AuditRecord(
        int schemaVersion,
        Instant timestamp,
        String sessionId,
        String agentId,
        String actionId,
        InterventionPoint point,
        EnforcementMode mode,
        String policyVersion,
        String verdict,
        String reasonCode,
        boolean transformApplied,
        String approvalOutcome,
        String executionOutcome,
        String inputIdentity,
        String enforcedIdentity,
        long durationNanos,
        List<String> warnings,
        String dataOperation,
        String resourceIdentity,
        ExecutionContext execution) {
    public AuditRecord(
            int schemaVersion,
            Instant timestamp,
            String sessionId,
            String agentId,
            String actionId,
            InterventionPoint point,
            EnforcementMode mode,
            String policyVersion,
            String verdict,
            String reasonCode,
            boolean transformApplied,
            String approvalOutcome,
            String executionOutcome,
            String inputIdentity,
            String enforcedIdentity,
            long durationNanos,
            List<String> warnings,
            String dataOperation,
            String resourceIdentity) {
        this(
                schemaVersion,
                timestamp,
                sessionId,
                agentId,
                actionId,
                point,
                mode,
                policyVersion,
                verdict,
                reasonCode,
                transformApplied,
                approvalOutcome,
                executionOutcome,
                inputIdentity,
                enforcedIdentity,
                durationNanos,
                warnings,
                dataOperation,
                resourceIdentity,
                null);
    }

    public AuditRecord withExecution(ExecutionContext value) {
        return new AuditRecord(
                3,
                timestamp,
                sessionId,
                agentId,
                actionId,
                point,
                mode,
                policyVersion,
                verdict,
                reasonCode,
                transformApplied,
                approvalOutcome,
                executionOutcome,
                inputIdentity,
                enforcedIdentity,
                durationNanos,
                warnings,
                dataOperation,
                resourceIdentity,
                value);
    }

    public AuditRecord(
            int schemaVersion,
            Instant timestamp,
            String sessionId,
            String agentId,
            String actionId,
            InterventionPoint point,
            EnforcementMode mode,
            String policyVersion,
            String verdict,
            String reasonCode,
            boolean transformApplied,
            String approvalOutcome,
            String executionOutcome,
            String inputIdentity,
            String enforcedIdentity,
            long durationNanos,
            List<String> warnings) {
        this(
                schemaVersion,
                timestamp,
                sessionId,
                agentId,
                actionId,
                point,
                mode,
                policyVersion,
                verdict,
                reasonCode,
                transformApplied,
                approvalOutcome,
                executionOutcome,
                inputIdentity,
                enforcedIdentity,
                durationNanos,
                warnings,
                null,
                null);
    }
}
