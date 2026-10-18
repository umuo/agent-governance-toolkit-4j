package io.agt.provenance;

import io.agt.core.*;
import io.agt.core.data.DataResource;
import io.agt.core.flow.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** 文档头与不可变发布历史在同一事务更新；与标签目录共用数据源和身份域。 */
final class JdbcDocumentCatalog implements DocumentCatalog {
    private final DataSource source;
    private final int timeout;

    JdbcDocumentCatalog(DataSource source, int timeout) {
        this.source = source;
        this.timeout = timeout;
    }

    void initializeSchema() {
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_document_head (tenant_id VARCHAR(256) NOT NULL, document_key VARCHAR(80) NOT NULL, version BIGINT NOT NULL CHECK (version>=0), PRIMARY KEY(tenant_id,document_key))");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_document_version (tenant_id VARCHAR(256) NOT NULL, document_key VARCHAR(80) NOT NULL, version BIGINT NOT NULL CHECK(version>0), document_id VARCHAR(128) NOT NULL, label_rank INTEGER NOT NULL CHECK(label_rank BETWEEN 0 AND 3), segments VARCHAR(1048576) NOT NULL, task_id VARCHAR(128) NOT NULL, action_id VARCHAR(128) NOT NULL, source_refs VARCHAR(1048576) NOT NULL, manifest_digest VARCHAR(80) NOT NULL, request_digest VARCHAR(80) NOT NULL, PRIMARY KEY(tenant_id,document_key,version), UNIQUE(tenant_id,document_key,task_id,action_id), FOREIGN KEY(tenant_id,document_key) REFERENCES agt_document_head(tenant_id,document_key))");
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    private String key(DataResource resource, String documentId) {
        if (resource.kind() != DataResource.Kind.RETRIEVAL)
            throw new GovernanceException("document_resource_kind_denied");
        Identifiers.require(documentId);
        return JsonValues.digest(List.of("AGT-DOCUMENT-V1", resource.descriptor(), documentId));
    }

    private PreparedStatement query(
            Connection connection, String sql, DataResource resource, String key)
            throws SQLException {
        var query = connection.prepareStatement(sql);
        query.setQueryTimeout(timeout);
        query.setString(1, resource.tenantId());
        query.setString(2, key);
        return query;
    }

    private Long head(Connection connection, DataResource resource, String key, boolean lock)
            throws SQLException {
        try (var query =
                        query(
                                connection,
                                "SELECT version FROM agt_document_head WHERE tenant_id=? AND document_key=?"
                                        + (lock ? " FOR UPDATE" : ""),
                                resource,
                                key);
                var rows = query.executeQuery()) {
            if (!rows.next()) return null;
            long version = rows.getLong(1);
            if (version < 1) throw integrity();
            return version;
        }
    }

    private DocumentVersion decode(ResultSet row, String documentId) throws SQLException {
        try {
            var raw = JsonValues.parseObject(row.getString("segments"));
            var segments = new TreeMap<String, String>();
            raw.forEach((id, digest) -> segments.put(id, (String) digest));
            var refs =
                    ((List<?>) JsonValues.parse(row.getString("source_refs")))
                            .stream().map(value -> (String) value).toList();
            var result =
                    new DocumentVersion(
                            row.getString("document_id"),
                            row.getLong("version"),
                            DataLabel.values()[row.getInt("label_rank")],
                            segments,
                            new LabelProvenance(
                                    row.getString("task_id"),
                                    row.getString("action_id"),
                                    refs,
                                    LabelProvenance.Kind.CLASSIFICATION));
            if (!documentId.equals(result.documentId())
                    || !result.manifestDigest().equals(row.getString("manifest_digest")))
                throw integrity();
            return result;
        } catch (RuntimeException invalid) {
            throw integrity();
        }
    }

