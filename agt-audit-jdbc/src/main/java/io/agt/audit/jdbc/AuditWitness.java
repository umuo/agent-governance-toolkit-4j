package io.agt.audit.jdbc;

/** 必须位于独立信任/存储边界；接口由已认证的可信发布者调用，不是匿名网络 API。 */
public interface AuditWitness {
    AuditCheckpoint read(String catalogId, String streamId);

    /** 原子 CAS；只能从缺失到 genesis，或严格递增一条，不能覆盖历史与回退。 */
    boolean advance(AuditCheckpoint expected, AuditCheckpoint next);
}
