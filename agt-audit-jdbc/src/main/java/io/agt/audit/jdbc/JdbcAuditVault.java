package io.agt.audit.jdbc;

import static io.agt.audit.jdbc.AuditCrypto.*;

import io.agt.core.*;
import io.agt.core.audit.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;

/** 加密的有界追加链；只有数据库提交和独立见证均确认后才确认写入或交付读取结果。 */
public final class JdbcAuditVault implements AuditSink {
    public record RecordView(
            long sequence,
            String eventId,
            String kind,
            long recordExpiresAt,
            long bodyExpiresAt,
            Map<String, Object> metadata,
            boolean bodyAvailable) {
        public RecordView {
            metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }

        @Override
        public String toString() {
            return "AuditRecordView[" + sequence + ",redacted]";
        }
    }

    private record Head(long seq, String hash, long observedAt, String keyId, String seal) {}

    private record Row(
            long seq,
            String eventId,
            String kind,
            String keyId,
            long createdAt,
            long bodyExpires,
            long recordExpires,
            String metadataHash,
            String bodyHash,
            String previous,
            String hash,
            String fingerprint,
            byte[] metadata,
            byte[] body) {}

    private record State(String stream, Head head, List<Row> rows, long now) {}

    private record BodyResult(Object value, String refusal) {}

    private final DataSource data;
    private final String name, catalog, policyId;
    private final AuditVaultPolicy policy;
    private final AuditKeys keys;
    private final AuditWitness witness;
    private final Clock clock;
    private final int timeout;

    public JdbcAuditVault(
            DataSource data,
            String name,
            String reviewedCatalogId,
            AuditVaultPolicy policy,
            AuditKeys keys,
            AuditWitness witness,
            Clock clock,
            int statementTimeoutSeconds) {
        this.data = Objects.requireNonNull(data);
        this.name = Identifiers.require(name);
        catalog = Identifiers.require(reviewedCatalogId);
        this.policy = Objects.requireNonNull(policy);
        policyId = policy.identity();
        this.keys = Objects.requireNonNull(keys);
        this.witness = Objects.requireNonNull(witness);
        this.clock = Objects.requireNonNull(clock);
        if (statementTimeoutSeconds < 1 || statementTimeoutSeconds > 30)
            throw new IllegalArgumentException("Invalid audit SQL timeout");
        timeout = statementTimeoutSeconds;
    }

    @Override
    public AuditBodyPolicy bodyPolicy() {
        return policy.bodies();
    }

    @Override
    public void write(AuditRecord record) {
        throw new GovernanceException("audit_scope_required");
    }

    @Override
    public void write(AuditEvent event) {
        append(event);
    }

    public String identity() {
        try (var connection = data.getConnection()) {
            checkCatalog(connection);
            return catalog;
        } catch (SQLException unavailable) {
            throw unavailable();
        }
    }

