package io.agt.core.audit;

import io.agt.core.Identifiers;

/** 由实际宿主会话绑定，不从正文中的租户/主体/trace 字段推导。 */
public record AuditScope(String tenantId, String principalId, String taskId, String sessionId) {
    public AuditScope {
        Identifiers.require(tenantId);
        Identifiers.require(principalId);
        Identifiers.require(taskId);
        Identifiers.require(sessionId);
    }

    @Override
    public String toString() {
        return "AuditScope[redacted]";
    }
}
