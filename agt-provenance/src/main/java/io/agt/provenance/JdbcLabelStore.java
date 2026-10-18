package io.agt.provenance;

import io.agt.core.*;
import io.agt.core.data.DataFragment;
import io.agt.core.data.DataResource;
import io.agt.core.flow.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** 事务化单调标签及因果版本；只依赖 JDBC，驱动/认证/连接期限由宿主管理。 */
public final class JdbcLabelStore implements LabelStore {
    private final DataSource dataSource;
    private final int statementTimeoutSeconds;
    private final JdbcDocumentCatalog documents;

    public JdbcLabelStore(DataSource dataSource, int statementTimeoutSeconds) {
        this.dataSource = Objects.requireNonNull(dataSource);
        if (statementTimeoutSeconds < 1 || statementTimeoutSeconds > 30)
            throw new IllegalArgumentException("Invalid statement timeout");
        this.statementTimeoutSeconds = statementTimeoutSeconds;
        documents = new JdbcDocumentCatalog(dataSource, statementTimeoutSeconds);
    }

    /** 仅由宿主迁移/启动流程调用，不在读取失败后自动重建目录。 */
    public void initializeSchema() {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(statementTimeoutSeconds);
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_label_catalog (singleton INTEGER PRIMARY KEY CHECK (singleton=1), catalog_id VARCHAR(128) NOT NULL)");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_resource_label (tenant_id VARCHAR(256) NOT NULL, resource_key VARCHAR(80) NOT NULL, label_rank INTEGER NOT NULL CHECK (label_rank BETWEEN 0 AND 3), revision BIGINT NOT NULL CHECK (revision >= 0), PRIMARY KEY (tenant_id, resource_key))");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_label_origin (tenant_id VARCHAR(256) NOT NULL, resource_key VARCHAR(80) NOT NULL, revision BIGINT NOT NULL CHECK (revision > 0), label_rank INTEGER NOT NULL CHECK (label_rank BETWEEN 0 AND 3), task_id VARCHAR(256) NOT NULL, action_id VARCHAR(256) NOT NULL, source_refs VARCHAR(1048576) NOT NULL, fingerprint VARCHAR(80) NOT NULL, previous_revision BIGINT NOT NULL, event_kind VARCHAR(32) NOT NULL, PRIMARY KEY (tenant_id, resource_key, revision), UNIQUE (tenant_id, resource_key, task_id, action_id))");
            try (var insert =
                    statement(
                            connection,
                            "INSERT INTO agt_label_catalog (singleton,catalog_id) SELECT 1,? WHERE NOT EXISTS (SELECT 1 FROM agt_label_catalog WHERE singleton=1)")) {
                insert.setString(1, UUID.randomUUID().toString());
                try {
                    insert.executeUpdate();
                } catch (SQLException duplicate) {
                    if (!"23505".equals(duplicate.getSQLState())) throw duplicate;
                }
            }
        } catch (SQLException unavailable) {
            throw unavailable();
        }
        documents.initializeSchema();
    }

    public DocumentVersion publishDocument(
            DataResource resource,
            String documentId,
            long expectedVersion,
            Map<String, String> segments,
            DataLabel label,
            LabelProvenance provenance) {
        return documents.publishDocument(
                resource, documentId, expectedVersion, segments, label, provenance);
    }

    public Optional<DocumentVersion> currentDocument(DataResource resource, String documentId) {
        return documents.currentDocument(resource, documentId);
    }

    public Optional<DocumentVersion> documentHistory(
            DataResource resource, String documentId, long version) {
        return documents.documentHistory(resource, documentId, version);
    }

    public String catalogIdentity() {
        try (var connection = dataSource.getConnection();
                var query =
                        statement(
                                connection,
                                "SELECT catalog_id FROM agt_label_catalog WHERE singleton=1");
                var rows = query.executeQuery()) {
            if (!rows.next()) throw integrity();
            return Identifiers.require(rows.getString(1));
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    private PreparedStatement statement(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(statementTimeoutSeconds);
        return statement;
    }

    private record CatalogKey(String tenantId, String digest) {}

    private CatalogKey key(DataResource resource) {
        return new CatalogKey(resource.tenantId(), JsonValues.digest(resource.descriptor()));
    }

    private CatalogKey key(DataResource resource, DataFragment fragment) {
        return new CatalogKey(resource.tenantId(), fragment.key(resource));
    }

    private void identity(PreparedStatement statement, CatalogKey resource) throws SQLException {
        statement.setString(1, resource.tenantId());
        statement.setString(2, resource.digest());
    }

    private StoredLabel current(Connection connection, CatalogKey resource, boolean lock)
            throws SQLException {
        try (var query =
                statement(
                        connection,
                        "SELECT label_rank, revision FROM agt_resource_label WHERE tenant_id=? AND resource_key=?"
                                + (lock ? " FOR UPDATE" : ""))) {
            identity(query, resource);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                long revision = rows.getLong(2);
                return mark(rows.getInt(1), revision);
            }
        }
    }

    private StoredLabel mark(int rank, long revision) {
        if (rank < 0 || rank >= DataLabel.values().length || revision < 1) throw integrity();
        return new StoredLabel(DataLabel.values()[rank], revision);
    }

    private LabelHistory event(ResultSet rows) throws SQLException {
        try {
            var values = (List<?>) JsonValues.parse(rows.getString("source_refs"));
            var refs = values.stream().map(value -> (String) value).toList();
            return new LabelHistory(
                    mark(rows.getInt("label_rank"), rows.getLong("revision")),
                    rows.getLong("previous_revision"),
                    new LabelProvenance(
                            rows.getString("task_id"),
                            rows.getString("action_id"),
                            refs,
                            LabelProvenance.Kind.valueOf(rows.getString("event_kind"))));
        } catch (RuntimeException invalid) {
            throw integrity();
        }
    }

    private LabelHistory history(Connection connection, CatalogKey resource, long revision)
            throws SQLException {
        try (var query =
                statement(
                        connection,
                        "SELECT * FROM agt_label_origin WHERE tenant_id=? AND resource_key=? AND revision=?")) {
            identity(query, resource);
            query.setLong(3, revision);
            try (var rows = query.executeQuery()) {
                return rows.next() ? event(rows) : null;
            }
        }
    }

    private void verify(Connection connection, CatalogKey resource, StoredLabel mark)
            throws SQLException {
        if (mark != null) {
            var history = history(connection, resource, mark.revision());
            if (history == null || !history.mark().equals(mark)) throw integrity();
        } else {
            try (var query =
                    statement(
                            connection,
                            "SELECT revision FROM agt_label_origin WHERE tenant_id=? AND resource_key=?")) {
                identity(query, resource);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) throw integrity();
                }
            }
        }
    }

    public Optional<StoredLabel> read(DataResource resource) {
        return read(key(resource));
    }

    public Optional<StoredLabel> readFragment(DataResource resource, DataFragment fragment) {
        return read(key(resource, fragment));
    }

    private Optional<StoredLabel> read(CatalogKey resource) {
        try (var connection = dataSource.getConnection()) {
            // 同一事务固定当前版本与其事件，避免正常并发写入被误判为损坏。
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                var mark = current(connection, resource, false);
                verify(connection, resource, mark);
                connection.commit();
                return Optional.ofNullable(mark);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection);
                throw failure;
            }
        } catch (SQLException unavailable) {
            throw unavailable();
        }
    }

    public Optional<LabelHistory> history(DataResource resource, long revision) {
        return history(key(resource), revision);
    }

    public Optional<LabelHistory> fragmentHistory(
            DataResource resource, DataFragment fragment, long revision) {
        return history(key(resource, fragment), revision);
    }

    private Optional<LabelHistory> history(CatalogKey resource, long revision) {
        if (revision < 1) throw new IllegalArgumentException("Invalid label revision");
        try (var connection = dataSource.getConnection()) {
            return Optional.ofNullable(history(connection, resource, revision));
        } catch (SQLException unavailable) {
            throw unavailable();
        }
    }

    public StoredLabel record(DataResource resource, DataLabel label, LabelProvenance provenance) {
        return record(key(resource), label, provenance);
    }

    public StoredLabel recordFragment(
            DataResource resource,
            DataFragment fragment,
            DataLabel label,
            LabelProvenance provenance) {
        return record(key(resource, fragment), label, provenance);
    }

    private StoredLabel record(CatalogKey resource, DataLabel label, LabelProvenance provenance) {
        Objects.requireNonNull(label);
        Objects.requireNonNull(provenance);
        String sourceRefs = JsonValues.write(provenance.sources());
        if (sourceRefs.length() > JsonValues.MAX_CHARACTERS)
            throw new GovernanceException("label_store_provenance_size_denied");
        String fingerprint =
                JsonValues.digest(
                        Map.of(
                                "resource",
                                resource.digest(),
                                "label",
                                label.name(),
                                "task",
                                provenance.taskId(),
                                "action",
                                provenance.actionId(),
                                "sources",
                                provenance.sources(),
                                "kind",
                                provenance.kind().name()));
        for (int attempt = 0; attempt < 5; attempt++) {
            try (var connection = dataSource.getConnection()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                try {
                    var previous = current(connection, resource, true);
                    if (previous == null) {
                        try (var insert =
                                statement(
                                        connection,
                                        "INSERT INTO agt_resource_label (tenant_id,resource_key,label_rank,revision) VALUES (?,?,0,0)")) {
                            identity(insert, resource);
                            insert.executeUpdate();
                        }
                        // 先用唯一键取得新目录行；并发首写由重复键重试，不能把刚提交的正常历史误判为孤儿。
                        verify(connection, resource, null);
                    } else verify(connection, resource, previous);
                    try (var query =
                            statement(
                                    connection,
                                    "SELECT * FROM agt_label_origin WHERE tenant_id=? AND resource_key=? AND task_id=? AND action_id=?")) {
                        identity(query, resource);
                        query.setString(3, provenance.taskId());
                        query.setString(4, provenance.actionId());
                        try (var rows = query.executeQuery()) {
                            if (rows.next()) {
                                if (!fingerprint.equals(rows.getString("fingerprint")))
                                    throw new GovernanceException(
                                            "label_store_action_mismatch_denied");
                                var existing = event(rows).mark();
                                connection.commit();
                                return existing;
                            }
                        }
                    }
                    long revision = previous == null ? 1 : Math.addExact(previous.revision(), 1);
                    var effective = previous == null ? label : previous.label().join(label);
                    try (var update =
                            statement(
                                    connection,
                                    "UPDATE agt_resource_label SET label_rank=?, revision=? WHERE tenant_id=? AND resource_key=?")) {
                        update.setInt(1, effective.ordinal());
                        update.setLong(2, revision);
                        update.setString(3, resource.tenantId());
                        update.setString(4, resource.digest());
                        if (update.executeUpdate() != 1) throw integrity();
                    }
                    try (var insert =
                            statement(
                                    connection,
                                    "INSERT INTO agt_label_origin (tenant_id,resource_key,revision,label_rank,task_id,action_id,source_refs,fingerprint,previous_revision,event_kind) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                        identity(insert, resource);
                        insert.setLong(3, revision);
                        insert.setInt(4, effective.ordinal());
                        insert.setString(5, provenance.taskId());
                        insert.setString(6, provenance.actionId());
                        insert.setString(7, sourceRefs);
                        insert.setString(8, fingerprint);
                        insert.setLong(9, revision - 1);
                        insert.setString(10, provenance.kind().name());
                        insert.executeUpdate();
                    }
                    connection.commit();
                    return new StoredLabel(effective, revision);
                } catch (SQLException | RuntimeException failure) {
                    rollback(connection);
                    throw failure;
                }
            } catch (SQLException retry) {
                String state = retry.getSQLState();
                if (!("23505".equals(state) || "40001".equals(state) || "40P01".equals(state)))
                    throw unavailable();
            } catch (ArithmeticException overflow) {
                throw integrity();
            }
        }
        throw unavailable();
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private GovernanceException unavailable() {
        return new GovernanceException("label_store_unavailable");
    }

    private GovernanceException integrity() {
        return new GovernanceException("label_store_integrity_failed");
    }
}
