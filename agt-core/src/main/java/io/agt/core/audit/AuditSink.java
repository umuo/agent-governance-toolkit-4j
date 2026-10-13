package io.agt.core.audit;

/**
 * 审计事件接收接口。
 *
 * <p>由宿主实现，用于将治理审计记录写入文件系统、日志框架或外部审计队列。
 */
@FunctionalInterface
public interface AuditSink extends AutoCloseable {
    void write(AuditRecord record) throws Exception;

    @Override
    default void close() throws Exception {}
}
