package io.agt.effects.jdbc;

import static io.agt.core.effects.EffectLedger.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import io.agt.core.*;
import io.agt.core.effects.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;

/** 单意图行锁协调；持久化纯元数据，不在事务内重放真实业务调用。 */
public final class JdbcEffectLedger implements EffectLedger {
    private final DataSource data;
    private final String name;
    private final Clock clock;
    private final int timeout;
    private final ObjectMapper json;

    public JdbcEffectLedger(
            DataSource data, String name, Clock clock, int statementTimeoutSeconds) {
        this.data = Objects.requireNonNull(data);
        this.name = Identifiers.require(name);
        this.clock = Objects.requireNonNull(clock);
        if (statementTimeoutSeconds < 1 || statementTimeoutSeconds > 30)
            throw new IllegalArgumentException("Invalid SQL timeout");
        timeout = statementTimeoutSeconds;
        json =
                new ObjectMapper(
                        JsonFactory.builder()
                                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                .streamReadConstraints(
                                        StreamReadConstraints.builder()
                                                .maxNestingDepth(32)
                                                .maxStringLength(65536)
                                                .maxNumberLength(128)
                                                .build())
                                .build());
        json.enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        json.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public void initializeSchema() {
        try (var connection = data.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_effect_catalog (ledger_name VARCHAR(128) PRIMARY KEY, catalog_id VARCHAR(128) NOT NULL)");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_effect_intent (ledger_name VARCHAR(128) NOT NULL, effect_key VARCHAR(80) NOT NULL, state_blob BLOB NOT NULL, checksum VARCHAR(80) NOT NULL, PRIMARY KEY(ledger_name,effect_key), FOREIGN KEY(ledger_name) REFERENCES agt_effect_catalog(ledger_name))");
            try (var insert =
                    connection.prepareStatement(
                            "INSERT INTO agt_effect_catalog(ledger_name,catalog_id) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM agt_effect_catalog WHERE ledger_name=?)")) {
                insert.setQueryTimeout(timeout);
                insert.setString(1, name);
                insert.setString(2, UUID.randomUUID().toString());
                insert.setString(3, name);
                try {
                    insert.executeUpdate();
                } catch (SQLException duplicate) {
                    if (!"23505".equals(duplicate.getSQLState())) throw duplicate;
                }
            }
        } catch (SQLException unavailable) {
            throw unavailable();
        }
    }

