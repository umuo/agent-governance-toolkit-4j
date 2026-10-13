package io.agt.core.audit;

@FunctionalInterface
public interface AuditSink extends AutoCloseable {
  void write(AuditRecord record) throws Exception;

  @Override
  default void close() throws Exception {}
}
