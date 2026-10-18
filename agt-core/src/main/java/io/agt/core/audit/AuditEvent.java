package io.agt.core.audit;

import io.agt.core.*;
import java.util.*;

/** 同一事件重试须保留 eventId；租户主体来自宿主会话，不能用 traceId 代替授权。 */
public record AuditEvent(String eventId, AuditScope scope, AuditRecord record, AuditBody body) {
    public AuditEvent {
        Identifiers.require(eventId);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(record);
        Objects.requireNonNull(body);
        if (!scope.sessionId().equals(record.sessionId()))
            throw new IllegalArgumentException("Audit scope mismatch");
    }

    @Override
    public String toString() {
        return "AuditEvent[redacted]";
    }
}
