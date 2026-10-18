package io.agt.core.audit;

import io.agt.core.*;
import java.util.Objects;

/** 正文不进入 AuditRecord；只由明确启用采集的结构化接收器处理。 */
public record AuditBody(String status, Object content) {
    public AuditBody {
        Identifiers.require(status);
        content = JsonValues.freeze(content);
        if ("captured".equals(status) != (content != null))
            throw new IllegalArgumentException("Invalid audit capture");
    }

    public static AuditBody omitted(String reason) {
        return new AuditBody(Objects.requireNonNull(reason), null);
    }

    @Override
    public String toString() {
        return "AuditBody[" + status + ",redacted]";
    }
}
