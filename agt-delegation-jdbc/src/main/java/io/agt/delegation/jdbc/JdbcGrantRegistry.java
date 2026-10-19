package io.agt.delegation.jdbc;

import io.agt.core.*;
import io.agt.core.execution.*;
import io.agt.core.flow.*;
import io.agt.core.usage.UsageBudget;
import io.agt.delegation.*;
import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;

/** 每根任务事务化检查点；跨实例由数据库行锁协调，不持有进程内权威余额缓存。 */
public final class JdbcGrantRegistry implements GrantRegistry {
    public static final int DEFAULT_MAXIMUM_BYTES = 67_108_864;
    private final DataSource source;
    private final String authorityName;
    private final LabelStore catalog;
    private final Clock clock;
    private final int timeout;
    private final CheckpointCodec codec;

    public JdbcGrantRegistry(
            DataSource source,
            String authorityName,
            Clock clock,
            LabelStore catalog,
            int statementTimeoutSeconds,
            int maximumCheckpointBytes) {
        this.source = Objects.requireNonNull(source);
        this.authorityName = Identifiers.require(authorityName);
        this.clock = Objects.requireNonNull(clock);
        this.catalog = catalog;
        if (statementTimeoutSeconds < 1 || statementTimeoutSeconds > 30)
            throw new IllegalArgumentException("Invalid SQL timeout");
        timeout = statementTimeoutSeconds;
        codec = new CheckpointCodec(maximumCheckpointBytes);
    }

    public void initializeSchema() {
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_authority_root (authority_name VARCHAR(128) NOT NULL, root_id VARCHAR(128) NOT NULL, task_id VARCHAR(128) NOT NULL, revision BIGINT NOT NULL CHECK (revision>0), last_seen BIGINT NOT NULL, format_version INTEGER NOT NULL, blocked BOOLEAN NOT NULL DEFAULT FALSE, state_blob BLOB NOT NULL, checksum VARCHAR(80) NOT NULL, PRIMARY KEY(authority_name,root_id), UNIQUE(authority_name,task_id))");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_authority_member (authority_name VARCHAR(128) NOT NULL, grant_id VARCHAR(128) NOT NULL, root_id VARCHAR(128) NOT NULL, PRIMARY KEY(authority_name,grant_id), FOREIGN KEY(authority_name,root_id) REFERENCES agt_authority_root(authority_name,root_id))");
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    private PreparedStatement statement(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(timeout);
        return statement;
    }

