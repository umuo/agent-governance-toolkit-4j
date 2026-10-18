package io.agt.audit.jdbc;

import io.agt.core.*;
import java.sql.*;
import java.util.Objects;
import javax.sql.DataSource;

/** 独立数据库上的持久 CAS 适配；生产必须另配凭据/管理边界，不能与主审计库共享删除权限。 */
public final class JdbcAuditWitness implements AuditWitness {
    private final DataSource data;
    private final int timeout;

    public JdbcAuditWitness(DataSource data, int statementTimeoutSeconds) {
        this.data = Objects.requireNonNull(data);
        if (statementTimeoutSeconds < 1 || statementTimeoutSeconds > 30)
            throw new IllegalArgumentException("Invalid witness SQL timeout");
        timeout = statementTimeoutSeconds;
    }

    public void initializeSchema() {
        try (var connection = data.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_audit_witness(catalog_id VARCHAR(128) NOT NULL, stream_id VARCHAR(64) NOT NULL, seq BIGINT NOT NULL, chain_hash VARCHAR(64) NOT NULL, PRIMARY KEY(catalog_id,stream_id))");
        } catch (SQLException unavailable) {
            throw unavailable();
        }
    }

    public AuditCheckpoint read(String catalog, String stream) {
        Identifiers.require(catalog);
        AuditCheckpoint.requireHash(stream);
        try (var connection = data.getConnection();
                var query =
                        connection.prepareStatement(
                                "SELECT seq,chain_hash FROM agt_audit_witness WHERE catalog_id=? AND stream_id=?")) {
            query.setQueryTimeout(timeout);
            query.setString(1, catalog);
            query.setString(2, stream);
            try (var rows = query.executeQuery()) {
                return rows.next()
                        ? new AuditCheckpoint(catalog, stream, rows.getLong(1), rows.getString(2))
                        : null;
            }
        } catch (SQLException unavailable) {
            throw unavailable();
        }
    }

    public boolean advance(AuditCheckpoint expected, AuditCheckpoint next) {
        Objects.requireNonNull(next);
        if (expected == null
                ? next.sequence() != 0
                : !expected.catalogId().equals(next.catalogId())
                        || !expected.streamId().equals(next.streamId())
                        || next.sequence() != Math.addExact(expected.sequence(), 1))
            throw new IllegalArgumentException("Witness must advance exactly one event");
        try (var connection = data.getConnection()) {
            connection.setAutoCommit(true);
            if (expected == null) {
                try (var insert =
                        connection.prepareStatement(
                                "INSERT INTO agt_audit_witness VALUES(?,?,?,?)")) {
                    insert.setQueryTimeout(timeout);
                    insert.setString(1, next.catalogId());
                    insert.setString(2, next.streamId());
                    insert.setLong(3, 0);
                    insert.setString(4, next.hash());
                    return insert.executeUpdate() == 1;
                }
            }
            try (var update =
                    connection.prepareStatement(
                            "UPDATE agt_audit_witness SET seq=?,chain_hash=? WHERE catalog_id=? AND stream_id=? AND seq=? AND chain_hash=?")) {
                update.setQueryTimeout(timeout);
                update.setLong(1, next.sequence());
                update.setString(2, next.hash());
                update.setString(3, next.catalogId());
                update.setString(4, next.streamId());
                update.setLong(5, expected.sequence());
                update.setString(6, expected.hash());
                return update.executeUpdate() == 1;
            }
        } catch (SQLException error) {
            if ("23505".equals(error.getSQLState())) return false;
            throw unavailable();
        }
    }

    private GovernanceException unavailable() {
        return new GovernanceException("audit_witness_unavailable");
    }
}
