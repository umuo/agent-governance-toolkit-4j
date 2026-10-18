package io.agt.audit.jdbc;

import io.agt.core.*;
import io.agt.core.audit.AuditBodyPolicy;
import java.util.*;

/** 内容到期后不可读取，可显式清理密文；完整性链的非正文头部继续保留。 */
public record AuditVaultPolicy(
        AuditBodyPolicy bodies,
        long bodyRetentionMillis,
        long recordRetentionMillis,
        int maxEntriesPerStream,
        long maxRetainedBytes) {
    public AuditVaultPolicy(
            AuditBodyPolicy bodies,
            long bodyRetentionMillis,
            long recordRetentionMillis,
            int maxEntriesPerStream) {
        this(bodies, bodyRetentionMillis, recordRetentionMillis, maxEntriesPerStream, 16777216L);
    }

    public AuditVaultPolicy {
        Objects.requireNonNull(bodies);
        if (bodyRetentionMillis < 1
                || recordRetentionMillis < bodyRetentionMillis
                || recordRetentionMillis > 315360000000L)
            throw new IllegalArgumentException("Invalid audit retention");
        if (maxEntriesPerStream < 1 || maxEntriesPerStream > 10000)
            throw new IllegalArgumentException("Invalid audit stream capacity");
        if (maxRetainedBytes < 131072 || maxRetainedBytes > 67108864)
            throw new IllegalArgumentException("Invalid audit retained byte capacity");
    }

    public String identity() {
        return JsonValues.digest(
                Map.of(
                        "schema",
                        "agt-audit/v1",
                        "bodies",
                        bodies.identity(),
                        "bodyRetention",
                        bodyRetentionMillis,
                        "recordRetention",
                        recordRetentionMillis,
                        "capacity",
                        maxEntriesPerStream,
                        "retainedBytes",
                        maxRetainedBytes));
    }
}
