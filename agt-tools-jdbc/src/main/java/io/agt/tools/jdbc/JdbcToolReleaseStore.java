package io.agt.tools.jdbc;

import static io.agt.tools.jdbc.ToolReleaseState.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import io.agt.audit.jdbc.*;
import io.agt.core.*;
import io.agt.core.audit.*;
import io.agt.core.storage.*;
import io.agt.core.tools.*;
import io.agt.identity.AuthenticatedActor;
import java.io.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * 单租户持久发布目录：固定版本、独立人工审核、不可逆撤销和独立见证。
 *
 * <p>目录不保存制品字节；运行时只获得在线校验能力，不能从模型参数生成发布权限。
 */
public final class JdbcToolReleaseStore implements ToolReviewAuthority, AutoCloseable {
    private record Row(
            long seq, String key, String previous, String hash, byte[] payload, State state) {}

    private record Change<T>(State state, T result, boolean changed, boolean revocation) {}

    private final DataSource data;
    private final String name, witnessCatalog, identity, stream;
    private final ToolReleasePolicy policy;
    private final StorageKeys keys;
    private final AuditWitness witness;
    private final Clock clock;
    private final ObjectMapper json =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(64)
                                                    .maxStringLength(65536)
                                                    .maxNumberLength(128)
                                                    .build())
                                    .build())
                    .enable(
                            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                            DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                            DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private final ThreadPoolExecutor workers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long started = System.nanoTime();
    private final long startTime;
    private final AtomicLong observed;

    public JdbcToolReleaseStore(
            DataSource data,
            String name,
            String witnessCatalog,
            ToolReleasePolicy policy,
            StorageKeys keys,
            AuditWitness independentWitness,
            Clock clock) {
        this.data = Objects.requireNonNull(data);
        this.name = Identifiers.require(name);
        this.witnessCatalog = Identifiers.require(witnessCatalog);
        this.policy = Objects.requireNonNull(policy);
        this.keys = Objects.requireNonNull(keys);
        witness = Objects.requireNonNull(independentWitness);
        this.clock = Objects.requireNonNull(clock);
        startTime = clock.millis();
        observed = new AtomicLong(startTime);
        identity =
                JsonValues.digest(
                        List.of(
                                "AGT-TOOL-RELEASE-STORE-V1",
                                name,
                                witnessCatalog,
                                policy.identity()));
        stream =
                StorageCrypto.hash(
                        StorageCrypto.json(List.of("AGT-TOOL-RELEASE-STREAM-V1", identity)));
        workers =
                new ThreadPoolExecutor(
                        2,
                        2,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(16),
                        r -> {
                            var thread = new Thread(r, "agt-tool-review");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public String identity() {
        return identity;
    }

    public void initializeSchema() {
        query(
                () -> {
                    try (var c = data.getConnection();
                            var s = c.createStatement()) {
                        s.setQueryTimeout(sqlTimeout());
                        s.executeUpdate(
                                "CREATE TABLE IF NOT EXISTS agt_tool_releases(catalog_name VARCHAR(128) PRIMARY KEY, authority_id VARCHAR(80) NOT NULL, seq BIGINT NOT NULL, key_id VARCHAR(128), previous_hash VARCHAR(64), state_hash VARCHAR(64), payload BLOB)");
                    }
                    return null;
                });
    }

    /** 发布提案，不等于批准；同一 provider/name/version 永远不能替换描述。 */
    public String submit(AuthenticatedActor publisher, ToolDescriptor descriptor) {
        requireDescriptor(descriptor);
        return manage(
                publisher,
                policy.publishers(),
                false,
                () ->
                        transact(
                                () -> authorize(publisher, policy.publishers(), false),
                                state -> {
                                    var prior =
                                            state.releases().stream()
                                                    .filter(
                                                            r ->
                                                                    slot(r.descriptor())
                                                                            .equals(
                                                                                    slot(
                                                                                            descriptor)))
                                                    .findFirst();
                                    if (prior.isPresent()) {
                                        var old = prior.get();
                                        if (!old.descriptor().equals(descriptor)
                                                || !old.publisher()
                                                        .equals(publisher.principalId())) {
                                            throw denied("tool_release_conflict");
                                        }
                                        if (old.status() != Status.PENDING
                                                && old.status() != Status.APPROVED) {
                                            throw denied("tool_release_terminal");
                                        }
                                        return unchanged(state, descriptor.identity());
                                    }
                                    if (state.releases().size() >= policy.maxReleases()) {
                                        throw denied("tool_release_capacity");
                                    }
                                    long now = now(state);
                                    var release =
                                            new Release(
                                                    descriptor,
                                                    publisher.principalId(),
                                                    Status.PENDING,
                                                    List.of(),
                                                    List.of(
                                                            new Event(
                                                                    "SUBMIT",
                                                                    publisher.principalId(),
                                                                    now,
                                                                    descriptor.identity())),
                                                    null);
                                    return changed(state, release, descriptor.identity(), false);
                                }));
    }

    /** 必须先持久确认查看事件，才返回包含审核正文的私有能力。 */
    public ToolReleaseReview review(AuthenticatedActor reviewer, String binding) {
        return manage(
                reviewer,
                policy.reviewers(),
                true,
                () ->
                        transact(
                                () -> authorize(reviewer, policy.reviewers(), true),
                                state -> {
                                    var release = release(state, binding);
                                    if (release.status() != Status.PENDING) {
                                        throw denied("tool_release_not_pending");
                                    }
                                    if (release.publisher().equals(reviewer.principalId())) {
                                        throw denied("tool_release_self_review");
                                    }
                                    if (release.views().size() >= policy.maxReviews()) {
                                        throw denied("tool_release_review_capacity");
                                    }
                                    long now = now(state),
                                            expires =
                                                    Math.addExact(
                                                            now,
                                                            Math.min(
                                                                    300000,
                                                                    reviewer.remainingMillis()));
                                    if (expires <= now) {
                                        throw denied("tool_release_review_expired");
                                    }
                                    String id = UUID.randomUUID().toString(),
                                            secret =
                                                    UUID.randomUUID().toString()
                                                            + UUID.randomUUID();
                                    var view =
                                            new View(
                                                    id,
                                                    reviewer.principalId(),
                                                    reviewer.tokenIdentity(),
                                                    JsonValues.digest(secret),
                                                    expires);
                                    var views = new ArrayList<>(release.views());
                                    views.add(view);
                                    var events = new ArrayList<>(release.events());
                                    events.add(
                                            new Event("REVIEW", reviewer.principalId(), now, id));
                                    var next =
                                            new Release(
                                                    release.descriptor(),
                                                    release.publisher(),
                                                    release.status(),
                                                    List.copyOf(views),
                                                    List.copyOf(events),
                                                    null);
                                    var result =
                                            new ToolReleaseReview(
                                                    identity,
                                                    binding,
                                                    reviewer.principalId(),
                                                    reviewer.tokenIdentity(),
                                                    id,
                                                    secret,
                                                    expires,
                                                    release.descriptor());
                                    return changed(state, next, result, false);
                                }));
    }

    public void decide(AuthenticatedActor reviewer, ToolReleaseReview review, boolean approved) {
        Objects.requireNonNull(review);
        manage(
                reviewer,
                policy.reviewers(),
                true,
                () ->
                        transact(
                                () -> authorize(reviewer, policy.reviewers(), true),
                                state -> {
                                    var release = release(state, review.binding);
                                    if (!identity.equals(review.authority)
                                            || !reviewer.principalId().equals(review.reviewer)
                                            || !reviewer.tokenIdentity().equals(review.token)
                                            || !release.descriptor().equals(review.descriptor())
                                            || release.publisher().equals(reviewer.principalId())) {
                                        throw denied("tool_release_review_denied");
                                    }
                                    String proof = JsonValues.digest(review.secret);
                                    var decision =
                                            new Decision(
                                                    review.id,
                                                    reviewer.principalId(),
                                                    proof,
                                                    approved);
                                    if (release.decision() != null
                                            && release.decision().equals(decision)
                                            && release.status()
                                                    == (approved
                                                            ? Status.APPROVED
                                                            : Status.REJECTED)) {
                                        return unchanged(state, null);
                                    }
                                    var view =
                                            release.views().stream()
                                                    .filter(v -> v.id().equals(review.id))
                                                    .findFirst()
                                                    .orElseThrow(
                                                            () ->
                                                                    denied(
                                                                            "tool_release_review_denied"));
                                    long now = now(state);
                                    if (release.status() != Status.PENDING
                                            || now >= view.expires()
                                            || review.expires != view.expires()
                                            || !view.actor().equals(reviewer.principalId())
                                            || !view.token().equals(reviewer.tokenIdentity())
                                            || !StorageCrypto.equal(proof, view.secretHash())) {
                                        throw denied("tool_release_review_denied");
                                    }
                                    var events = new ArrayList<>(release.events());
                                    events.add(
                                            new Event(
                                                    approved ? "APPROVE" : "REJECT",
                                                    reviewer.principalId(),
                                                    now,
                                                    review.id));
                                    return changed(
                                            state,
                                            new Release(
                                                    release.descriptor(),
                                                    release.publisher(),
                                                    approved ? Status.APPROVED : Status.REJECTED,
                                                    release.views(),
                                                    List.copyOf(events),
                                                    decision),
                                            null,
                                            false);
                                }));
    }

    /** 撤销独立授权，允许受信自动化主体收窄权限；同版本不能再次发布或批准。 */
    public void revoke(AuthenticatedActor revoker, String binding) {
        manage(
                revoker,
                policy.revokers(),
                false,
                () ->
                        transact(
                                () -> authorize(revoker, policy.revokers(), false),
                                state -> {
                                    var release = release(state, binding);
                                    if (release.status() == Status.REVOKED) {
                                        return unchanged(state, null);
                                    }
                                    var events = new ArrayList<>(release.events());
                                    events.add(
                                            new Event(
                                                    "REVOKE",
                                                    revoker.principalId(),
                                                    now(state),
                                                    binding));
                                    return changed(
                                            state,
                                            new Release(
                                                    release.descriptor(),
                                                    release.publisher(),
                                                    Status.REVOKED,
                                                    release.views(),
                                                    List.copyOf(events),
                                                    release.decision()),
                                            null,
                                            true);
                                }));
    }

    /** 管理目录不返回完整描述；查看、决策、撤销均以稳定绑定摘要寻址。 */
    public List<ToolReleaseSummary> list(AuthenticatedActor actor, AuditSink accessAudit) {
        return readManagement(
                actor,
                "tool_release_list",
                identity,
                accessAudit,
                state ->
                        state.releases().stream()
                                .map(
                                        r ->
                                                new ToolReleaseSummary(
                                                        r.descriptor().identity(),
                                                        r.descriptor().name(),
                                                        r.descriptor().provider(),
                                                        r.descriptor().version(),
                                                        r.status().name()))
                                .toList());
    }

    /** 先完成独立访问审计再返回描述和历史；该投影不能创建批准能力。 */
    public ToolReleaseInfo inspect(
            AuthenticatedActor actor, String binding, AuditSink accessAudit) {
        return readManagement(
                actor,
                "tool_release_inspect",
                binding,
                accessAudit,
                state -> {
                    var release = release(state, binding);
                    return new ToolReleaseInfo(
                            release.descriptor(),
                            release.publisher(),
                            release.status().name(),
                            release.events().stream()
                                    .map(
                                            e ->
                                                    new ToolReleaseInfo.Event(
                                                            e.kind(),
                                                            e.actor(),
                                                            e.time(),
                                                            e.reference()))
                                    .toList());
                });
    }

    private <T> T readManagement(
            AuthenticatedActor actor,
            String operation,
            String resource,
            AuditSink accessAudit,
            Function<State, T> projection) {
        Objects.requireNonNull(accessAudit);
        return manage(
                actor,
                policy.reviewers(),
                true,
                () -> {
                    long start = System.nanoTime();
                    T value =
                            transact(
                                    actor::checkActive,
                                    state -> unchanged(state, projection.apply(state)));
                    String request = UUID.randomUUID().toString();
                    String input = JsonValues.digest(List.of(identity, resource));
                    String output = "sha256:" + StorageCrypto.hash(encode(value));
                    var record =
                            new AuditRecord(
                                    2,
                                    clock.instant(),
                                    request,
                                    "release-admin",
                                    request,
                                    InterventionPoint.OUTPUT,
                                    EnforcementMode.ENFORCE,
                                    "tool-release-management-v1",
                                    "allow",
                                    operation,
                                    false,
                                    "not_requested",
                                    "succeeded",
                                    input,
                                    output,
                                    System.nanoTime() - start,
                                    List.of("tool_review_authority:" + identity),
                                    null,
                                    identity);
                    try {
                        accessAudit.write(
                                new AuditEvent(
                                        request,
                                        new AuditScope(
                                                actor.tenantId(),
                                                actor.principalId(),
                                                identity,
                                                request),
                                        record,
                                        AuditBody.omitted("disabled")));
                    } catch (Exception failure) {
                        throw denied("tool_release_access_audit_failed");
                    }
                    return value;
                });
    }

    @Override
    public void check(ToolDescriptor descriptor) {
        requireDescriptor(descriptor);
        query(
                () ->
                        transact(
                                () -> {},
                                state -> {
                                    var release = release(state, descriptor.identity());
                                    if (!release.descriptor().equals(descriptor)
                                            || release.status() != Status.APPROVED) {
                                        throw denied(
                                                release.status() == Status.REVOKED
                                                        ? "tool_release_revoked"
                                                        : "tool_release_unapproved");
                                    }
                                    return unchanged(state, null);
                                }));
    }

    @Override
    public void validateContext(TrustedContext context) {
        if (!policy.tenantId().equals(context.tenantId())) {
            throw denied("tool_release_tenant_denied");
        }
    }

    private void requireDescriptor(ToolDescriptor descriptor) {
        if (!identity.equals(Objects.requireNonNull(descriptor).reviewAuthority())) {
            throw denied("tool_review_authority_required");
        }
    }

    private <T> T manage(
            AuthenticatedActor actor,
            Set<String> permissions,
            boolean human,
            Callable<T> operation) {
        Objects.requireNonNull(actor);
        return query(
                () -> {
                    authorize(actor, permissions, human);
                    T result = operation.call();
                    authorize(actor, permissions, human);
                    return result;
                });
    }

    private void authorize(AuthenticatedActor actor, Set<String> permissions, boolean human) {
        if (!policy.identityPolicyId().equals(actor.identityPolicyId())
                || !policy.tenantId().equals(actor.tenantId())) {
            throw denied("tool_release_actor_denied");
        }
        var current = actor.context("release-authorizer");
        if (!current.permissions().containsAll(permissions)) {
            throw denied("identity_permission_denied");
        }
        if (human) {
            actor.requireHuman();
        }
    }

    private long now(State state) {
        long time =
                Math.max(
                        clock.millis(),
                        Math.addExact(
                                startTime,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        return observed.accumulateAndGet(Math.max(time, state.observed()), Math::max);
    }

    private List<String> slot(ToolDescriptor descriptor) {
        return List.of(descriptor.provider(), descriptor.name(), descriptor.version());
    }

    private Release release(State state, String binding) {
        if (binding == null || !binding.matches("sha256:[0-9a-f]{64}")) {
            throw denied("tool_release_unknown");
        }
        return state.releases().stream()
                .filter(r -> r.descriptor().identity().equals(binding))
                .findFirst()
                .orElseThrow(() -> denied("tool_release_unknown"));
    }

    private <T> Change<T> unchanged(State state, T result) {
        return new Change<>(state, result, false, false);
    }

    private <T> Change<T> changed(State state, Release entry, T result, boolean revocation) {
        var entries = new ArrayList<>(state.releases());
        entries.removeIf(r -> r.descriptor().identity().equals(entry.descriptor().identity()));
        entries.add(entry);
        entries.sort(Comparator.comparing(r -> r.descriptor().identity()));
        return new Change<>(
                new State(identity, List.copyOf(entries), state.history(), now(state)),
                result,
                true,
                revocation);
    }

    private <T> T transact(Runnable authorization, Function<State, Change<T>> operation) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Row saved;
            T result = null;
            boolean initialized = false;
            try (var c = data.getConnection()) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                c.setAutoCommit(false);
                try {
                    if (!exists(c)) {
                        if (witness.read(witnessCatalog, stream) != null) {
                            throw integrity();
                        }
                        try (var insert =
                                statement(
                                        c,
                                        "INSERT INTO agt_tool_releases(catalog_name,authority_id,seq) VALUES(?,?,-1)")) {
                            insert.setString(1, name);
                            insert.setString(2, identity);
                            insert.executeUpdate();
                        } catch (SQLException conflict) {
                            if ("23505".equals(conflict.getSQLState())) {
                                c.rollback();
                                continue;
                            }
                            throw conflict;
                        }
                    }
                    Row old = readLocked(c);
                    if (old == null) {
                        if (witness.read(witnessCatalog, stream) != null) {
                            throw integrity();
                        }
                        saved =
                                save(
                                        c,
                                        null,
                                        new State(identity, List.of(), List.of(), observed.get()));
                        c.commit();
                        initialized = true;
                    } else {
                        confirm(old);
                        var change = operation.apply(old.state());
                        result = change.result();
                        if (!change.changed()) {
                            c.commit();
                            return result;
                        }
                        var history = new ArrayList<>(old.state().history());
                        history.add(old.hash());
                        var next =
                                new State(
                                        identity,
                                        change.state().releases(),
                                        List.copyOf(history),
                                        change.state().observed());
                        validate(next, old.seq() + 1);
                        int length = encode(next).length;
                        int limit =
                                change.revocation()
                                        ? policy.maxStateBytes()
                                        : policy.maxStateBytes() - policy.maxReleases() * 2048;
                        if (length > limit) {
                            throw denied("tool_release_capacity");
                        }
                        authorization.run();
                        saved = save(c, old, next);
                        authorization.run();
                        c.commit();
                    }
                } catch (Exception failure) {
                    rollback(c);
                    throw map(failure);
                }
            } catch (SQLException failure) {
                throw map(failure);
            }
            // 必须先释放主连接，再确认；单连接池也能完成首次目录创建。
            publish(saved);
            if (initialized) {
                continue;
            }
            return result;
        }
        throw denied("tool_release_conflict");
    }

    private boolean exists(Connection c) throws SQLException {
        try (var q =
                statement(c, "SELECT catalog_name FROM agt_tool_releases WHERE catalog_name=?")) {
            q.setString(1, name);
            try (var rows = q.executeQuery()) {
                return rows.next();
            }
        }
    }

    private Row readLocked(Connection c) throws Exception {
        try (var q =
                statement(
                        c,
                        "SELECT authority_id,seq,key_id,previous_hash,state_hash,payload FROM agt_tool_releases WHERE catalog_name=? FOR UPDATE")) {
            q.setString(1, name);
            try (var rows = q.executeQuery()) {
                if (!rows.next() || !identity.equals(rows.getString(1))) {
                    throw integrity();
                }
                long seq = rows.getLong(2);
                if (seq == -1) {
                    if (rows.getString(3) != null
                            || rows.getString(4) != null
                            || rows.getString(5) != null
                            || rows.getBinaryStream(6) != null) {
                        throw integrity();
                    }
                    return null;
                }
                String keyId = rows.getString(3),
                        previous = rows.getString(4),
                        hash = rows.getString(5);
                byte[] payload;
                try (var input = rows.getBinaryStream(6)) {
                    if (input == null) {
                        throw integrity();
                    }
                    payload = input.readNBytes(policy.maxStateBytes() + 29);
                }
                if (seq < 0 || seq > maxEvents() || payload.length > policy.maxStateBytes() + 28) {
                    throw integrity();
                }
                var key = Objects.requireNonNull(keys.find(keyId));
                if (!keyId.equals(key.id())) {
                    throw integrity();
                }
                var aad = aad(seq, keyId, previous);
                if (!StorageCrypto.equal(
                        hash,
                        StorageCrypto.mac(
                                key.authentication(), List.of(aad, StorageCrypto.hash(payload))))) {
                    throw integrity();
                }
                byte[] plain = StorageCrypto.decrypt(key.encryption(), aad, payload);
                if (plain.length > policy.maxStateBytes()) {
                    throw integrity();
                }
                var state = json.readValue(plain, State.class);
                validate(state, seq);
                if (seq == 0
                        ? !previous.equals("0".repeat(64))
                        : !previous.equals(state.history().get((int) seq - 1))) {
                    throw integrity();
                }
                return new Row(seq, keyId, previous, hash, payload, state);
            }
        }
    }

    private Row save(Connection c, Row old, State state) throws SQLException {
        long seq = old == null ? 0 : old.seq() + 1;
        var key = keys.active();
        String previous = old == null ? "0".repeat(64) : old.hash();
        var aad = aad(seq, key.id(), previous);
        byte[] payload = StorageCrypto.encrypt(key.encryption(), aad, encode(state));
        String hash =
                StorageCrypto.mac(key.authentication(), List.of(aad, StorageCrypto.hash(payload)));
        try (var update =
                statement(
                        c,
                        "UPDATE agt_tool_releases SET seq=?,key_id=?,previous_hash=?,state_hash=?,payload=? WHERE catalog_name=? AND authority_id=?")) {
            update.setLong(1, seq);
            update.setString(2, key.id());
            update.setString(3, previous);
            update.setString(4, hash);
            update.setBytes(5, payload);
            update.setString(6, name);
            update.setString(7, identity);
            if (update.executeUpdate() != 1) {
                throw integrity();
            }
        }
        return new Row(seq, key.id(), previous, hash, payload, state);
    }

    private byte[] encode(Object state) {
        try {
            byte[] encoded = json.writeValueAsBytes(state);
            if (encoded.length > policy.maxStateBytes()) {
                throw denied("tool_release_capacity");
            }
            return encoded;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw integrity();
        }
    }

    private Object aad(long seq, String key, String previous) {
        return List.of(
                "AGT-TOOL-RELEASE-STATE-V1", identity, name, witnessCatalog, seq, key, previous);
    }

    private AuditCheckpoint checkpoint(Row row) {
        return new AuditCheckpoint(witnessCatalog, stream, row.seq(), row.hash());
    }

    private void confirm(Row row) {
        var target = checkpoint(row);
        var old = witness.read(witnessCatalog, stream);
        if (target.equals(old)) {
            return;
        }
        if (old == null
                ? row.seq() != 0
                : old.sequence() != row.seq() - 1
                        || !StorageCrypto.equal(old.hash(), row.previous())) {
            throw integrity();
        }
        if (!witness.advance(old, target) && !target.equals(witness.read(witnessCatalog, stream))) {
            throw denied("tool_release_witness_conflict");
        }
        if (!target.equals(witness.read(witnessCatalog, stream))) {
            throw denied("tool_release_witness_unconfirmed");
        }
    }

    private void publish(Row saved) {
        try (var c = data.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                var current = readLocked(c);
                if (current == null
                        || current.seq() < saved.seq()
                        || (current.seq() == saved.seq()
                                ? !StorageCrypto.equal(current.hash(), saved.hash())
                                : !StorageCrypto.equal(
                                        current.state().history().get((int) saved.seq()),
                                        saved.hash()))) {
                    throw integrity();
                }
                confirm(current);
                c.commit();
            } catch (Exception failure) {
                rollback(c);
                throw map(failure);
            }
        } catch (SQLException failure) {
            throw map(failure);
        }
    }

    private void validate(State state, long seq) {
        ToolReleaseState.validate(state, seq, identity, policy);
    }

    private long maxEvents() {
        return (long) policy.maxReleases() * (policy.maxReviews() + 3);
    }

    private PreparedStatement statement(Connection c, String sql) throws SQLException {
        var s = c.prepareStatement(sql);
        s.setQueryTimeout(sqlTimeout());
        return s;
    }

    private int sqlTimeout() {
        return Math.max(1, (policy.operationTimeoutMillis() + 999) / 1000);
    }

    private <T> T query(Callable<T> operation) {
        if (closed.get()) {
            throw denied("tool_release_unavailable");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw denied("tool_release_cancelled");
        }
        Future<T> future;
        try {
            future = workers.submit(operation);
        } catch (RejectedExecutionException full) {
            throw denied("tool_release_capacity");
        }
        try {
            T result = future.get(policy.operationTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (closed.get()) {
                throw denied("tool_release_unavailable");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw denied("tool_release_cancelled");
            }
            return result;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw denied("tool_release_cancelled");
        } catch (TimeoutException slow) {
            throw denied("tool_release_timeout");
        } catch (ExecutionException failed) {
            throw map(failed.getCause());
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
            workers.remove((Runnable) future);
        }
    }

    private void rollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            /* 保留原始拒绝原因。 */
        }
    }

    private GovernanceException map(Throwable failure) {
        return failure instanceof GovernanceException denied
                ? denied
                : denied("tool_release_unavailable");
    }

    private GovernanceException integrity() {
        return denied("tool_release_integrity_denied");
    }

    private static GovernanceException denied(String code) {
        return new GovernanceException(code);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            workers.shutdownNow();
        }
    }
}