    /** 仅供可信部署迁移；正常运行不能自动初始化空库替代已有审计目录。 */
    public void initializeSchema() {
        try (var connection = data.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_audit_catalog(vault_name VARCHAR(128) PRIMARY KEY, catalog_id VARCHAR(128) NOT NULL, policy_id VARCHAR(80) NOT NULL, key_id VARCHAR(128) NOT NULL, seal VARCHAR(64) NOT NULL)");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_audit_head(catalog_id VARCHAR(128) NOT NULL, stream_id VARCHAR(64) NOT NULL, seq BIGINT NOT NULL, chain_hash VARCHAR(64) NOT NULL, observed_at BIGINT NOT NULL, key_id VARCHAR(128) NOT NULL, seal VARCHAR(64) NOT NULL, PRIMARY KEY(catalog_id,stream_id))");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_audit_entry(catalog_id VARCHAR(128) NOT NULL, stream_id VARCHAR(64) NOT NULL, seq BIGINT NOT NULL, event_id VARCHAR(128) NOT NULL, event_kind VARCHAR(32) NOT NULL, key_id VARCHAR(128) NOT NULL, created_at BIGINT NOT NULL, body_expires BIGINT NOT NULL, record_expires BIGINT NOT NULL, metadata_hash VARCHAR(64) NOT NULL, body_hash VARCHAR(64) NOT NULL, previous_hash VARCHAR(64) NOT NULL, chain_hash VARCHAR(64) NOT NULL, fingerprint VARCHAR(64) NOT NULL, metadata_cipher BLOB, body_cipher BLOB, PRIMARY KEY(catalog_id,stream_id,seq), UNIQUE(catalog_id,stream_id,event_id))");
            var active = active();
            try (var insert =
                    connection.prepareStatement(
                            "INSERT INTO agt_audit_catalog VALUES(?,?,?,?,?)")) {
                insert.setQueryTimeout(timeout);
                insert.setString(1, name);
                insert.setString(2, catalog);
                insert.setString(3, policyId);
                insert.setString(4, active.id());
                insert.setString(5, catalogSeal(active));
                try {
                    insert.executeUpdate();
                } catch (SQLException duplicate) {
                    if (!"23505".equals(duplicate.getSQLState())) throw duplicate;
                }
            }
            checkCatalog(connection);
        } catch (SQLException unavailable) {
            throw unavailable();
        }
    }

    private AuditKeys.Version active() {
        var key = Objects.requireNonNull(keys.active());
        return key;
    }

    private AuditKeys.Version key(String id) {
        var key = keys.find(id);
        if (key == null || !id.equals(key.id()))
            throw new GovernanceException("audit_key_unavailable");
        return key;
    }

    private String catalogSeal(AuditKeys.Version key) {
        return mac(key, List.of("AGT-AUDIT-CATALOG-V1", name, catalog, policyId, key.id()));
    }

    private void checkCatalog(Connection connection) throws SQLException {
        try (var query =
                connection.prepareStatement(
                        "SELECT catalog_id,policy_id,key_id,seal FROM agt_audit_catalog WHERE vault_name=?")) {
            query.setQueryTimeout(timeout);
            query.setString(1, name);
            try (var rows = query.executeQuery()) {
                if (!rows.next()
                        || !catalog.equals(rows.getString(1))
                        || !policyId.equals(rows.getString(2))
                        || !equal(catalogSeal(key(rows.getString(3))), rows.getString(4)))
                    throw failed();
            }
        }
    }

    private String stream(String tenant, String principal) {
        return hash(json(List.of("AGT-AUDIT-STREAM-V1", catalog, tenant, principal)));
    }

    private String stream(TrustedContext actor) {
        return stream(actor.tenantId(), actor.principalId());
    }

    private String genesis(String stream) {
        return hash(json(List.of("AGT-AUDIT-GENESIS-V1", catalog, stream, policyId)));
    }

    private AuditCheckpoint checkpoint(String stream, long sequence, String hash) {
        return new AuditCheckpoint(catalog, stream, sequence, hash);
    }

    private String seal(
            String stream, long seq, String hash, long observed, AuditKeys.Version key) {
        return mac(
                key,
                List.of(
                        "AGT-AUDIT-HEAD-V1",
                        catalog,
                        stream,
                        seq,
                        hash,
                        observed,
                        key.id(),
                        policyId));
    }

    private Head loadHead(Connection connection, String stream) throws SQLException {
        try (var query =
                connection.prepareStatement(
                        "SELECT seq,chain_hash,observed_at,key_id,seal FROM agt_audit_head WHERE catalog_id=? AND stream_id=? FOR UPDATE")) {
            query.setQueryTimeout(timeout);
            query.setString(1, catalog);
            query.setString(2, stream);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                var head =
                        new Head(
                                rows.getLong(1),
                                rows.getString(2),
                                rows.getLong(3),
                                rows.getString(4),
                                rows.getString(5));
                if (head.seq() < 0
                        || head.seq() > policy.maxEntriesPerStream()
                        || head.observedAt() < 0
                        || !equal(
                                head.seal(),
                                seal(
                                        stream,
                                        head.seq(),
                                        head.hash(),
                                        head.observedAt(),
                                        key(head.keyId())))) throw failed();
                return head;
            }
        }
    }

    private void saveHead(Connection connection, String stream, Head head, boolean insert)
            throws SQLException {
        String sql =
                insert
                        ? "INSERT INTO agt_audit_head(seq,chain_hash,observed_at,key_id,seal,catalog_id,stream_id) VALUES(?,?,?,?,?,?,?)"
                        : "UPDATE agt_audit_head SET seq=?,chain_hash=?,observed_at=?,key_id=?,seal=? WHERE catalog_id=? AND stream_id=?";
        try (var write = connection.prepareStatement(sql)) {
            write.setQueryTimeout(timeout);
            write.setLong(1, head.seq());
            write.setString(2, head.hash());
            write.setLong(3, head.observedAt());
            write.setString(4, head.keyId());
            write.setString(5, head.seal());
            write.setString(6, catalog);
            write.setString(7, stream);
            if (write.executeUpdate() != 1) throw failed();
        }
    }

    private Object base(String stream, Row row) {
        return List.of(
                "AGT-AUDIT-ROW-V1",
                catalog,
                stream,
                policyId,
                row.seq(),
                row.eventId(),
                row.kind(),
                row.keyId(),
                row.createdAt(),
                row.bodyExpires(),
                row.recordExpires(),
                row.fingerprint());
    }

    private Object aad(String stream, Row row, String part) {
        return List.of(base(stream, row), part);
    }

    private String rowMac(String stream, Row row) {
        return mac(
                key(row.keyId()),
                List.of(base(stream, row), row.metadataHash(), row.bodyHash(), row.previous()));
    }

    private byte[] blob(ResultSet rows, int column) throws SQLException {
        try (var stream = rows.getBinaryStream(column)) {
            if (stream == null) return null;
            byte[] bytes = stream.readNBytes(65565);
            if (bytes.length > 65564) throw failed();
            return bytes;
        } catch (java.io.IOException invalid) {
            throw failed();
        }
    }

    private List<Row> loadRows(Connection connection, String stream, Head head, long now)
            throws SQLException {
        var result = new ArrayList<Row>();
        try (var query =
                connection.prepareStatement(
                        "SELECT seq,event_id,event_kind,key_id,created_at,body_expires,record_expires,metadata_hash,body_hash,previous_hash,chain_hash,fingerprint,metadata_cipher,body_cipher FROM agt_audit_entry WHERE catalog_id=? AND stream_id=? ORDER BY seq")) {
            query.setQueryTimeout(timeout);
            query.setMaxRows(policy.maxEntriesPerStream() + 1);
            query.setString(1, catalog);
            query.setString(2, stream);
            try (var rows = query.executeQuery()) {
                String previous = genesis(stream);
                long seenTime = 0, retainedBytes = 0;
                while (rows.next()) {
                    var row =
                            new Row(
                                    rows.getLong(1),
                                    rows.getString(2),
                                    rows.getString(3),
                                    rows.getString(4),
                                    rows.getLong(5),
                                    rows.getLong(6),
                                    rows.getLong(7),
                                    rows.getString(8),
                                    rows.getString(9),
                                    rows.getString(10),
                                    rows.getString(11),
                                    rows.getString(12),
                                    blob(rows, 13),
                                    blob(rows, 14));
                    Identifiers.require(row.eventId());
                    Identifiers.require(row.kind());
                    Identifiers.require(row.keyId());
                    AuditCheckpoint.requireHash(row.metadataHash());
                    AuditCheckpoint.requireHash(row.hash());
                    AuditCheckpoint.requireHash(row.fingerprint());
                    if (!row.bodyHash().isEmpty()) AuditCheckpoint.requireHash(row.bodyHash());
                    if (row.seq() != result.size() + 1L
                            || row.seq() > policy.maxEntriesPerStream()
                            || !previous.equals(row.previous())
                            || row.createdAt() < seenTime
                            || row.recordExpires()
                                    != Math.addExact(
                                            row.createdAt(), policy.recordRetentionMillis())
                            || row.bodyExpires()
                                    != Math.addExact(row.createdAt(), policy.bodyRetentionMillis())
                            || !equal(row.hash(), rowMac(stream, row))
                            || !payload(
                                    row.metadata(), row.metadataHash(), row.recordExpires(), now)
                            || (row.bodyHash().isEmpty()
                                    ? row.body() != null
                                    : !payload(row.body(), row.bodyHash(), row.bodyExpires(), now)))
                        throw failed();
                    retainedBytes +=
                            (row.metadata() == null ? 0 : row.metadata().length)
                                    + (row.body() == null ? 0 : row.body().length);
                    if (retainedBytes > policy.maxRetainedBytes())
                        throw new GovernanceException("audit_capacity_exceeded");
                    previous = row.hash();
                    seenTime = row.createdAt();
                    result.add(row);
                }
                if (result.size() != head.seq()
                        || !previous.equals(head.hash())
                        || seenTime > head.observedAt()) throw failed();
            }
        }
        return result;
    }

    private boolean payload(byte[] ciphertext, String digest, long expires, long now) {
        return ciphertext == null ? now >= expires : equal(hash(ciphertext), digest);
    }

    private void publish(State state) {
        var current = witness.read(catalog, state.stream());
        if (current == null
                || !current.catalogId().equals(catalog)
                || !current.streamId().equals(state.stream())
                || current.sequence() > state.head().seq()
                || state.head().seq() - current.sequence() > 1
                || !current.hash()
                        .equals(
                                current.sequence() == 0
                                        ? genesis(state.stream())
                                        : state.rows()
                                                .get(Math.toIntExact(current.sequence() - 1))
                                                .hash()))
            throw new GovernanceException("audit_witness_mismatch");
        while (current.sequence() < state.head().seq()) {
            var next = state.rows().get(Math.toIntExact(current.sequence()));
            var target = checkpoint(state.stream(), next.seq(), next.hash());
            if (!witness.advance(current, target))
                throw new GovernanceException("audit_witness_conflict");
            current = target;
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T apply(Connection connection, State state) throws SQLException;
    }

    private <T> T locked(String stream, boolean create, Operation<T> operation) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try (var connection = data.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    checkCatalog(connection);
                    Head head = loadHead(connection, stream);
                    if (head == null) {
                        // 尚无 stream 行时没有可锁对象；串行化首次创建，再读取最新头部，
                        // 避免另一个写入者已提交/见证后仍依据旧的“缺失”查询误判回滚。
                        try (var bootstrap =
                                connection.prepareStatement(
                                        "SELECT catalog_id FROM agt_audit_catalog WHERE vault_name=? FOR UPDATE")) {
                            bootstrap.setQueryTimeout(timeout);
                            bootstrap.setString(1, name);
                            try (var rows = bootstrap.executeQuery()) {
                                if (!rows.next()) throw failed();
                            }
                        }
                        checkCatalog(connection);
                        head = loadHead(connection, stream);
                    }
                    if (head == null) {
                        var anchored = witness.read(catalog, stream);
                        var genesis = checkpoint(stream, 0, genesis(stream));
                        if (anchored != null && !anchored.equals(genesis))
                            throw new GovernanceException("audit_witness_mismatch");
                        if (!create) {
                            if (anchored != null)
                                throw new GovernanceException("audit_store_missing");
                            connection.rollback();
                            return operation.apply(null, null);
                        }
                        if (anchored == null && !witness.advance(null, genesis)) {
                            if (!genesis.equals(witness.read(catalog, stream)))
                                throw new GovernanceException("audit_witness_mismatch");
                        }
                        var key = active();
                        long now = Math.max(0, clock.millis());
                        head =
                                new Head(
                                        0,
                                        genesis.hash(),
                                        now,
                                        key.id(),
                                        seal(stream, 0, genesis.hash(), now, key));
                        saveHead(connection, stream, head, true);
                    }
                    long now = Math.max(head.observedAt(), clock.millis());
                    var state =
                            new State(stream, head, loadRows(connection, stream, head, now), now);
                    publish(state);
                    T result = operation.apply(connection, state);
                    connection.commit();
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
            } catch (GovernanceException refused) {
                throw refused;
            } catch (RuntimeException invalid) {
                throw failed();
            }
        }
        throw unavailable();
    }

    private void acknowledged(String stream) {
        locked(stream, false, (connection, state) -> null);
    }

    private String fingerprint(AuditKeys.Version key, String id, byte[] metadata, byte[] body) {
        return mac(
                key,
                List.of("AGT-AUDIT-EVENT-V1", id, hash(metadata), body == null ? "" : hash(body)));
    }

    private Row append(
            Connection connection,
            State state,
            String id,
            String kind,
            byte[] metadata,
            byte[] body)
            throws SQLException {
        if (metadata.length > 32768
                || (body != null && body.length > policy.bodies().maxStoredBytes()))
            throw new GovernanceException("audit_payload_limit");
        for (var old : state.rows())
            if (old.eventId().equals(id)) {
                if (!old.kind().equals(kind)
                        || !equal(
                                old.fingerprint(),
                                fingerprint(key(old.keyId()), id, metadata, body)))
                    throw new GovernanceException("audit_event_replay_mismatch");
                return old;
            }
        if (state.head().seq() >= policy.maxEntriesPerStream())
            throw new GovernanceException("audit_capacity_exceeded");
        var key = active();
        long seq = state.head().seq() + 1;
        var skeleton =
                new Row(
                        seq,
                        id,
                        kind,
                        key.id(),
                        state.now(),
                        Math.addExact(state.now(), policy.bodyRetentionMillis()),
                        Math.addExact(state.now(), policy.recordRetentionMillis()),
                        "",
                        "",
                        state.head().hash(),
                        "",
                        fingerprint(key, id, metadata, body),
                        null,
                        null);
        byte[] encryptedMetadata =
                encrypt(key, aad(state.stream(), skeleton, "METADATA"), metadata);
        byte[] encryptedBody =
                body == null ? null : encrypt(key, aad(state.stream(), skeleton, "BODY"), body);
        try (var size =
                connection.prepareStatement(
                        "SELECT COALESCE(SUM(COALESCE(OCTET_LENGTH(metadata_cipher),0)+COALESCE(OCTET_LENGTH(body_cipher),0)),0) FROM agt_audit_entry WHERE catalog_id=? AND stream_id=?")) {
            size.setQueryTimeout(timeout);
            size.setString(1, catalog);
            size.setString(2, state.stream());
            try (var rows = size.executeQuery()) {
                if (!rows.next()) throw failed();
                long budget = policy.maxRetainedBytes() - ("SDK_EVENT".equals(kind) ? 65536 : 0);
                if (rows.getLong(1)
                                + encryptedMetadata.length
                                + (encryptedBody == null ? 0 : encryptedBody.length)
                        > budget) throw new GovernanceException("audit_capacity_exceeded");
            }
        }
        var unsigned =
                new Row(
                        seq,
                        id,
                        kind,
                        key.id(),
                        skeleton.createdAt(),
                        skeleton.bodyExpires(),
                        skeleton.recordExpires(),
                        hash(encryptedMetadata),
                        encryptedBody == null ? "" : hash(encryptedBody),
                        skeleton.previous(),
                        "",
                        skeleton.fingerprint(),
                        encryptedMetadata,
                        encryptedBody);
        var row =
                new Row(
                        seq,
                        id,
                        kind,
                        key.id(),
                        unsigned.createdAt(),
                        unsigned.bodyExpires(),
                        unsigned.recordExpires(),
                        unsigned.metadataHash(),
                        unsigned.bodyHash(),
                        unsigned.previous(),
                        rowMac(state.stream(), unsigned),
                        unsigned.fingerprint(),
                        encryptedMetadata,
                        encryptedBody);
        try (var insert =
                connection.prepareStatement(
                        "INSERT INTO agt_audit_entry VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            insert.setQueryTimeout(timeout);
            insert.setString(1, catalog);
            insert.setString(2, state.stream());
            insert.setLong(3, row.seq());
            insert.setString(4, id);
            insert.setString(5, kind);
            insert.setString(6, key.id());
            insert.setLong(7, row.createdAt());
            insert.setLong(8, row.bodyExpires());
            insert.setLong(9, row.recordExpires());
            insert.setString(10, row.metadataHash());
            insert.setString(11, row.bodyHash());
            insert.setString(12, row.previous());
            insert.setString(13, row.hash());
            insert.setString(14, row.fingerprint());
            insert.setBytes(15, row.metadata());
            insert.setBytes(16, row.body());
            insert.executeUpdate();
        }
        saveHead(
                connection,
                state.stream(),
                new Head(
                        seq,
                        row.hash(),
                        state.now(),
                        key.id(),
                        seal(state.stream(), seq, row.hash(), state.now(), key)),
                false);
        return row;
    }

    public AuditCheckpoint append(AuditEvent event) {
        Objects.requireNonNull(event);
        if (event.body().content() != null
                && !policy.bodies().points().contains(event.record().point()))
            throw new GovernanceException("audit_capture_policy_denied");
        String stream = stream(event.scope().tenantId(), event.scope().principalId());
        byte[] metadata = json(AuditEncoding.metadata(event));
        byte[] body = event.body().content() == null ? null : json(event.body().content());
        var row =
                locked(
                        stream,
                        true,
                        (connection, state) ->
                                append(
                                        connection,
                                        state,
                                        event.eventId(),
                                        "SDK_EVENT",
                                        metadata,
                                        body));
        acknowledged(stream);
        return checkpoint(stream, row.seq(), row.hash());
    }

    private void authorize(TrustedContext actor, String permission) {
        if (!actor.permissions().contains(permission))
            throw new GovernanceException("audit_access_denied");
    }

    private Map<String, Object> administrative(
            TrustedContext actor, String operation, Object target) {
        return Map.of(
                "actorAgentId",
                actor.agentId(),
                "tenantId",
                actor.tenantId(),
                "principalId",
                actor.principalId(),
                "operation",
                operation,
                "target",
                target);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decode(State state, Row row) {
        Object decoded =
                decrypt(key(row.keyId()), aad(state.stream(), row, "METADATA"), row.metadata());
        if (!(decoded instanceof Map<?, ?>)) throw failed();
        return (Map<String, Object>) decoded;
    }

    public List<RecordView> readMetadata(TrustedContext actor, long after, int limit) {
        authorize(actor, "governance:audit:read");
        if (after < 0 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid audit page");
        String stream = stream(actor), id = UUID.randomUUID().toString();
        var result =
                locked(
                        stream,
                        true,
                        (connection, state) -> {
                            var selected = new ArrayList<RecordView>();
                            for (var row : state.rows())
                                if (row.seq() > after
                                        && row.recordExpires() > state.now()
                                        && selected.size() < limit)
                                    selected.add(
                                            new RecordView(
                                                    row.seq(),
                                                    row.eventId(),
                                                    row.kind(),
                                                    row.recordExpires(),
                                                    row.bodyExpires(),
                                                    decode(state, row),
                                                    row.body() != null
                                                            && row.bodyExpires() > state.now()));
                            append(
                                    connection,
                                    state,
                                    id,
                                    "ACCESS",
                                    json(
                                            administrative(
                                                    actor,
                                                    "metadata",
                                                    Map.of("after", after, "limit", limit))),
                                    null);
                            return List.copyOf(selected);
                        });
        acknowledged(stream);
        return result;
    }

    public Object readBody(TrustedContext actor, long sequence) {
        authorize(actor, "governance:audit:read");
        authorize(actor, "governance:audit:body");
        if (sequence < 1) throw new IllegalArgumentException("Invalid audit sequence");
        String stream = stream(actor), id = UUID.randomUUID().toString();
        var result =
                locked(
                        stream,
                        true,
                        (connection, state) -> {
                            var selected =
                                    state.rows().stream()
                                            .filter(row -> row.seq() == sequence)
                                            .findFirst();
                            boolean available =
                                    selected.isPresent()
                                            && selected.get().body() != null
                                            && selected.get().bodyExpires() > state.now()
                                            && selected.get().recordExpires() > state.now();
                            Object value =
                                    available
                                            ? decrypt(
                                                    key(selected.get().keyId()),
                                                    aad(stream, selected.get(), "BODY"),
                                                    selected.get().body())
                                            : null;
                            append(
                                    connection,
                                    state,
                                    id,
                                    "ACCESS",
                                    json(
                                            administrative(
                                                    actor,
                                                    "body",
                                                    Map.of(
                                                            "sequence",
                                                            sequence,
                                                            "available",
                                                            available))),
                                    null);
                            return new BodyResult(
                                    value, available ? null : "audit_body_unavailable");
                        });
        acknowledged(stream);
        if (result.refusal() != null) throw new GovernanceException(result.refusal());
        return result.value();
    }

    public int purgeExpired(TrustedContext actor) {
        authorize(actor, "governance:audit:purge");
        String stream = stream(actor), id = UUID.randomUUID().toString();
        int count =
                locked(
                        stream,
                        true,
                        (connection, state) -> {
                            int removed = 0;
                            for (var row : state.rows()) {
                                boolean metadata =
                                        row.metadata() != null
                                                && row.recordExpires() <= state.now();
                                boolean body =
                                        row.body() != null && row.bodyExpires() <= state.now();
                                if (!metadata && !body) continue;
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE agt_audit_entry SET metadata_cipher=?,body_cipher=? WHERE catalog_id=? AND stream_id=? AND seq=?")) {
                                    update.setQueryTimeout(timeout);
                                    update.setBytes(1, metadata ? null : row.metadata());
                                    update.setBytes(2, body ? null : row.body());
                                    update.setString(3, catalog);
                                    update.setString(4, stream);
                                    update.setLong(5, row.seq());
                                    if (update.executeUpdate() != 1) throw failed();
                                    removed++;
                                }
                            }
                            append(
                                    connection,
                                    state,
                                    id,
                                    "RETENTION",
                                    json(
                                            administrative(
                                                    actor,
                                                    "purge",
                                                    Map.of("removedPayloadRows", removed))),
                                    null);
                            return removed;
                        });
        acknowledged(stream);
        return count;
    }

    public AuditCheckpoint verify(TrustedContext actor, AuditCheckpoint expected) {
        authorize(actor, "governance:audit:read");
        String stream = stream(actor);
        return locked(
                stream,
                false,
                (connection, state) -> {
                    var actual =
                            state == null
                                    ? checkpoint(stream, 0, genesis(stream))
                                    : checkpoint(stream, state.head().seq(), state.head().hash());
                    if (expected != null
                            && (!expected.catalogId().equals(catalog)
                                    || !expected.streamId().equals(stream)
                                    || expected.sequence() > actual.sequence()
                                    || !expected.hash()
                                            .equals(
                                                    expected.sequence() == 0
                                                            ? genesis(stream)
                                                            : state.rows()
                                                                    .get(
                                                                            Math.toIntExact(
                                                                                    expected
                                                                                                    .sequence()
                                                                                            - 1))
                                                                    .hash())))
                        throw new GovernanceException("audit_checkpoint_mismatch");
                    return actual;
                });
    }

    private GovernanceException unavailable() {
        return new GovernanceException("audit_store_unavailable");
    }
}
