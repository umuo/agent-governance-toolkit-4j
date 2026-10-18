package io.agt.approval.jdbc;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import io.agt.audit.jdbc.*;
import io.agt.core.*;
import io.agt.core.approval.*;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.storage.*;
import io.agt.identity.AuthenticatedActor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;

/** 加密、单次消费的 JDBC 审批；独立见证确认之前不交付正文或批准响应。 */
public final class JdbcApprovalStore {
    public enum Status {
        PENDING,
        APPROVED,
        DENIED,
        CONSUMED,
        CANCELLED,
        EXPIRED
    }

    public record Event(String operation, String principalId, String tokenIdentity, long at) {}

    public record Receipt(
            String requestId, String actionIdentity, Status status, List<Event> events) {
        public Receipt {
            events = List.copyOf(events);
        }

        public String toString() {
            return "ApprovalReceipt[redacted]";
        }
    }

    private record State(
            int version,
            String requestId,
            String actionIdentity,
            String policyVersion,
            TrustedContext owner,
            String sessionId,
            String actionId,
            String tool,
            String requesterToken,
            String claimHash,
            long expiresAt,
            long observed,
            Status status,
            ApprovalRequest request,
            String reviewer,
            String reviewerToken,
            String reviewHash,
            int reviews,
            List<Event> events,
            List<String> lineage) {
        State next(
                Status status,
                long now,
                Event event,
                ApprovalRequest body,
                String reviewer,
                String reviewerToken,
                String reviewHash,
                int reviews) {
            var history = new ArrayList<>(events);
            history.add(event);
            return new State(
                    version,
                    requestId,
                    actionIdentity,
                    policyVersion,
                    owner,
                    sessionId,
                    actionId,
                    tool,
                    requesterToken,
                    claimHash,
                    expiresAt,
                    now,
                    status,
                    body,
                    reviewer,
                    reviewerToken,
                    reviewHash,
                    reviews,
                    List.copyOf(history),
                    lineage);
        }

        State finish(Status status, long now, Event event) {
            return next(status, now, event, null, reviewer, reviewerToken, null, reviews);
        }

        State withLineage(List<String> lineage) {
            return new State(
                    version,
                    requestId,
                    actionIdentity,
                    policyVersion,
                    owner,
                    sessionId,
                    actionId,
                    tool,
                    requesterToken,
                    claimHash,
                    expiresAt,
                    observed,
                    status,
                    request,
                    reviewer,
                    reviewerToken,
                    reviewHash,
                    reviews,
                    events,
                    List.copyOf(lineage));
        }
    }

    private record Row(
            String id, long revision, String keyId, String previous, byte[] payload, State state) {}

    private record Change<T>(State state, T value, String error) {
        static <T> Change<T> same(T value) {
            return new Change<>(null, value, null);
        }

        static <T> Change<T> save(State state, T value) {
            return new Change<>(state, value, null);
        }

        static <T> Change<T> refused(State state, String error) {
            return new Change<>(state, null, error);
        }
    }