    private DocumentVersion version(
            Connection connection,
            DataResource resource,
            String key,
            String documentId,
            long version)
            throws SQLException {
        try (var query =
                query(
                        connection,
                        "SELECT * FROM agt_document_version WHERE tenant_id=? AND document_key=? AND version=?",
                        resource,
                        key)) {
            query.setLong(3, version);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                var result = decode(rows, documentId);
                if (result.version() != version) throw integrity();
                return result;
            }
        }
    }

    private DocumentVersion required(
            Connection connection,
            DataResource resource,
            String key,
            String documentId,
            long version)
            throws SQLException {
        var result = version(connection, resource, key, documentId, version);
        if (result == null) throw integrity();
        return result;
    }

    public Optional<DocumentVersion> currentDocument(DataResource resource, String documentId) {
        return read(resource, documentId, null);
    }

    public Optional<DocumentVersion> documentHistory(
            DataResource resource, String documentId, long version) {
        if (version < 1) throw new IllegalArgumentException("Invalid document version");
        return read(resource, documentId, version);
    }

    private Optional<DocumentVersion> read(
            DataResource resource, String documentId, Long requested) {
        String key = key(resource, documentId);
        try (var connection = source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                Long latest = head(connection, resource, key, false);
                if (latest == null) {
                    try (var query =
                                    query(
                                            connection,
                                            "SELECT version FROM agt_document_version WHERE tenant_id=? AND document_key=?",
                                            resource,
                                            key);
                            var rows = query.executeQuery()) {
                        if (rows.next()) throw integrity();
                    }
                    connection.commit();
                    return Optional.empty();
                }
                var current = required(connection, resource, key, documentId, latest);
                var result =
                        requested == null
                                ? current
                                : requested > latest
                                        ? null
                                        : required(
                                                connection, resource, key, documentId, requested);
                connection.commit();
                return Optional.ofNullable(result);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection);
                throw failure;
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    public DocumentVersion publishDocument(
            DataResource resource,
            String documentId,
            long expectedVersion,
            Map<String, String> segments,
            DataLabel label,
            LabelProvenance provenance) {
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid expected document version");
        var proposed =
                new DocumentVersion(documentId, expectedVersion + 1, label, segments, provenance);
        String key = key(resource, documentId);
        String requestDigest =
                JsonValues.digest(List.of(key, expectedVersion, proposed.manifestDigest()));
        for (int attempt = 0; attempt < 5; attempt++) {
            try (var connection = source.getConnection()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                try {
                    Long previous = head(connection, resource, key, true);
                    var previousDocument =
                            previous == null
                                    ? null
                                    : required(connection, resource, key, documentId, previous);
                    if (previous == null) {
                        try (var insert =
                                query(
                                        connection,
                                        "INSERT INTO agt_document_head(tenant_id,document_key,version) VALUES(?,?,0)",
                                        resource,
                                        key)) {
                            insert.executeUpdate();
                        }
                        // 唯一键取得首写锁；并发首写冲突回滚后重试，不盲目覆盖。
                    }
                    try (var query =
                            query(
                                    connection,
                                    "SELECT * FROM agt_document_version WHERE tenant_id=? AND document_key=? AND task_id=? AND action_id=?",
                                    resource,
                                    key)) {
                        query.setString(3, provenance.taskId());
                        query.setString(4, provenance.actionId());
                        try (var rows = query.executeQuery()) {
                            if (rows.next()) {
                                if (!requestDigest.equals(rows.getString("request_digest")))
                                    throw new GovernanceException(
                                            "document_publication_action_mismatch_denied");
                                var result = decode(rows, documentId);
                                if (previous == null || result.version() > previous)
                                    throw integrity();
                                connection.commit();
                                return result;
                            }
                        }
                    }
                    if ((previous == null ? 0 : previous) != expectedVersion)
                        throw new GovernanceException("document_publication_conflict");
                    var effective = previous == null ? label : label.join(previousDocument.label());
                    var published =
                            new DocumentVersion(
                                    documentId,
                                    expectedVersion + 1,
                                    effective,
                                    proposed.segments(),
                                    provenance);
                    try (var insert =
                            query(
                                    connection,
                                    "INSERT INTO agt_document_version(tenant_id,document_key,version,document_id,label_rank,segments,task_id,action_id,source_refs,manifest_digest,request_digest) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                    resource,
                                    key)) {
                        insert.setLong(3, published.version());
                        insert.setString(4, documentId);
                        insert.setInt(5, effective.ordinal());
                        insert.setString(6, JsonValues.write(published.segments()));
                        insert.setString(7, provenance.taskId());
                        insert.setString(8, provenance.actionId());
                        insert.setString(9, JsonValues.write(provenance.sources()));
                        insert.setString(10, published.manifestDigest());
                        insert.setString(11, requestDigest);
                        insert.executeUpdate();
                    }
                    try (var update =
                            connection.prepareStatement(
                                    "UPDATE agt_document_head SET version=? WHERE tenant_id=? AND document_key=? AND version=?")) {
                        update.setQueryTimeout(timeout);
                        update.setLong(1, published.version());
                        update.setString(2, resource.tenantId());
                        update.setString(3, key);
                        update.setLong(4, expectedVersion);
                        if (update.executeUpdate() != 1) throw integrity();
                    }
                    connection.commit();
                    return published;
                } catch (SQLException | RuntimeException failure) {
                    rollback(connection);
                    throw failure;
                }
            } catch (SQLException failure) {
                if (!Set.of("23505", "40001", "40P01")
                        .contains(Objects.toString(failure.getSQLState(), ""))) throw unavailable();
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
        return new GovernanceException("document_catalog_unavailable");
    }

    private GovernanceException integrity() {
        return new GovernanceException("document_catalog_integrity_failed");
    }
}
