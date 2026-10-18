package io.agt.audit.jdbc;

import io.agt.core.Identifiers;

/** 独立见证端保存的单调检查点；trace 或检查点本身不授予读取权限。 */
public record AuditCheckpoint(String catalogId, String streamId, long sequence, String hash) {
    public AuditCheckpoint {
        Identifiers.require(catalogId);
        requireHash(streamId);
        requireHash(hash);
        if (sequence < 0) throw new IllegalArgumentException("Invalid audit sequence");
    }

    static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid audit hash");
    }
}