    private record Delivery(ApprovalResponse response, long expiresAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ZERO = "0".repeat(64);
    private static final int MAX_PAYLOAD = 131072;
    private final DataSource data;
    private final String name, catalog, policyId;
    private final ApprovalPolicy policy;
    private final StorageKeys keys;
    private final AuditWitness witness;
    private final Clock clock;
    private final int timeout;
    private final ObjectMapper json;
    private final StructuredPolicy runtime;

    public JdbcApprovalStore(
            DataSource data,
            String name,
            String reviewedCatalogId,
            ApprovalPolicy policy,
            StorageKeys keys,
            AuditWitness independentWitness,
            Clock clock,
            int statementTimeoutSeconds) {
        this.data = Objects.requireNonNull(data);
        this.name = Identifiers.require(name);
        catalog = Identifiers.require(reviewedCatalogId);
        this.policy = Objects.requireNonNull(policy);
        policyId = policy.identity();
        this.keys = Objects.requireNonNull(keys);
        witness = Objects.requireNonNull(independentWitness);
        this.clock = Objects.requireNonNull(clock);
        timeout = statementTimeoutSeconds;
        if (timeout < 1 || timeout > 30)
            throw new IllegalArgumentException("Invalid approval SQL timeout");
        runtime = new StructuredPolicy(policy.runtimePolicy());
        json =
                new ObjectMapper(
                        JsonFactory.builder()
                                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                .streamReadConstraints(
                                        StreamReadConstraints.builder()
                                                .maxNestingDepth(128)
                                                .maxStringLength(65536)
                                                .maxNumberLength(128)
                                                .build())
                                .build());
        json.enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        json.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public ApprovalPolicy policy() {
        return policy;
    }

    /** 仅部署时显式初始化；业务路径不会自动创建丢失的目录。 */
    public void initializeSchema() {
        try (var c = data.getConnection();
                var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_approval_catalog(store_name VARCHAR(128) PRIMARY KEY,catalog_id VARCHAR(128) NOT NULL,policy_id VARCHAR(80) NOT NULL,key_id VARCHAR(128) NOT NULL,seal VARCHAR(64) NOT NULL)");
            s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS agt_approval_request(store_name VARCHAR(128) NOT NULL,request_id VARCHAR(128) NOT NULL,revision BIGINT NOT NULL,key_id VARCHAR(128) NOT NULL,previous_hash VARCHAR(64) NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(store_name,request_id),FOREIGN KEY(store_name) REFERENCES agt_approval_catalog(store_name))");
            var key = Objects.requireNonNull(keys.active());
            try (var insert =
                    c.prepareStatement("INSERT INTO agt_approval_catalog VALUES(?,?,?,?,?)")) {
                insert.setQueryTimeout(timeout);
                insert.setString(1, name);
                insert.setString(2, catalog);
                insert.setString(3, policyId);
                insert.setString(4, key.id());
                insert.setString(5, seal(key));
                try {
                    insert.executeUpdate();
                } catch (SQLException duplicate) {
                    if (!"23505".equals(duplicate.getSQLState())) throw duplicate;
                }
            }
            c.setAutoCommit(false);
            lockCatalog(c);
            c.commit();
        } catch (SQLException failed) {
            throw unavailable();
        }
    }

    public ApprovalClaim submit(AuthenticatedActor requester, ApprovalRequest request) {
        requireProfile(requester);
        policy.requesterPermissions().forEach(requester::requirePermission);
        Objects.requireNonNull(request);
        Identifiers.require(request.requestId());
        var action = Objects.requireNonNull(request.action());
        if (action.point() != InterventionPoint.PRE_TOOL_CALL
                || !policy.reviewerPermissions().containsKey(action.toolName())
                || !policy.runtimePolicy().version().equals(request.policyVersion())
                || !request.actionIdentity()
                        .equals(ActionIdentity.of(request.policyVersion(), action))
                || !action.context().equals(requester.context(action.context().agentId())))
            throw denied("approval_request_binding_denied");
        runtime.validateEffective(action);
        if (!Objects.equals(
                policy.runtimePolicy().tools().get(action.toolName()).effect(),
                action.effect() == null ? null : action.effect().rule()))
            throw denied("approval_effect_binding_denied");
        if (encode(request).length > policy.maxRequestBytes())
            throw denied("approval_request_capacity");
        long now = requester.currentTimeMillis();
        if (request.expiresAtEpochMillis() <= now
                || request.expiresAtEpochMillis() - now > policy.maxPendingMillis()
                || request.expiresAtEpochMillis() - now > requester.remainingMillis())
            throw denied("approval_request_expiry_denied");
        String secret = secret();
        var state =
                new State(
                        1,
                        request.requestId(),
                        request.actionIdentity(),
                        request.policyVersion(),
                        action.context(),
                        action.sessionId(),
                        action.actionId(),
                        action.toolName(),
                        requester.tokenIdentity(),
                        hashSecret(secret),
                        request.expiresAtEpochMillis(),
                        now,
                        Status.PENDING,
                        request,
                        null,
                        null,
                        null,
                        0,
                        List.of(event("SUBMIT", requester, now)),
                        List.of());
        var claim = new ApprovalClaim(request.requestId(), request.actionIdentity(), secret);
        Row row;
        try (var c = data.getConnection()) {
            c.setAutoCommit(false);
            try {
                lockCatalog(c);
                requester.checkActive();
                if (read(c, request.requestId()) != null
                        || witness.read(catalog, stream(request.requestId())) != null)
                    throw denied("approval_request_replayed");
                try (var count =
                        c.prepareStatement(
                                "SELECT COUNT(*) FROM agt_approval_request WHERE store_name=?")) {
                    count.setQueryTimeout(timeout);
                    count.setString(1, name);
                    try (var rows = count.executeQuery()) {
                        rows.next();
                        if (rows.getLong(1) >= policy.maxRequests())
                            throw denied("approval_store_capacity");
                    }
                }
                row = protect(request.requestId(), 0, ZERO, state);
                write(c, row, true);
                c.commit();
            } catch (Exception failed) {
                rollback(c);
                throw map(failed);
            }
        } catch (SQLException failed) {
            throw unavailable();
        }
        publish(row);
        return claim;
    }

    public ApprovalReview review(AuthenticatedActor reviewer, String requestId) {
        requireProfile(reviewer);
        reviewer.requireHuman();
        ApprovalReview result =
                change(
                        requestId,
                        row -> {
                            var state = row.state;
                            requireReviewer(reviewer, state);
                            long now = now(reviewer, state);
                            if (expired(state, now))
                                return Change.refused(expire(state, now), "approval_expired");
                            if (state.status != Status.PENDING)
                                throw denied("approval_not_pending");
                            if (state.reviews >= policy.maxReviews())
                                throw denied("approval_review_capacity");
                            String secret = secret();
                            var next =
                                    state.next(
                                            Status.PENDING,
                                            now,
                                            event("VIEW", reviewer, now),
                                            state.request,
                                            reviewer.principalId(),
                                            reviewer.tokenIdentity(),
                                            hashSecret(secret),
                                            state.reviews + 1);
                            return Change.save(
                                    next, new ApprovalReview(state.request, next.reviews, secret));
                        });
        reviewer.requireHuman();
        if (reviewer.currentTimeMillis() >= result.request().expiresAtEpochMillis())
            throw denied("approval_expired");
        return result;
    }

    public void decide(AuthenticatedActor reviewer, ApprovalReview reviewed, boolean approve) {
        requireProfile(reviewer);
        reviewer.requireHuman();
        Objects.requireNonNull(reviewed);
        Objects.requireNonNull(reviewed.request());
        change(
                reviewed.request().requestId(),
                row -> {
                    var state = row.state;
                    requireReviewer(reviewer, state);
                    long now = now(reviewer, state);
                    if (expired(state, now))
                        return Change.refused(expire(state, now), "approval_expired");
                    if (state.status != Status.PENDING
                            || state.reviews != reviewed.revision()
                            || !state.actionIdentity.equals(reviewed.request().actionIdentity())
                            || !Objects.equals(state.reviewer, reviewer.principalId())
                            || !Objects.equals(state.reviewerToken, reviewer.tokenIdentity())
                            || !Objects.equals(state.request, reviewed.request())
                            || !StorageCrypto.equal(
                                    state.reviewHash, hashSecret(reviewed.reviewSecret()))
                            || !state.actionIdentity.equals(
                                    ActionIdentity.of(
                                            reviewed.request().policyVersion(),
                                            reviewed.request().action())))
                        throw denied("approval_review_binding_denied");
                    var status = approve ? Status.APPROVED : Status.DENIED;
                    var next =
                            state.next(
                                    status,
                                    now,
                                    event(approve ? "APPROVE" : "DENY", reviewer, now),
                                    approve ? state.request : null,
                                    state.reviewer,
                                    state.reviewerToken,
                                    null,
                                    state.reviews);
                    return Change.save(next, null);
                });
    }

    /** 只返回关联原请求的响应；批准先持久消费、确认见证，再交付，丢失响应不能重做。 */
    public Optional<ApprovalResponse> consume(AuthenticatedActor requester, ApprovalClaim claim) {
        requireProfile(requester);
        policy.requesterPermissions().forEach(requester::requirePermission);
        Delivery delivery =
                change(
                        claim.requestId(),
                        row -> {
                            var state = row.state;
                            requireClaim(state, claim);
                            if (!state.owner.equals(requester.context(state.owner.agentId()))
                                    || !state.requesterToken.equals(requester.tokenIdentity()))
                                throw denied("approval_requester_denied");
                            long now = now(requester, state);
                            if (expired(state, now))
                                return Change.refused(expire(state, now), "approval_expired");
                            return switch (state.status) {
                                case PENDING -> Change.same(null);
                                case APPROVED ->
                                        Change.save(
                                                state.finish(
                                                        Status.CONSUMED,
                                                        now,
                                                        event("CONSUME", requester, now)),
                                                new Delivery(
                                                        new ApprovalResponse(
                                                                state.requestId,
                                                                state.actionIdentity,
                                                                true),
                                                        state.expiresAt));
                                case DENIED ->
                                        Change.same(
                                                new Delivery(
                                                        new ApprovalResponse(
                                                                state.requestId,
                                                                state.actionIdentity,
                                                                false),
                                                        state.expiresAt));
                                default -> throw denied("approval_not_consumable");
                            };
                        });
        long deliveredAt = requester.currentTimeMillis();
        if (delivery != null && delivery.response.approved() && deliveredAt >= delivery.expiresAt)
            throw denied("approval_expired");
        return Optional.ofNullable(delivery == null ? null : delivery.response);
    }

    /** 私有租约只授予取消能力，撤销身份后的清理不需要恢复其执行权限。 */
    public void cancel(ApprovalClaim claim) {
        change(
                claim.requestId(),
                row -> {
                    var state = row.state;
                    requireClaim(state, claim);
                    if (state.status != Status.PENDING && state.status != Status.APPROVED)
                        return Change.same(null);
                    long now = Math.max(state.observed, clock.millis());
                    return Change.save(
                            state.finish(
                                    Status.CANCELLED,
                                    now,
                                    new Event(
                                            "CANCEL",
                                            state.owner.principalId(),
                                            state.requesterToken,
                                            now)),
                            null);
                });
    }

    /** 元数据收据；正文只经 review 交付，且必须先持久记录 VIEW。 */
    public Receipt receipt(AuthenticatedActor actor, String requestId) {
        requireProfile(actor);
        return change(
                requestId,
                row -> {
                    var state = row.state;
                    requireReader(actor, state);
                    long now = now(actor, state);
                    var next = expired(state, now) ? expire(state, now) : state;
                    next =
                            next.next(
                                    next.status,
                                    now,
                                    event("RECEIPT", actor, now),
                                    next.request,
                                    next.reviewer,
                                    next.reviewerToken,
                                    next.reviewHash,
                                    next.reviews);
                    if (next.events.size() > policy.maxReviews() + 8)
                        throw denied("approval_history_capacity");
                    return Change.save(
                            next,
                            new Receipt(
                                    next.requestId, next.actionIdentity, next.status, next.events));
                });
    }

    /** 可信宿主定时清理过期正文；只终止待审/已批请求，不授予或重新执行业务。 */
    public int expirePending() {
        var ids = new ArrayList<String>();
        try (var connection = data.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockCatalog(connection);
                try (var query =
                        connection.prepareStatement(
                                "SELECT request_id FROM agt_approval_request WHERE store_name=?")) {
                    query.setQueryTimeout(timeout);
                    query.setMaxRows(policy.maxRequests() + 1);
                    query.setString(1, name);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            if (ids.size() == policy.maxRequests()) throw integrity();
                            ids.add(rows.getString(1));
                        }
                    }
                }
                connection.commit();
            } catch (Exception failure) {
                rollback(connection);
                throw map(failure);
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
        int changed = 0;
        for (String id : ids) {
            boolean expired =
                    change(
                            id,
                            row -> {
                                long now = Math.max(row.state.observed, clock.millis());
                                return expired(row.state, now)
                                        ? Change.save(expire(row.state, now), true)
                                        : Change.same(false);
                            });
            if (expired) changed++;
        }
        return changed;
    }

    private void requireProfile(AuthenticatedActor actor) {
        Objects.requireNonNull(actor);
        actor.checkActive();
        if (!policy.identityPolicyId().equals(actor.identityPolicyId()))
            throw denied("approval_identity_profile_denied");
    }

    private void requireReviewer(AuthenticatedActor actor, State state) {
        actor.requireHuman();
        if (!state.owner.tenantId().equals(actor.tenantId())
                || state.owner.principalId().equals(actor.principalId()))
            throw denied("approval_reviewer_denied");
        policy.reviewerPermissions().get(state.tool).forEach(actor::requirePermission);
    }

    private void requireReader(AuthenticatedActor actor, State state) {
        if (state.owner.tenantId().equals(actor.tenantId())
                && state.owner.principalId().equals(actor.principalId())) actor.checkActive();
        else requireReviewer(actor, state);
    }

    private long now(AuthenticatedActor actor, State state) {
        return Math.max(state.observed, actor.currentTimeMillis());
    }

    private boolean expired(State state, long now) {
        return (state.status == Status.PENDING || state.status == Status.APPROVED)
                && now >= state.expiresAt;
    }

    private State expire(State state, long now) {
        return state.finish(
                Status.EXPIRED,
                now,
                new Event("EXPIRE", state.owner.principalId(), state.requesterToken, now));
    }

    private void requireClaim(State state, ApprovalClaim claim) {
        if (!state.actionIdentity.equals(claim.actionIdentity())
                || !StorageCrypto.equal(state.claimHash, hashSecret(claim.secret())))
            throw denied("approval_claim_denied");
    }

    private Event event(String operation, AuthenticatedActor actor, long at) {
        return new Event(operation, actor.principalId(), actor.tokenIdentity(), at);
    }

    private <T> T change(String requestId, Function<Row, Change<T>> operation) {
        Identifiers.require(requestId);
        Row saved = null;
        Change<T> result;
        try (var c = data.getConnection()) {
            c.setAutoCommit(false);
            try {
                lockCatalog(c);
                var row = read(c, requestId);
                if (row == null) throw denied("approval_request_missing");
                confirm(row);
                result = operation.apply(row);
                if (result.state != null) {
                    saved =
                            protect(
                                    requestId,
                                    Math.addExact(row.revision, 1),
                                    checkpoint(row).hash(),
                                    result.state);
                    write(c, saved, false);
                }
                c.commit();
            } catch (Exception failed) {
                rollback(c);
                throw map(failed);
            }
        } catch (SQLException failed) {
            throw unavailable();
        }
        if (saved != null) publish(saved);
        if (result.error != null) throw denied(result.error);
        return result.value;
    }

    private void lockCatalog(Connection c) throws SQLException {
        try (var q =
                c.prepareStatement(
                        "SELECT catalog_id,policy_id,key_id,seal FROM agt_approval_catalog WHERE store_name=? FOR UPDATE")) {
            q.setQueryTimeout(timeout);
            q.setString(1, name);
            try (var rows = q.executeQuery()) {
                if (!rows.next()
                        || !catalog.equals(rows.getString(1))
                        || !policyId.equals(rows.getString(2))) throw integrity();
                var key = key(rows.getString(3));
                if (!StorageCrypto.equal(seal(key), rows.getString(4))) throw integrity();
            }
        }
    }

    private String seal(StorageKeys.Version key) {
        return StorageCrypto.mac(
                key.authentication(),
                List.of("AGT-APPROVAL-CATALOG-V1", name, catalog, policyId, key.id()));
    }

    private StorageKeys.Version key(String id) {
        var key = keys.find(id);
        if (key == null || !id.equals(key.id())) throw integrity();
        return key;
    }

    private Object aad(String id, long revision, String keyId, String previous) {
        return List.of(
                "AGT-APPROVAL-ROW-V1", name, catalog, policyId, id, revision, keyId, previous);
    }

    private Row protect(String id, long revision, String previous, State state) {
        var lineage = new ArrayList<>(state.lineage);
        if (revision != 0) lineage.add(previous);
        state = state.withLineage(lineage);
        validate(state, id);
        if (state.lineage.size() != revision) throw integrity();
        var key = Objects.requireNonNull(keys.active());
        byte[] plain = encode(state);
        if (plain.length > MAX_PAYLOAD - 28) throw denied("approval_state_capacity");
        byte[] payload =
                StorageCrypto.encrypt(
                        key.encryption(), aad(id, revision, key.id(), previous), plain);
        return new Row(id, revision, key.id(), previous, payload, state);
    }

    private Row read(Connection c, String id) throws SQLException, IOException {
        try (var q =
                c.prepareStatement(
                        "SELECT revision,key_id,previous_hash,payload FROM agt_approval_request WHERE store_name=? AND request_id=? FOR UPDATE")) {
            q.setQueryTimeout(timeout);
            q.setString(1, name);
            q.setString(2, id);
            try (var rows = q.executeQuery()) {
                if (!rows.next()) return null;
                long revision = rows.getLong(1);
                String keyId = rows.getString(2), previous = rows.getString(3);
                if (revision < 0
                        || previous == null
                        || !previous.matches("[0-9a-f]{64}")
                        || (revision == 0 && !ZERO.equals(previous))) throw integrity();
                byte[] payload;
                try (var input = rows.getBinaryStream(4)) {
                    payload = input.readNBytes(MAX_PAYLOAD + 1);
                }
                if (payload.length > MAX_PAYLOAD) throw integrity();
                var state =
                        json.readValue(
                                StorageCrypto.decrypt(
                                        key(keyId).encryption(),
                                        aad(id, revision, keyId, previous),
                                        payload),
                                State.class);
                validate(state, id);
                if (state.lineage.size() != revision
                        || (revision > 0
                                && !previous.equals(state.lineage.get(state.lineage.size() - 1))))
                    throw integrity();
                return new Row(id, revision, keyId, previous, payload, state);
            }
        }
    }

    private void validate(State state, String id) {
        if (state == null
                || state.version != 1
                || !id.equals(state.requestId)
                || state.owner == null
                || state.status == null
                || state.requesterToken == null
                || state.claimHash == null
                || !state.claimHash.matches("[0-9a-f]{64}")
                || state.actionIdentity == null
                || !state.actionIdentity.matches("sha256:[0-9a-f]{64}")
                || !policy.runtimePolicy().version().equals(state.policyVersion)
                || !policy.reviewerPermissions().containsKey(state.tool)
                || state.events == null
                || state.events.isEmpty()
                || state.events.size() > policy.maxReviews() + 8
                || state.lineage == null
                || state.lineage.size() > 32
                || state.lineage.stream()
                        .anyMatch(hash -> hash == null || !hash.matches("[0-9a-f]{64}"))
                || state.reviews < 0
                || state.reviews > policy.maxReviews()
                || state.observed < 0
                || state.expiresAt < 1) throw integrity();
        boolean body = state.status == Status.PENDING || state.status == Status.APPROVED;
        if (body != (state.request != null)) throw integrity();
        if (body
                && (!id.equals(state.request.requestId())
                        || !state.actionIdentity.equals(
                                ActionIdentity.of(state.policyVersion, state.request.action()))
                        || !state.owner.equals(state.request.action().context())
                        || !state.sessionId.equals(state.request.action().sessionId())
                        || !state.actionId.equals(state.request.action().actionId())
                        || !state.tool.equals(state.request.action().toolName())
                        || state.expiresAt != state.request.expiresAtEpochMillis()))
            throw integrity();
    }

    private void write(Connection c, Row row, boolean insert) throws SQLException {
        String sql =
                insert
                        ? "INSERT INTO agt_approval_request(revision,key_id,previous_hash,payload,store_name,request_id) VALUES(?,?,?,?,?,?)"
                        : "UPDATE agt_approval_request SET revision=?,key_id=?,previous_hash=?,payload=? WHERE store_name=? AND request_id=?";
        try (var q = c.prepareStatement(sql)) {
            q.setQueryTimeout(timeout);
            q.setLong(1, row.revision);
            q.setString(2, row.keyId);
            q.setString(3, row.previous);
            q.setBytes(4, row.payload);
            q.setString(5, name);
            q.setString(6, row.id);
            if (q.executeUpdate() != 1) throw integrity();
        }
    }

    private String stream(String id) {
        return StorageCrypto.hash(
                StorageCrypto.json(List.of("AGT-APPROVAL-WITNESS-V1", name, catalog, id)));
    }

    private AuditCheckpoint checkpoint(Row row) {
        return new AuditCheckpoint(
                catalog,
                stream(row.id),
                row.revision,
                StorageCrypto.hash(
                        StorageCrypto.json(
                                List.of(
                                        aad(row.id, row.revision, row.keyId, row.previous),
                                        StorageCrypto.hash(row.payload)))));
    }

    /** 允许另一实例先确认并推进，但必须从当前已见证密文证明本次写入确为其祖先。 */
    private void publish(Row row) {
        try {
            confirm(row);
            return;
        } catch (GovernanceException failure) {
            var observed = witness.read(catalog, stream(row.id));
            if (observed == null || observed.sequence() <= row.revision) throw failure;
        }
        try (var connection = data.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockCatalog(connection);
                var current = read(connection, row.id);
                if (current == null
                        || current.revision <= row.revision
                        || current.state.lineage.size() <= row.revision
                        || !checkpoint(row)
                                .hash()
                                .equals(current.state.lineage.get((int) row.revision)))
                    throw integrity();
                confirm(current);
                connection.commit();
            } catch (Exception failure) {
                rollback(connection);
                throw map(failure);
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    private void confirm(Row row) {
        var target = checkpoint(row);
        var old = witness.read(catalog, target.streamId());
        if (target.equals(old)) return;
        if (old == null
                ? row.revision != 0
                : old.sequence() != row.revision - 1 || !old.hash().equals(row.previous))
            throw integrity();
        if (!witness.advance(old, target)
                && !target.equals(witness.read(catalog, target.streamId())))
            throw denied("approval_witness_conflict");
        if (!target.equals(witness.read(catalog, target.streamId())))
            throw denied("approval_witness_unconfirmed");
    }

    private byte[] encode(Object state) {
        try {
            return json.writeValueAsBytes(state);
        } catch (Exception failed) {
            throw integrity();
        }
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashSecret(String secret) {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}"))
            throw denied("approval_claim_denied");
        return StorageCrypto.hash(secret.getBytes(StandardCharsets.US_ASCII));
    }

    private static void rollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static GovernanceException map(Exception e) {
        return e instanceof GovernanceException g
                ? g
                : e instanceof SQLException ? unavailable() : integrity();
    }

    private static GovernanceException denied(String code) {
        return new GovernanceException(code);
    }

    private static GovernanceException integrity() {
        return denied("approval_integrity_failed");
    }

    private static GovernanceException unavailable() {
        return denied("approval_store_unavailable");
    }
}
