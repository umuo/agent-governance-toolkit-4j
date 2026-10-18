package io.agt.audit.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.audit.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.KeyGenerator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcAuditVaultTest {
    static final TrustedContext READER =
            new TrustedContext("reviewer", "tenant", "user", Set.of("governance:audit:read"));
    static final TrustedContext ADMIN =
            new TrustedContext(
                    "reviewer",
                    "tenant",
                    "user",
                    Set.of(
                            "governance:audit:read",
                            "governance:audit:body",
                            "governance:audit:purge"));

    static class TestClock extends Clock {
        final AtomicLong now = new AtomicLong(1000);

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    }

    static class KeyRing implements AuditKeys {
        final Map<String, Version> versions = new ConcurrentHashMap<>();
        volatile Version active;

        KeyRing() throws Exception {
            rotate("key-1");
        }

        void rotate(String id) throws Exception {
            var aes = KeyGenerator.getInstance("AES");
            aes.init(256);
            var mac = KeyGenerator.getInstance("HmacSHA256");
            mac.init(256);
            active = new Version(id, aes.generateKey(), mac.generateKey());
            versions.put(id, active);
        }

        public Version active() {
            return active;
        }

        public Version find(String id) {
            return versions.get(id);
        }
    }

    static JdbcDataSource data(String name) {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:" + name + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return data;
    }

    static class Fixture {
        final JdbcDataSource primary = data("vault"), anchor = data("witness");
        final JdbcAuditWitness witness = new JdbcAuditWitness(anchor, 3);
        final KeyRing keys = new KeyRing();
        final TestClock clock = new TestClock();
        final AuditVaultPolicy policy =
                new AuditVaultPolicy(
                        new AuditBodyPolicy(
                                Set.of(InterventionPoint.PRE_TOOL_CALL),
                                Set.of("payload"),
                                true,
                                10000,
                                4096),
                        100,
                        1000,
                        1000);
        final String catalog = UUID.randomUUID().toString();
        final JdbcAuditVault vault;

        Fixture() throws Exception {
            witness.initializeSchema();
            vault = reopen(witness);
            vault.initializeSchema();
        }

        JdbcAuditVault reopen(AuditWitness remote) {
            return new JdbcAuditVault(
                    primary, "governance", catalog, policy, keys, remote, clock, 3);
        }

        AuditEvent event(String id) {
            var record =
                    new AuditRecord(
                            1,
                            Instant.ofEpochMilli(1),
                            "session-1",
                            "agent",
                            "action-1",
                            InterventionPoint.PRE_TOOL_CALL,
                            EnforcementMode.ENFORCE,
                            "v1",
                            "ALLOW",
                            "allowed",
                            false,
                            "not_requested",
                            "not_executed",
                            JsonValues.digest("input"),
                            JsonValues.digest("effective"),
                            1,
                            List.of());
            var payload =
                    Map.of(
                            "payload",
                            Map.of("text", "hello-private-body", "password", "synthetic-password"));
            return new AuditEvent(
                    id,
                    new AuditScope("tenant", "user", "task-1", "session-1"),
                    record,
                    policy.bodies().capture(record.point(), payload, payload));
        }

        int entries() throws Exception {
            try (var c = primary.getConnection();
                    var s = c.createStatement();
                    var r = s.executeQuery("SELECT COUNT(*) FROM agt_audit_entry")) {
                r.next();
                return r.getInt(1);
            }
        }

        void sql(String sql) throws Exception {
            try (var c = primary.getConnection();
                    var s = c.createStatement()) {
                s.executeUpdate(sql);
            }
        }
    }

    @Test
    void ciphertextReadPermissionsAndDurableReopenKeepBodySeparateFromMetadata() throws Exception {
        var f = new Fixture();
        var receipt = f.vault.append(f.event("event-1"));
        var reopened = f.reopen(new JdbcAuditWitness(f.anchor, 3));
        var metadata = reopened.readMetadata(READER, 0, 10);
        assertEquals(1, metadata.size());
        assertFalse(JsonValues.write(metadata.get(0).metadata()).contains("hello-private-body"));
        denied("audit_access_denied", () -> reopened.readBody(READER, receipt.sequence()));
        String body = JsonValues.write(reopened.readBody(ADMIN, receipt.sequence()));
        assertTrue(body.contains("hello-private-body"));
        assertFalse(body.contains("synthetic-password"));
        assertTrue(reopened.verify(READER, receipt).sequence() >= receipt.sequence());
        try (var c = f.primary.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT metadata_cipher,body_cipher FROM agt_audit_entry")) {
            while (r.next())
                for (int i = 1; i <= 2; i++)
                    if (r.getBytes(i) != null) {
                        String encoded =
                                new String(r.getBytes(i), java.nio.charset.StandardCharsets.UTF_8);
                        assertFalse(encoded.contains("hello-private-body"));
                        assertFalse(encoded.contains("synthetic-password"));
                    }
        }
    }

    @Test
    void guessedSequenceAndRoleCannotReadOtherTenantOrPrincipal() throws Exception {
        var f = new Fixture();
        f.vault.append(f.event("event-1"));
        for (var actor :
                List.of(
                        new TrustedContext("reader", "other", "user", ADMIN.permissions()),
                        new TrustedContext("reader", "tenant", "other", ADMIN.permissions()))) {
            assertTrue(f.vault.readMetadata(actor, 0, 10).isEmpty());
            denied("audit_body_unavailable", () -> f.vault.readBody(actor, 1));
        }
        denied(
                "audit_access_denied",
                () ->
                        f.vault.readMetadata(
                                new TrustedContext("reader", "tenant", "user", Set.of()), 0, 10));
    }

    @Test
    void bodyAndRecordExpirationAreStickyAndPayloadPurgePreservesVerifiableHeaders()
            throws Exception {
        var f = new Fixture();
        var receipt = f.vault.append(f.event("event-1"));
        f.clock.now.set(1100);
        denied("audit_body_unavailable", () -> f.vault.readBody(ADMIN, 1));
        f.clock.now.set(1);
        denied("audit_body_unavailable", () -> f.vault.readBody(ADMIN, 1));
        assertTrue(f.vault.purgeExpired(ADMIN) > 0);
        f.clock.now.set(2100);
        assertTrue(f.vault.readMetadata(READER, 0, 10).isEmpty());
        assertTrue(f.vault.purgeExpired(ADMIN) > 0);
        assertTrue(f.vault.verify(READER, receipt).sequence() > receipt.sequence());
        try (var c = f.primary.getConnection();
                var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT metadata_cipher,body_cipher FROM agt_audit_entry WHERE seq=1")) {
            assertTrue(r.next());
            assertNull(r.getBytes(1));
            assertNull(r.getBytes(2));
        }
        f.clock.now.set(1);
        f.vault.append(f.event("event-1"));
        denied("audit_body_unavailable", () -> f.vault.readBody(ADMIN, 1));
    }

    @Test
    void ciphertextChangesDeletionAndSwappingFailBeforeDataIsReturned() throws Exception {
        for (String change :
                List.of(
                        "UPDATE agt_audit_entry SET body_cipher=X'000102' WHERE seq=1",
                        "UPDATE agt_audit_entry SET body_cipher=NULL WHERE seq=1",
                        "UPDATE agt_audit_entry SET body_cipher=metadata_cipher WHERE seq=1",
                        "UPDATE agt_audit_entry SET record_expires=0 WHERE seq=1",
                        "DELETE FROM agt_audit_entry WHERE seq=1")) {
            var f = new Fixture();
            f.vault.append(f.event("event-1"));
            f.sql(change);
            denied("audit_integrity_failed", () -> f.reopen(f.witness).readMetadata(READER, 0, 10));
        }
    }

    @Test
    void genuineOldHeadAndValidPrefixCannotHideTailRollbackFromIndependentWitness()
            throws Exception {
        var f = new Fixture();
        var first = f.vault.append(f.event("event-1"));
        Object[] old;
        try (var c = f.primary.getConnection();
                var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT seq,chain_hash,observed_at,key_id,seal FROM agt_audit_head")) {
            assertTrue(r.next());
            old =
                    new Object[] {
                        r.getLong(1), r.getString(2), r.getLong(3), r.getString(4), r.getString(5)
                    };
        }
        f.vault.append(f.event("event-2"));
        f.sql("DELETE FROM agt_audit_entry WHERE seq=2");
        try (var c = f.primary.getConnection();
                var s =
                        c.prepareStatement(
                                "UPDATE agt_audit_head SET seq=?,chain_hash=?,observed_at=?,key_id=?,seal=?")) {
            for (int i = 0; i < old.length; i++) s.setObject(i + 1, old[i]);
            s.executeUpdate();
        }
        denied("audit_witness_mismatch", () -> f.reopen(f.witness).verify(READER, first));
    }

    @Test
    void committedButUnwitnessedEventIsRecoveredWithoutDuplicateOnRetry() throws Exception {
        var f = new Fixture();
        var event = f.event("event-1");
        AuditWitness failing =
                new AuditWitness() {
                    public AuditCheckpoint read(String c, String s) {
                        return f.witness.read(c, s);
                    }

                    public boolean advance(AuditCheckpoint before, AuditCheckpoint after) {
                        if (after.sequence() > 0)
                            throw new GovernanceException("audit_witness_unavailable");
                        return f.witness.advance(before, after);
                    }
                };
        denied("audit_witness_unavailable", () -> f.reopen(failing).append(event));
        assertEquals(1, f.entries());
        var result = f.reopen(f.witness).append(event);
        assertEquals(1, result.sequence());
        assertEquals(1, f.entries());
        assertEquals(result, f.vault.verify(READER, result));
    }

    @Test
    void lostWitnessAckIsNotSuccessfulDeliveryButRetryDoesNotAppendAgain() throws Exception {
        var f = new Fixture();
        var event = f.event("event-1");
        AuditWitness failing =
                new AuditWitness() {
                    public AuditCheckpoint read(String c, String s) {
                        return f.witness.read(c, s);
                    }

                    public boolean advance(AuditCheckpoint before, AuditCheckpoint after) {
                        boolean accepted = f.witness.advance(before, after);
                        if (after.sequence() > 0)
                            throw new GovernanceException("audit_witness_unavailable");
                        return accepted;
                    }
                };
        denied("audit_witness_unavailable", () -> f.reopen(failing).append(event));
        assertEquals(1, f.entries());
        assertEquals(1, f.vault.append(event).sequence());
        assertEquals(1, f.entries());
    }

    @Test
    void witnessBackupRollbackCannotBeTreatedAsAnUnboundedReplayQueue() throws Exception {
        var f = new Fixture();
        f.vault.append(f.event("event-1"));
        var head = f.vault.append(f.event("event-2"));
        String genesis =
                AuditCrypto.hash(
                        AuditCrypto.json(
                                List.of(
                                        "AGT-AUDIT-GENESIS-V1",
                                        f.catalog,
                                        head.streamId(),
                                        f.policy.identity())));
        try (var c = f.anchor.getConnection();
                var update =
                        c.prepareStatement("UPDATE agt_audit_witness SET seq=0,chain_hash=?")) {
            update.setString(1, genesis);
            update.executeUpdate();
        }
        denied("audit_witness_mismatch", () -> f.vault.verify(READER, head));
    }

    @Test
    void missingWitnessIsNeverSilentlyRecreatedForExistingStream() throws Exception {
        var f = new Fixture();
        f.vault.append(f.event("event-1"));
        try (var c = f.anchor.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate("DELETE FROM agt_audit_witness");
        }
        denied("audit_witness_mismatch", () -> f.vault.append(f.event("event-2")));
        assertEquals(1, f.entries());
    }

    @Test
    void eventIdentityCannotBeReusedForDifferentCaptureAndKeysCanRotate() throws Exception {
        var f = new Fixture();
        var event = f.event("event-1");
        var first = f.vault.append(event);
        f.keys.rotate("key-2");
        f.vault.append(f.event("event-2"));
        assertTrue(f.vault.verify(READER, first).sequence() >= 2);
        assertEquals(first, f.vault.append(event));
        denied(
                "audit_event_replay_mismatch",
                () ->
                        f.vault.append(
                                new AuditEvent(
                                        event.eventId(),
                                        event.scope(),
                                        event.record(),
                                        new AuditBody("captured", Map.of("different", "body")))));
        f.keys.versions.remove("key-1");
        denied("audit_key_unavailable", () -> f.vault.readMetadata(READER, 0, 10));
    }

    @Test
    void concurrentInstancesUseOneGlobalSequenceAndEveryAckIsWitnessed() throws Exception {
        var f = new Fixture();
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<AuditCheckpoint>>();
            for (int i = 0; i < 8; i++) {
                var event = f.event("event-" + i);
                futures.add(
                        pool.submit(
                                () -> {
                                    assertTrue(start.await(3, TimeUnit.SECONDS));
                                    return f.reopen(new JdbcAuditWitness(f.anchor, 3))
                                            .append(event);
                                }));
            }
            start.countDown();
            var seqs = new HashSet<Long>();
            for (var future : futures) seqs.add(future.get(8, TimeUnit.SECONDS).sequence());
            assertEquals(8, seqs.size());
            assertEquals(8, f.vault.verify(READER, null).sequence());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void staleMissingHeadReadIsRecheckedAfterAnotherCreatorCommitsAndWitnesses() throws Exception {
        var f = new Fixture();
        var missed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var once = new AtomicBoolean();
        javax.sql.DataSource observed =
                (javax.sql.DataSource)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {javax.sql.DataSource.class},
                                (proxy, method, args) -> {
                                    Object result = invoke(method, f.primary, args);
                                    if (!method.getName().equals("getConnection")) return result;
                                    return java.lang.reflect.Proxy.newProxyInstance(
                                            getClass().getClassLoader(),
                                            new Class<?>[] {Connection.class},
                                            (cp, cm, ca) -> {
                                                Object statement = invoke(cm, result, ca);
                                                if (!cm.getName().equals("prepareStatement")
                                                        || !((String) ca[0])
                                                                .contains("FROM agt_audit_head"))
                                                    return statement;
                                                return java.lang.reflect.Proxy.newProxyInstance(
                                                        getClass().getClassLoader(),
                                                        new Class<?>[] {PreparedStatement.class},
                                                        (sp, sm, sa) -> {
                                                            Object rows = invoke(sm, statement, sa);
                                                            if (!sm.getName()
                                                                    .equals("executeQuery"))
                                                                return rows;
                                                            var empty = new AtomicBoolean();
                                                            return java.lang.reflect.Proxy
                                                                    .newProxyInstance(
                                                                            getClass()
                                                                                    .getClassLoader(),
                                                                            new Class<?>[] {
                                                                                ResultSet.class
                                                                            },
                                                                            (rp, rm, ra) -> {
                                                                                Object value =
                                                                                        invoke(
                                                                                                rm,
                                                                                                rows,
                                                                                                ra);
                                                                                if (rm.getName()
                                                                                                .equals(
                                                                                                        "next")
                                                                                        && Boolean
                                                                                                .FALSE
                                                                                                .equals(
                                                                                                        value))
                                                                                    empty.set(true);
                                                                                if (rm.getName()
                                                                                                .equals(
                                                                                                        "close")
                                                                                        && empty
                                                                                                .get()
                                                                                        && once
                                                                                                .compareAndSet(
                                                                                                        false,
                                                                                                        true)) {
                                                                                    missed
                                                                                            .countDown();
                                                                                    assertTrue(
                                                                                            release
                                                                                                    .await(
                                                                                                            6,
                                                                                                            TimeUnit
                                                                                                                    .SECONDS));
                                                                                }
                                                                                return value;
                                                                            });
                                                        });
                                            });
                                });
        var slow =
                new JdbcAuditVault(
                        observed, "governance", f.catalog, f.policy, f.keys, f.witness, f.clock, 3);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> slow.append(f.event("slow-event")));
            assertTrue(missed.await(3, TimeUnit.SECONDS));
            assertEquals(1, f.vault.append(f.event("fast-event")).sequence());
            release.countDown();
            assertEquals(2, pending.get(6, TimeUnit.SECONDS).sequence());
            assertEquals(2, f.vault.verify(READER, null).sequence());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    static Object invoke(java.lang.reflect.Method method, Object target, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Test
    void retainedByteBudgetRefusesNewPayloadsButLeavesSpaceForReadAndExpiryCleanup()
            throws Exception {
        var f = new Fixture();
        var primary = data("bounded");
        var limited = new AuditVaultPolicy(f.policy.bodies(), 100, 1000, 1000, 131072);
        var vault =
                new JdbcAuditVault(
                        primary,
                        "governance",
                        UUID.randomUUID().toString(),
                        limited,
                        f.keys,
                        f.witness,
                        f.clock,
                        3);
        vault.initializeSchema();
        int accepted = 0;
        for (int i = 0; i < 200; i++) {
            try {
                vault.append(f.event("event-" + i));
                accepted++;
            } catch (GovernanceException denied) {
                assertEquals("audit_capacity_exceeded", denied.code());
                break;
            }
        }
        assertTrue(accepted > 0 && accepted < 200);
        assertEquals(accepted, vault.verify(READER, null).sequence());
        assertEquals(1, vault.readMetadata(READER, 0, 1).size());
        f.clock.now.set(2100);
        assertTrue(vault.purgeExpired(ADMIN) > 0);
        assertNotNull(vault.append(f.event("after-purge")));
        assertTrue(vault.verify(READER, null).sequence() > accepted);
    }

    @Test
    void captureAndRetentionPoliciesCannotBeWeakenedAfterDeployment() throws Exception {
        var f = new Fixture();
        f.vault.append(f.event("event-1"));
        var weaker = new AuditVaultPolicy(AuditBodyPolicy.NONE, 100, 1000, 1000);
        var changed =
                new JdbcAuditVault(
                        f.primary, "governance", f.catalog, weaker, f.keys, f.witness, f.clock, 3);
        denied("audit_integrity_failed", changed::identity);
        var event = f.event("event-2");
        denied(
                "audit_integrity_failed",
                () ->
                        changed.append(
                                new AuditEvent(
                                        event.eventId(),
                                        event.scope(),
                                        event.record(),
                                        AuditBody.omitted("disabled"))));
    }

    static void denied(String code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code, assertThrows(GovernanceException.class, call).code());
    }
}