    public String identity() {
        try (var connection = data.getConnection();
                var query =
                        connection.prepareStatement(
                                "SELECT catalog_id FROM agt_effect_catalog WHERE ledger_name=?")) {
            query.setQueryTimeout(timeout);
            query.setString(1, name);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw integrity();
                return Identifiers.require(rows.getString(1));
            }
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    private byte[] encode(EffectJournal state) {
        try {
            byte[] bytes = json.writeValueAsBytes(state.checkpoint());
            if (bytes.length > 1048576)
                throw new GovernanceException("effect_checkpoint_capacity_denied");
            return bytes;
        } catch (GovernanceException denied) {
            throw denied;
        } catch (Exception invalid) {
            throw integrity();
        }
    }

    private String digest(byte[] bytes) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private EffectJournal read(ResultSet rows, Key key) throws SQLException {
        try (var stream = rows.getBinaryStream(1)) {
            byte[] bytes = stream.readNBytes(1048577);
            if (bytes.length > 1048576 || !digest(bytes).equals(rows.getString(2)))
                throw integrity();
            var checkpoint = json.readValue(bytes, EffectJournal.Checkpoint.class);
            if (!checkpoint.key().equals(key)) throw integrity();
            return EffectJournal.restored(checkpoint);
        } catch (Exception invalid) {
            throw integrity();
        }
    }

    private <T> T transact(Key key, boolean create, Function<EffectJournal, T> operation) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try (var connection = data.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    EffectJournal state = null;
                    try (var query =
                            connection.prepareStatement(
                                    "SELECT state_blob,checksum FROM agt_effect_intent WHERE ledger_name=? AND effect_key=? FOR UPDATE")) {
                        query.setQueryTimeout(timeout);
                        query.setString(1, name);
                        query.setString(2, key.digest());
                        try (var rows = query.executeQuery()) {
                            if (rows.next()) state = read(rows, key);
                        }
                    }
                    if (state == null) {
                        state = new EffectJournal(key);
                        if (!create) {
                            state.observe(clock.millis());
                            T result = operation.apply(state);
                            connection.rollback();
                            return result;
                        }
                        byte[] bytes = encode(state);
                        try (var insert =
                                connection.prepareStatement(
                                        "INSERT INTO agt_effect_intent(ledger_name,effect_key,state_blob,checksum) VALUES(?,?,?,?)")) {
                            insert.setQueryTimeout(timeout);
                            insert.setString(1, name);
                            insert.setString(2, key.digest());
                            insert.setBytes(3, bytes);
                            insert.setString(4, digest(bytes));
                            insert.executeUpdate();
                        }
                    }
                    state.observe(clock.millis());
                    GovernanceException refusal = null;
                    T result = null;
                    try {
                        result = operation.apply(state);
                    } catch (GovernanceException denied) {
                        refusal = denied;
                    }
                    byte[] bytes = encode(state);
                    try (var update =
                            connection.prepareStatement(
                                    "UPDATE agt_effect_intent SET state_blob=?,checksum=? WHERE ledger_name=? AND effect_key=?")) {
                        update.setQueryTimeout(timeout);
                        update.setBytes(1, bytes);
                        update.setString(2, digest(bytes));
                        update.setString(3, name);
                        update.setString(4, key.digest());
                        if (update.executeUpdate() != 1) throw integrity();
                    }
                    connection.commit();
                    if (refusal != null) throw refusal;
                    return result;
                } catch (SQLException | RuntimeException failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException ignored) {
                    }
                    throw failure;
                }
            } catch (SQLException failure) {
                if (!Set.of("23505", "40001", "40P01")
                        .contains(Objects.toString(failure.getSQLState(), ""))) throw unavailable();
            }
        }
        throw unavailable();
    }

    public Claim claim(
            Key key,
            Phase phase,
            String requestDigest,
            Proof parent,
            Owner owner,
            long leaseMillis) {
        return transact(
                key, true, state -> state.claim(phase, requestDigest, parent, owner, leaseMillis));
    }

    public void start(Lease lease) {
        transact(
                lease.key(),
                false,
                state -> {
                    state.start(lease);
                    return null;
                });
        if (clock.millis() >= lease.expiresAt())
            throw new GovernanceException("effect_dispatch_ack_expired");
    }

    public void finish(Lease lease, Status status, Proof proof) {
        transact(
                lease.key(),
                false,
                state -> {
                    state.finish(lease, status, proof);
                    return null;
                });
    }

    private void authorize(Key key, TrustedContext actor, String permission) {
        key.requireOwner(actor);
        if (!actor.permissions().contains(permission))
            throw new GovernanceException("effect_management_denied");
    }

    public View inspect(Key key, TrustedContext actor) {
        authorize(key, actor, "governance:effects:inspect");
        return transact(key, false, EffectJournal::view);
    }

    public void reconcile(
            Key key,
            Phase phase,
            long attempt,
            Status status,
            Proof proof,
            TrustedContext actor,
            String reviewId) {
        authorize(key, actor, "governance:effects:reconcile");
        transact(
                key,
                false,
                state -> {
                    state.reconcile(phase, attempt, status, proof, reviewId);
                    return null;
                });
    }

    private GovernanceException unavailable() {
        return new GovernanceException("effect_store_unavailable");
    }

    private GovernanceException integrity() {
        return new GovernanceException("effect_store_integrity_failed");
    }
}
