package io.agt.core.audit;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agt.core.GovernanceException;
import java.io.BufferedWriter;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;

/** 同步确认落到 Writer，不宣称断电持久性或防篡改。一个实例可供并发会话共用。 */
public final class JsonlAuditSink implements AuditSink {
  private final BufferedWriter writer;
  private final JsonMapper mapper = new JsonMapper();
  private boolean failed;

  public JsonlAuditSink(Path path) throws java.io.IOException {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
      throw new java.io.IOException("Audit path must be a new file");
    writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      /* 非 POSIX 平台依赖宿主文件 ACL。 */
    }
  }

  @Override
  public synchronized void write(AuditRecord r) throws Exception {
    if (failed) throw new GovernanceException("audit_failed");
    try {
      var row = new LinkedHashMap<String, Object>();
      row.put("schemaVersion", r.schemaVersion());
      row.put("timestamp", r.timestamp().toString());
      row.put("sessionId", r.sessionId());
      row.put("agentId", r.agentId());
      row.put("actionId", r.actionId());
      row.put("point", r.point().wireName());
      row.put("mode", r.mode().name());
      row.put("policyVersion", r.policyVersion());
      row.put("verdict", r.verdict());
      row.put("reasonCode", r.reasonCode());
      row.put("transformApplied", r.transformApplied());
      row.put("approvalOutcome", r.approvalOutcome());
      row.put("executionOutcome", r.executionOutcome());
      row.put("inputIdentity", r.inputIdentity());
      row.put("enforcedIdentity", r.enforcedIdentity());
      row.put("durationNanos", r.durationNanos());
      row.put("warnings", r.warnings());
      writer.write(mapper.writeValueAsString(row));
      writer.newLine();
      writer.flush();
    } catch (Exception e) {
      failed = true;
      throw e;
    }
  }

  @Override
  public synchronized void close() throws java.io.IOException {
    writer.close();
  }
}