    private String rootId(Connection connection, String id) throws SQLException {
        try (var query =
                statement(
                        connection,
                        "SELECT root_id FROM agt_authority_member WHERE authority_name=? AND grant_id=?")) {
            query.setString(1, authorityName);
            query.setString(2, Identifiers.require(id));
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new GovernanceException("delegation_unknown_denied");
                return Identifiers.require(rows.getString(1));
            }
        }
    }

    private byte[] body(ResultSet rows) throws SQLException {
        try (var input = rows.getBinaryStream("state_blob")) {
            if (input == null) throw denied();
            var bytes = input.readNBytes(codec.maximumBytes() + 1);
            if (bytes.length > codec.maximumBytes()) throw denied();
            return bytes;
        } catch (IOException invalid) {
            throw denied();
        }
    }

    private static final class TransactionClock extends Clock {
        final long now;

        TransactionClock(long now) {
            if (now < 0) throw denied();
            this.now = now;
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        public long millis() {
            return now;
        }
    }

    private GrantDocument rootDocument(RegistryCheckpoint checkpoint, String rootId) {
        GrantDocument root = null;
        for (var node : checkpoint.nodes()) {
            var doc = GrantCodec.decode(node.document());
            if (doc.parentId() == null) {
                if (root != null) throw denied();
                root = doc;
            }
        }
        if (root == null || !root.id().equals(rootId)) throw denied();
        return root;
    }

    private void members(Connection connection, RegistryCheckpoint checkpoint, String rootId)
            throws SQLException {
        for (var node : checkpoint.nodes()) {
            var doc = GrantCodec.decode(node.document());
            try (var query =
                    statement(
                            connection,
                            "SELECT root_id FROM agt_authority_member WHERE authority_name=? AND grant_id=?")) {
                query.setString(1, authorityName);
                query.setString(2, doc.id());
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        if (!rootId.equals(rows.getString(1))) throw denied();
                        continue;
                    }
                }
            }
            try (var insert =
                    statement(
                            connection,
                            "INSERT INTO agt_authority_member(authority_name,grant_id,root_id) VALUES (?,?,?)")) {
                insert.setString(1, authorityName);
                insert.setString(2, doc.id());
                insert.setString(3, rootId);
                insert.executeUpdate();
            }
        }
    }

    private <T> T transact(
            String grantId, boolean fenced, Function<InMemoryGrantRegistry, T> operation) {
        try (var connection = source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                String rootId = rootId(connection, grantId);
                long version, seen;
                RegistryCheckpoint checkpoint;
                String task;
                try (var query =
                        statement(
                                connection,
                                "SELECT * FROM agt_authority_root WHERE authority_name=? AND root_id=? FOR UPDATE")) {
                    query.setString(1, authorityName);
                    query.setString(2, rootId);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next() || rows.getInt("format_version") != 1) throw denied();
                        if (rows.getBoolean("blocked"))
                            throw new GovernanceException("delegation_checkpoint_capacity_denied");
                        version = rows.getLong("revision");
                        seen = rows.getLong("last_seen");
                        task = rows.getString("task_id");
                        if (version < 1 || seen < 0) throw denied();
                        checkpoint = codec.decode(body(rows), rows.getString("checksum"));
                    }
                }
                var rootDoc = rootDocument(checkpoint, rootId);
                if (!rootDoc.taskId().equals(task)) throw denied();
                long now = Math.max(seen, clock.millis());
                var registry =
                        InMemoryGrantRegistry.restored(
                                checkpoint, new TransactionClock(now), catalog);
                registry.expireLeases();
                T result = null;
                GovernanceException refusal = null;
                try {
                    result = operation.apply(registry);
                } catch (GovernanceException rejected) {
                    refusal = rejected;
                }
                // 拒绝也可能产生到期清理或未知费用，必须一起提交，不能回滚成允许状态。
                long validUntil =
                        fenced && refusal == null ? registry.validUntil(grantId) : Long.MAX_VALUE;
                var next = registry.checkpoint(rootId);
                byte[] encoded;
                try {
                    encoded = codec.encode(next);
                } catch (GovernanceException capacity) {
                    // 状态增长失败可能发生在结算/冻结之后；不能回退到仍允许兄弟执行的旧状态。
                    try (var block =
                            statement(
                                    connection,
                                    "UPDATE agt_authority_root SET blocked=TRUE,last_seen=? WHERE authority_name=? AND root_id=?")) {
                        block.setLong(1, now);
                        block.setString(2, authorityName);
                        block.setString(3, rootId);
                        if (block.executeUpdate() != 1) throw denied();
                    }
                    connection.commit();
                    committed = true;
                    throw capacity;
                }
                members(connection, next, rootId);
                try (var update =
                        statement(
                                connection,
                                "UPDATE agt_authority_root SET revision=?,last_seen=?,state_blob=?,checksum=? WHERE authority_name=? AND root_id=? AND revision=?")) {
                    update.setLong(1, Math.addExact(version, 1));
                    update.setLong(2, now);
                    update.setBytes(3, encoded);
                    update.setString(4, CheckpointCodec.digest(encoded));
                    update.setString(5, authorityName);
                    update.setString(6, rootId);
                    update.setLong(7, version);
                    if (update.executeUpdate() != 1) throw denied();
                }
                connection.commit();
                committed = true;
                if (refusal != null) throw refusal;
                long finished = Math.max(now, clock.millis());
                if (fenced && finished >= validUntil) {
                    // 不返回已过期的准入，也不退款；记录已观察时间，避免时钟回退复活租约。
                    try (var note =
                            statement(
                                    connection,
                                    "UPDATE agt_authority_root SET last_seen=CASE WHEN last_seen<? THEN ? ELSE last_seen END WHERE authority_name=? AND root_id=?")) {
                        note.setLong(1, finished);
                        note.setLong(2, finished);
                        note.setString(3, authorityName);
                        note.setString(4, rootId);
                        note.executeUpdate();
                    }
                    connection.commit();
                    throw new GovernanceException("delegation_inactive_denied");
                }
                return result;
            } catch (RuntimeException | SQLException failure) {
                if (!committed) rollback(connection);
                throw failure;
            }
        } catch (SQLException unknown) {
            throw unavailable();
        } catch (ArithmeticException invalid) {
            throw denied();
        }
    }

    public void registerRoot(
            GrantDocument doc,
            GrantLease lease,
            String session,
            UsageBudget usage,
            DataFlowState flow) {
        registerRoot(doc, lease, session, usage, flow, null);
    }

    public void registerRoot(
            GrantDocument doc,
            GrantLease lease,
            String session,
            UsageBudget usage,
            DataFlowState flow,
            ExternalExecutionState.Checkpoint external) {
        long now = clock.millis();
        var registry = new InMemoryGrantRegistry(new TransactionClock(now));
        registry.registerRoot(doc, lease, session, usage, flow, external);
        var state = registry.checkpoint(doc.id());
        // 先验证此宿主能重新绑定同一目录，绝不将目录缺失恢复为空状态。
        InMemoryGrantRegistry.restored(state, new TransactionClock(now), catalog);
        var encoded = codec.encode(state);
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var insert =
                        statement(
                                connection,
                                "INSERT INTO agt_authority_root(authority_name,root_id,task_id,revision,last_seen,format_version,state_blob,checksum) VALUES (?,?,?,1,?,1,?,?)")) {
                    insert.setString(1, authorityName);
                    insert.setString(2, doc.id());
                    insert.setString(3, doc.taskId());
                    insert.setLong(4, now);
                    insert.setBytes(5, encoded);
                    insert.setString(6, CheckpointCodec.digest(encoded));
                    insert.executeUpdate();
                }
                members(connection, state, doc.id());
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection);
                throw failure;
            }
        } catch (SQLException failed) {
            if ("23505".equals(failed.getSQLState()))
                throw new GovernanceException("delegation_replay_denied");
            throw unavailable();
        }
        if (clock.millis() >= registry.validUntil(doc.id()))
            throw new GovernanceException("delegation_inactive_denied");
    }

    private void command(
            GrantLease lease,
            boolean fenced,
            java.util.function.Consumer<InMemoryGrantRegistry> operation) {
        transact(
                lease.grantId(),
                fenced,
                registry -> {
                    operation.accept(registry);
                    return null;
                });
    }

    public GrantDocument parent(GrantLease lease) {
        return transact(lease.grantId(), true, registry -> registry.parent(lease));
    }

    public void issue(GrantDocument doc, GrantLease parent) {
        command(parent, true, registry -> registry.issue(doc, parent));
    }

    public GrantDocument claim(
            String id, String digest, String actor, String audience, GrantLease lease) {
        return transact(id, true, registry -> registry.claim(id, digest, actor, audience, lease));
    }

    public void bind(GrantLease lease, String session) {
        command(lease, true, registry -> registry.bind(lease, session));
    }

    public void check(GrantLease lease, String session) {
        command(lease, true, registry -> registry.check(lease, session));
    }

    public void commit(
            GrantLease lease, String session, String action, String kind, String digest) {
        command(lease, true, registry -> registry.commit(lease, session, action, kind, digest));
    }

    public void reviewedCommit(
            GrantLease lease,
            String session,
            String action,
            String tool,
            String binding,
            Object projection,
            String digest) {
        command(
                lease,
                true,
                registry ->
                        registry.reviewedCommit(
                                lease, session, action, tool, binding, projection, digest));
    }

    public void complete(GrantLease lease, String session) {
        command(lease, false, registry -> registry.complete(lease, session));
    }

    public void heartbeatRoot(GrantLease lease) {
        command(lease, true, registry -> registry.heartbeatRoot(lease));
    }

    public void authorizeCaller(GrantLease lease, String actor) {
        command(lease, false, registry -> registry.authorizeCaller(lease, actor));
    }

    public void revoke(String id) {
        transact(
                id,
                false,
                registry -> {
                    registry.revoke(id);
                    return null;
                });
    }

    public void guardedCommit(
            GrantLease lease,
            String session,
            String action,
            InterventionPoint point,
            String tool,
            Object projection,
            String digest) {
        command(
                lease,
                true,
                registry ->
                        registry.guardedCommit(
                                lease, session, action, point, tool, projection, digest));
    }

    public void flowValidate(
            GrantLease lease,
            String session,
            InterventionPoint point,
            String tool,
            Object projection) {
        command(
                lease,
                true,
                registry -> registry.flowValidate(lease, session, point, tool, projection));
    }

    public void flowComplete(GrantLease lease, String session, String action, String tool) {
        command(lease, true, registry -> registry.flowComplete(lease, session, action, tool));
    }

    public void flowValidate(
            GrantLease lease,
            String session,
            String action,
            InterventionPoint point,
            String tool,
            Object projection) {
        command(
                lease,
                true,
                registry -> registry.flowValidate(lease, session, action, point, tool, projection));
    }

    public DataFlowState.Snapshot flowSnapshot(GrantLease lease, String session) {
        return transact(lease.grantId(), false, registry -> registry.flowSnapshot(lease, session));
    }

    public void usageReserve(
            GrantLease lease, String session, String id, String model, int input, int output) {
        command(
                lease,
                true,
                registry -> registry.usageReserve(lease, session, id, model, input, output));
    }

    public void usageDispatch(GrantLease lease, String session, String id) {
        command(lease, true, registry -> registry.usageDispatch(lease, session, id));
    }

    public void usageSettle(
            GrantLease lease,
            String session,
            String id,
            String model,
            Integer input,
            Integer output,
            Integer total) {
        command(
                lease,
                true,
                registry -> registry.usageSettle(lease, session, id, model, input, output, total));
    }

    public void usageClose(GrantLease lease, String session, String id) {
        command(lease, false, registry -> registry.usageClose(lease, session, id));
    }

    public UsageBudget.Snapshot usageSnapshot(GrantLease lease, String session) {
        return transact(lease.grantId(), false, registry -> registry.usageSnapshot(lease, session));
    }

    /** 凭已认证主体和 governance:inspect 权限读取过期后的账本；不恢复授权，不加入接收端协议。 */
    public RegistryInspection inspect(String rootId, TrustedContext reader) {
        return transact(rootId, false, registry -> registry.inspect(rootId, reader));
    }

    public void workRegister(GrantLease lease, String session, String id, String kind) {
        command(lease, true, registry -> registry.workRegister(lease, session, id, kind));
    }

    public void workStart(GrantLease lease, String session, String id) {
        command(lease, true, registry -> registry.workStart(lease, session, id));
    }

    public void workCancel(GrantLease lease, String session, String id) {
        command(lease, false, registry -> registry.workCancel(lease, session, id));
    }

    public void workFinish(
            GrantLease lease, String session, String id, TerminationEvidence evidence) {
        command(lease, false, registry -> registry.workFinish(lease, session, id, evidence));
    }

    public ExternalExecutionState.Summary workStatus(GrantLease lease, String session) {
        return transact(lease.grantId(), false, registry -> registry.workStatus(lease, session));
    }

    public ExternalExecutionState.Page workPage(
            GrantLease lease, String session, long revision, int offset) {
        return transact(
                lease.grantId(),
                false,
                registry -> registry.workPage(lease, session, revision, offset));
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static GovernanceException unavailable() {
        return new GovernanceException("delegation_registry_unavailable");
    }

    private static GovernanceException denied() {
        return new GovernanceException("delegation_checkpoint_denied");
    }
}
