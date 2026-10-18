package io.agt.effects.jdbc;

import static io.agt.core.effects.EffectLedger.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.audit.AuditRecord;
import io.agt.core.effects.*;
import io.agt.core.policy.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcEffectLedgerTest {
    static final TrustedContext ID =
            new TrustedContext("agent", "tenant", "user", Set.of("pay", "refund"));
    static final TrustedContext ADMIN =
            new TrustedContext(
                    "reviewer",
                    "tenant",
                    "user",
                    Set.of("governance:effects:inspect", "governance:effects:reconcile"));
    static final Key KEY = new Key("tenant", "user", "orders", "order-7");
    static final Owner OWNER = new Owner("task", "session", "action", "agent");
    static final Map<String, Object> ARGS = Map.of("item", "book");
    static final EffectIntent INTENT = new EffectIntent("order-7", "open", null);
    static final String DIGEST = JsonValues.digest(ARGS);

    static class TestClock extends Clock {
        final AtomicLong millis = new AtomicLong(1000);

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    static class Fixture {
        final JdbcDataSource data = new JdbcDataSource();
        final TestClock clock = new TestClock();
        final JdbcEffectLedger ledger;

        Fixture() {
            data.setURL("jdbc:h2:mem:effects-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            ledger = reopen();
            ledger.initializeSchema();
        }

        JdbcEffectLedger reopen() {
            return new JdbcEffectLedger(data, "orders", clock, 2);
        }

        StructuredPolicy.Builder policy(boolean approval) {
            return StructuredPolicy.builder("effects-v1")
                    .tool(
                            "pay",
                            new ToolRule(
                                    Set.of("pay"),
                                    ParameterRule.object(
                                            Map.of("item", ParameterRule.string(null)), "item"),
                                    approval,
                                    new EffectRule(
                                            "orders",
                                            "orders-v1",
                                            Phase.FORWARD,
                                            ledger.identity())))
                    .tool(
                            "refund",
                            new ToolRule(
                                    Set.of("refund"),
                                    ParameterRule.object(Map.of()),
                                    approval,
                                    new EffectRule(
                                            "orders",
                                            "refund-v1",
                                            Phase.COMPENSATION,
                                            ledger.identity())));
        }

        AgentControl control() {
            return AgentControl.builder(policy(false).build()).audit(r -> {}).build();
        }
    }

    /** 测试业务后端：在同一个同步临界区重复验证预期状态与幂等键，记录 payload 指纹。 */
    static class Backend implements EffectBackend {
        String state = "open";
        int applied, read, recovered;
        final Map<String, Call> calls = new HashMap<>();
        final Map<String, Reply> replies = new HashMap<>();

        public synchronized String currentState(Call call) {
            read++;
            return state;
        }

        public synchronized Reply execute(Call call) {
            if (calls.containsKey(call.idempotencyKey())) {
                assertEquals(
                        calls.get(call.idempotencyKey()).requestDigest(), call.requestDigest());
                return replies.get(call.idempotencyKey());
            }
            if (!state.equals(call.expectedState())) return Reply.notApplied("cas-mismatch");
            calls.put(call.idempotencyKey(), call);
            applied++;
            state = call.phase() == Phase.FORWARD ? "paid" : "refunded";
            var result = Reply.committed("receipt-" + applied, state);
            replies.put(call.idempotencyKey(), result);
            return result;
        }

        public synchronized Object recover(Call call, Proof proof) {
            recovered++;
            var old = calls.get(call.idempotencyKey());
            assertNotNull(old);
            assertEquals(old.requestDigest(), call.requestDigest());
            var reply = replies.get(call.idempotencyKey());
            assertEquals(reply.receipt(), proof.receipt());
            return reply.value();
        }
    }

    @Test
    void reopenedCatalogAndIndependentSessionsRecoverResultWithoutReexecutingOrRereadingOldState() {
        var fixture = new Fixture();
        var backend = new Backend();
        var audit = new CopyOnWriteArrayList<AuditRecord>();
        try (var control =
                AgentControl.builder(fixture.policy(false).build()).audit(audit::add).build()) {
            try (var session = control.openSession(ID)) {
                assertEquals(
                        "paid",
                        session.effectOperation("pay", ARGS, fixture.ledger, INTENT, backend));
            }
            var reopened = fixture.reopen();
            assertEquals(fixture.ledger.identity(), reopened.identity());
            try (var session = control.openSession(ID)) {
                assertEquals(
                        "paid", session.effectOperation("pay", ARGS, reopened, INTENT, backend));
            }
        }
        assertEquals(1, backend.applied);
        assertEquals(1, backend.read);
        assertEquals(1, backend.recovered);
        var entry = fixture.ledger.inspect(KEY, ADMIN).forward();
        assertEquals(Status.COMMITTED, entry.status());
        assertTrue(
                audit.stream().anyMatch(r -> r.warnings().contains("effect_key:" + KEY.digest())));
        assertTrue(audit.stream().anyMatch(r -> r.reasonCode().equals("effect_replayed")));
    }

    @Test
    void concurrentClaimAcrossInstancesAdmitsOneOwner() throws Exception {
        var fixture = new Fixture();
        var pool = Executors.newFixedThreadPool(8);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    assertTrue(start.await(3, TimeUnit.SECONDS));
                                    try {
                                        return fixture.reopen()
                                                        .claim(
                                                                KEY,
                                                                Phase.FORWARD,
                                                                DIGEST,
                                                                null,
                                                                OWNER,
                                                                100)
                                                        .lease()
                                                != null;
                                    } catch (GovernanceException denied) {
                                        assertEquals("effect_in_progress", denied.code());
                                        return false;
                                    }
                                }));
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            int admitted = 0;
            for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) admitted++;
            assertEquals(1, admitted);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void persistedUnknownCannotBeRetriedAfterClockRollbackAndOnlyPrivateLateProofSettles() {
        var fixture = new Fixture();
        var lease = fixture.ledger.claim(KEY, Phase.FORWARD, DIGEST, null, OWNER, 100).lease();
        fixture.ledger.start(lease);
        fixture.clock.millis.set(1100);
        denied(
                "effect_outcome_unknown",
                () -> fixture.ledger.claim(KEY, Phase.FORWARD, DIGEST, null, OWNER, 100));
        fixture.clock.millis.set(1);
        var reopened = fixture.reopen();
        assertEquals(Status.UNKNOWN, reopened.inspect(KEY, ADMIN).forward().status());
        denied(
                "effect_outcome_unknown",
                () -> reopened.claim(KEY, Phase.FORWARD, DIGEST, null, OWNER, 100));
        var proof = new Proof("business-receipt", JsonValues.digest("paid"));
        reopened.finish(lease, Status.COMMITTED, proof);
        assertEquals(
                proof,
                reopened.claim(KEY, Phase.FORWARD, DIGEST, null, OWNER, 100).replay().proof());
    }

    @Test
    void managementAccessNeedsOwnerAndExplicitPermission() {
        var fixture = new Fixture();
        var lease = fixture.ledger.claim(KEY, Phase.FORWARD, DIGEST, null, OWNER, 100).lease();
        fixture.ledger.start(lease);
        fixture.clock.millis.set(1100);
        denied("effect_management_denied", () -> fixture.ledger.inspect(KEY, ID));
        var other = new TrustedContext("reviewer", "other-tenant", "user", ADMIN.permissions());
        denied("effect_owner_denied", () -> fixture.ledger.inspect(KEY, other));
        var proof = new Proof("verified-receipt", JsonValues.digest("paid"));
        denied(
                "effect_management_denied",
                () ->
                        fixture.ledger.reconcile(
                                KEY, Phase.FORWARD, 1, Status.COMMITTED, proof, ID, "review-7"));
        fixture.ledger.reconcile(KEY, Phase.FORWARD, 1, Status.COMMITTED, proof, ADMIN, "review-7");
        fixture.reopen()
                .reconcile(KEY, Phase.FORWARD, 1, Status.COMMITTED, proof, ADMIN, "review-7");
        assertEquals(proof, fixture.ledger.inspect(KEY, ADMIN).forward().proof());
    }

    @Test
    void checksumCorruptionFailsClosedWithoutResettingJournal() throws Exception {
        var fixture = new Fixture();
        fixture.ledger.claim(KEY, Phase.FORWARD, DIGEST, null, OWNER, 100);
        try (var connection = fixture.data.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE agt_effect_intent SET checksum='corrupted'");
        }
        denied(
                "effect_store_integrity_failed",
                () -> fixture.reopen().claim(KEY, Phase.FORWARD, DIGEST, null, OWNER, 100));
    }

    @Test
    void replayStillNeedsCurrentPermissionAndFreshHumanApproval() {
        var fixture = new Fixture();
        var backend = new Backend();
        var approval = new AtomicInteger();
        try (var control =
                AgentControl.builder(fixture.policy(true).build())
                        .audit(r -> {})
                        .approvalResolver(
                                request -> {
                                    approval.incrementAndGet();
                                    assertEquals(INTENT, request.action().effect().intent());
                                    assertEquals(
                                            "orders", request.action().effect().rule().namespace());
                                    assertEquals(ARGS, request.action().target());
                                    return CompletableFuture.completedFuture(
                                            io.agt.core.approval.ApprovalResponse.allow(request));
                                })
                        .build()) {
            for (int i = 0; i < 2; i++)
                try (var session = control.openSession(ID)) {
                    assertEquals(
                            "paid",
                            session.effectOperation("pay", ARGS, fixture.ledger, INTENT, backend));
                }
            try (var session =
                    control.openSession(new TrustedContext("agent", "tenant", "user", Set.of()))) {
                denied(
                        "permission_denied",
                        () ->
                                session.effectOperation(
                                        "pay", ARGS, fixture.ledger, INTENT, backend));
            }
        }
        assertEquals(2, approval.get());
        assertEquals(1, backend.applied);
        assertEquals(1, backend.recovered);
    }

    @Test
    void requiredEffectCannotBypassThroughOrdinaryToolOrWrongLedger() {
        var fixture = new Fixture();
        var backend = new Backend();
        var unprotected = new AtomicInteger();
        try (var control = fixture.control()) {
            try (var session = control.openSession(ID)) {
                denied(
                        "effect_tool_binding_required",
                        () ->
                                session.operation(
                                        true,
                                        "pay",
                                        ARGS,
                                        value -> {
                                            unprotected.incrementAndGet();
                                            return (Object) "paid";
                                        },
                                        value -> value,
                                        (a, b) -> b));
            }
            try (var session = control.openSession(ID)) {
                denied(
                        "effect_ledger_binding_denied",
                        () ->
                                session.effectOperation(
                                        "pay", ARGS, new Fixture().ledger, INTENT, backend));
            }
        }
        assertEquals(0, unprotected.get());
        assertEquals(0, backend.applied);
    }

    @Test
    void payloadOrHostBusinessIntentStateCannotChangeUnderSameId() {
        var fixture = new Fixture();
        var backend = new Backend();
        try (var control = fixture.control()) {
            try (var session = control.openSession(ID)) {
                session.effectOperation("pay", ARGS, fixture.ledger, INTENT, backend);
            }
            try (var session = control.openSession(ID)) {
                denied(
                        "effect_request_mismatch",
                        () ->
                                session.effectOperation(
                                        "pay",
                                        Map.of("item", "other"),
                                        fixture.ledger,
                                        INTENT,
                                        backend));
            }
            try (var session = control.openSession(ID)) {
                denied(
                        "effect_request_mismatch",
                        () ->
                                session.effectOperation(
                                        "pay",
                                        ARGS,
                                        fixture.ledger,
                                        new EffectIntent(INTENT.id(), "paid", null),
                                        backend));
            }
        }
        assertEquals(1, backend.applied);
    }

    @Test
    void backendAtomicCasRejectsRaceAfterSdkPrecheckWithoutApplying() {
        var fixture = new Fixture();
        var backend =
                new Backend() {
                    public synchronized Reply execute(Call call) {
                        state = "changed-concurrently";
                        return super.execute(call);
                    }
                };
        try (var control = fixture.control();
                var session = control.openSession(ID)) {
            denied(
                    "effect_not_applied",
                    () -> session.effectOperation("pay", ARGS, fixture.ledger, INTENT, backend));
        }
        assertEquals(0, backend.applied);
        assertEquals(Status.NOT_APPLIED, fixture.ledger.inspect(KEY, ADMIN).forward().status());
    }

    @Test
    void responseLossAfterBusinessCommitRetainsUnknownAndBlocksRetryUntilVerified() {
        var fixture = new Fixture();
        var backend =
                new Backend() {
                    public synchronized Reply execute(Call call) {
                        super.execute(call);
                        throw new IllegalStateException("private-detail");
                    }
                };
        try (var control = fixture.control()) {
            try (var session = control.openSession(ID)) {
                denied(
                        "effect_outcome_unknown",
                        () ->
                                session.effectOperation(
                                        "pay", ARGS, fixture.ledger, INTENT, backend));
                assertNotNull(session.externalExecutionSnapshot().fault());
                assertThrows(GovernanceException.class, session::checkActive);
            }
            try (var session = control.openSession(ID)) {
                denied(
                        "effect_outcome_unknown",
                        () ->
                                session.effectOperation(
                                        "pay", ARGS, fixture.ledger, INTENT, backend));
            }
            var entry = fixture.ledger.inspect(KEY, ADMIN).forward();
            assertEquals(Status.UNKNOWN, entry.status());
            var proof = new Proof("receipt-1", JsonValues.digest("paid"));
            fixture.ledger.reconcile(
                    KEY,
                    Phase.FORWARD,
                    entry.attempt(),
                    Status.COMMITTED,
                    proof,
                    ADMIN,
                    "backend-lookup-7");
            try (var session = control.openSession(ID)) {
                assertEquals(
                        "paid",
                        session.effectOperation("pay", ARGS, fixture.ledger, INTENT, backend));
            }
        }
        assertEquals(1, backend.applied);
        assertEquals(1, backend.recovered);
    }

    @Test
    void postPolicyFailureCannotEraseCommittedEffectOrRunItAgain() {
        var fixture = new Fixture();
        var backend = new Backend();
        var policy =
                fixture.policy(false)
                        .content(
                                new ContentRule(
                                        "result-denied",
                                        Set.of(InterventionPoint.POST_TOOL_CALL),
                                        List.of("paid"),
                                        Map.of()))
                        .build();
        try (var control = AgentControl.builder(policy).audit(r -> {}).build()) {
            for (int i = 0; i < 2; i++)
                try (var session = control.openSession(ID)) {
                    assertThrows(
                            GovernanceException.class,
                            () ->
                                    session.effectOperation(
                                            "pay", ARGS, fixture.ledger, INTENT, backend));
                }
        }
        assertEquals(1, backend.applied);
        assertEquals(1, backend.recovered);
        assertEquals(Status.COMMITTED, fixture.ledger.inspect(KEY, ADMIN).forward().status());
    }

    @Test
    void explicitCompensationIsSeparatelyAuthorizedAndIdempotent() {
        var fixture = new Fixture();
        var backend = new Backend();
        try (var control = fixture.control()) {
            try (var session = control.openSession(ID)) {
                session.effectOperation("pay", ARGS, fixture.ledger, INTENT, backend);
            }
            var forward = fixture.ledger.inspect(KEY, ADMIN).forward().proof();
            var refund = new EffectIntent(INTENT.id(), "paid", forward);
            try (var session =
                    control.openSession(
                            new TrustedContext("agent", "tenant", "user", Set.of("pay")))) {
                denied(
                        "permission_denied",
                        () ->
                                session.effectOperation(
                                        "refund", Map.of(), fixture.ledger, refund, backend));
            }
            for (int i = 0; i < 2; i++)
                try (var session = control.openSession(ID)) {
                    assertEquals(
                            "refunded",
                            session.effectOperation(
                                    "refund", Map.of(), fixture.ledger, refund, backend));
                }
            try (var session = control.openSession(ID)) {
                denied(
                        "effect_already_compensated",
                        () ->
                                session.effectOperation(
                                        "pay", ARGS, fixture.ledger, INTENT, backend));
            }
        }
        assertEquals(2, backend.applied);
        assertEquals(1, backend.recovered);
        assertEquals(Status.COMMITTED, fixture.ledger.inspect(KEY, ADMIN).compensation().status());
    }

    @Test
    void recoveryMustMatchStoredResultDigestAndCannotReenterMutationPath() {
        var fixture = new Fixture();
        var backend =
                new Backend() {
                    public synchronized Object recover(Call call, Proof proof) {
                        return "tampered";
                    }
                };
        try (var control = fixture.control()) {
            try (var session = control.openSession(ID)) {
                session.effectOperation("pay", ARGS, fixture.ledger, INTENT, backend);
            }
            try (var session = control.openSession(ID)) {
                denied(
                        "effect_result_mismatch",
                        () ->
                                session.effectOperation(
                                        "pay", ARGS, fixture.ledger, INTENT, backend));
            }
        }
        assertEquals(1, backend.applied);
        assertEquals(Status.COMMITTED, fixture.ledger.inspect(KEY, ADMIN).forward().status());
    }

    @Test
    void revocationBeforeDispatchNeverEntersBackendEvenWhenStateReadIgnoresInterrupts()
            throws Exception {
        var fixture = new Fixture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var backend =
                new Backend() {
                    public String currentState(Call call) {
                        entered.countDown();
                        awaitIgnoringInterrupts(release);
                        return "open";
                    }
                };
        try (var control = fixture.control();
                var session = control.openSession(ID)) {
            var source =
                    session.effectOperationAsync(
                            "pay",
                            ARGS,
                            fixture.ledger,
                            INTENT,
                            backend,
                            v -> {},
                            v -> fail("No response after revocation"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            session.revoke();
            release.countDown();
            assertEquals(
                    "session_revoked",
                    ((GovernanceException)
                                    assertThrows(
                                                    ExecutionException.class,
                                                    () -> source.get(3, TimeUnit.SECONDS))
                                            .getCause())
                            .code());
            awaitStatus(fixture.ledger, Status.NOT_APPLIED);
            assertEquals(0, backend.applied);
        } finally {
            release.countDown();
        }
    }

    @Test
    void lateKnownCommitUpdatesDurableEvidenceWithoutRevivingRevokedSession() throws Exception {
        var fixture = new Fixture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var backend =
                new Backend() {
                    public Reply execute(Call call) {
                        entered.countDown();
                        awaitIgnoringInterrupts(release);
                        return super.execute(call);
                    }
                };
        var delivered = new AtomicInteger();
        try (var control = fixture.control();
                var session = control.openSession(ID)) {
            var source =
                    session.effectOperationAsync(
                            "pay",
                            ARGS,
                            fixture.ledger,
                            INTENT,
                            backend,
                            v -> {},
                            v -> delivered.incrementAndGet());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            session.revoke();
            release.countDown();
            assertEquals(
                    "session_revoked",
                    ((GovernanceException)
                                    assertThrows(
                                                    ExecutionException.class,
                                                    () -> source.get(3, TimeUnit.SECONDS))
                                            .getCause())
                            .code());
            awaitStatus(fixture.ledger, Status.COMMITTED);
            assertThrows(GovernanceException.class, session::checkActive);
            assertEquals(1, backend.applied);
            assertEquals(0, delivered.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void lostCommitAcknowledgmentRetainsStoredReceiptAndNeverRepeatsMutation() {
        var fixture = new Fixture();
        var backend = new Backend();
        EffectLedger acknowledgmentLost =
                new EffectLedger() {
                    public String identity() {
                        return fixture.ledger.identity();
                    }

                    public Claim claim(
                            Key key,
                            Phase phase,
                            String digest,
                            Proof parent,
                            Owner owner,
                            long duration) {
                        return fixture.ledger.claim(key, phase, digest, parent, owner, duration);
                    }

                    public void start(Lease lease) {
                        fixture.ledger.start(lease);
                    }

                    public void finish(Lease lease, Status status, Proof proof) {
                        fixture.ledger.finish(lease, status, proof);
                        if (status == Status.COMMITTED)
                            throw new GovernanceException("effect_store_unavailable");
                    }

                    public View inspect(Key key, TrustedContext actor) {
                        return fixture.ledger.inspect(key, actor);
                    }

                    public void reconcile(
                            Key key,
                            Phase phase,
                            long attempt,
                            Status status,
                            Proof proof,
                            TrustedContext actor,
                            String review) {
                        fixture.ledger.reconcile(key, phase, attempt, status, proof, actor, review);
                    }
                };
        try (var control = fixture.control()) {
            try (var session = control.openSession(ID)) {
                denied(
                        "effect_store_unavailable",
                        () ->
                                session.effectOperation(
                                        "pay", ARGS, acknowledgmentLost, INTENT, backend));
                assertThrows(GovernanceException.class, session::checkActive);
            }
            assertEquals(Status.COMMITTED, fixture.ledger.inspect(KEY, ADMIN).forward().status());
            try (var session = control.openSession(ID)) {
                assertEquals(
                        "paid",
                        session.effectOperation("pay", ARGS, fixture.reopen(), INTENT, backend));
            }
        }
        assertEquals(1, backend.applied);
        assertEquals(1, backend.recovered);
    }

    @Test
    void lowLevelCoordinatorCannotInventAnAdmittedAction() {
        var fixture = new Fixture();
        var backend = new Backend();
        try (var control = fixture.control();
                var session = control.openSession(ID)) {
            var plan =
                    new PreparedEffect(
                            fixture.policy(false).build().effectRule("pay"),
                            fixture.ledger,
                            INTENT,
                            backend,
                            ARGS);
            denied(
                    "effect_tool_admission_required",
                    () ->
                            EffectCoordinator.execute(
                                    session, "invented-action", "pay", plan, v -> {}, v -> {}));
        }
        assertEquals(0, backend.applied);
    }

    @Test
    void requiredReliabilityCannotSilentlyDegradeInObservationMode() {
        var fixture = new Fixture();
        var backend = new Backend();
        try (var control =
                AgentControl.builder(fixture.policy(false).build())
                        .mode(EnforcementMode.EVALUATE_ONLY)
                        .audit(r -> {})
                        .build()) {
            try (var session = control.openSession(ID)) {
                denied(
                        "effect_policy_required",
                        () ->
                                session.effectOperation(
                                        "pay", ARGS, fixture.ledger, INTENT, backend));
                assertThrows(GovernanceException.class, session::checkActive);
            }
            try (var session = control.openSession(ID)) {
                denied(
                        "effect_tool_binding_required",
                        () ->
                                session.operation(
                                        true,
                                        "pay",
                                        ARGS,
                                        value -> {
                                            backend.applied++;
                                            return (Object) "paid";
                                        },
                                        value -> value,
                                        (a, b) -> b));
            }
        }
        assertEquals(0, backend.applied);
    }

    static void awaitIgnoringInterrupts(CountDownLatch release) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (release.getCount() != 0 && System.nanoTime() < deadline) {
                try {
                    release.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            assertEquals(0, release.getCount(), "Test backend was not released");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static void awaitStatus(JdbcEffectLedger ledger, Status expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            if (ledger.inspect(KEY, ADMIN).forward().status() == expected) return;
            Thread.sleep(5);
        }
        assertEquals(expected, ledger.inspect(KEY, ADMIN).forward().status());
    }

    static void denied(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(GovernanceException.class, action).code());
    }
}
