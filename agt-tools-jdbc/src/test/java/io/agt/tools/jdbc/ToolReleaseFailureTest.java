package io.agt.tools.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.audit.jdbc.*;
import io.agt.core.*;
import io.agt.core.storage.StorageKeys;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 无响应见证、轮换密钥和多实例首次创建等持久边界。 */
class ToolReleaseFailureTest {
    @Test
    void slowWitnessCannotReturnLateApprovalOrLeaveCallerWaitingWithoutBound() {
        try (var f = new ReleaseFixture(16, 4, 262144, 600)) {
            var descriptor = f.descriptor("v1");
            f.approve(descriptor);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            AuditWitness slow =
                    new AuditWitness() {
                        public AuditCheckpoint read(String catalog, String stream) {
                            entered.countDown();
                            boolean done = false;
                            while (!done) {
                                try {
                                    release.await();
                                    done = true;
                                } catch (InterruptedException ignored) {
                                    /* 模拟不合作的外部客户端。 */
                                }
                            }
                            return f.witness.read(catalog, stream);
                        }

                        public boolean advance(AuditCheckpoint expected, AuditCheckpoint next) {
                            return f.witness.advance(expected, next);
                        }
                    };
            try (var store = f.store(slow)) {
                long start = System.nanoTime();
                assertEquals(
                        "tool_release_timeout",
                        assertThrows(GovernanceException.class, () -> store.check(descriptor))
                                .code());
                assertEquals(0, entered.getCount());
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
            } finally {
                release.countDown();
            }
            f.store.check(descriptor);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingOrCancellingCheckNeverDeliversLateApproval(boolean close) throws Exception {
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor("v1");
            f.approve(descriptor);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            AuditWitness slow =
                    new AuditWitness() {
                        public AuditCheckpoint read(String catalog, String stream) {
                            entered.countDown();
                            boolean done = false;
                            while (!done) {
                                try {
                                    release.await();
                                    done = true;
                                } catch (InterruptedException ignored) {
                                    /* 外部调用可忽略中断。 */
                                }
                            }
                            return f.witness.read(catalog, stream);
                        }

                        public boolean advance(AuditCheckpoint expected, AuditCheckpoint next) {
                            return f.witness.advance(expected, next);
                        }
                    };
            var error = new AtomicReference<GovernanceException>();
            try (var store = f.store(slow)) {
                var thread =
                        new Thread(
                                () -> {
                                    try {
                                        store.check(descriptor);
                                    } catch (GovernanceException denied) {
                                        error.set(denied);
                                    }
                                });
                try {
                    thread.start();
                    assertTrue(entered.await(2, TimeUnit.SECONDS));
                    if (close) {
                        store.close();
                    } else {
                        thread.interrupt();
                    }
                    release.countDown();
                    thread.join(5000);
                    assertFalse(thread.isAlive());
                    assertNotNull(error.get());
                    assertEquals(
                            close ? "tool_release_unavailable" : "tool_release_cancelled",
                            error.get().code());
                } finally {
                    release.countDown();
                    thread.interrupt();
                    thread.join(5000);
                }
            }
            f.store.check(descriptor);
        }
    }

    @Test
    void reviewBodyIsNotDeliveredBeforeIndependentWitnessConfirmation() {
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor("v1");
            f.store.submit(f.publisher(), descriptor);
            AuditWitness failed =
                    new AuditWitness() {
                        public AuditCheckpoint read(String catalog, String stream) {
                            return f.witness.read(catalog, stream);
                        }

                        public boolean advance(AuditCheckpoint expected, AuditCheckpoint next) {
                            if (next.sequence() == 2) {
                                throw new GovernanceException("synthetic_failure");
                            }
                            return f.witness.advance(expected, next);
                        }
                    };
            var actor = f.reviewer();
            try (var store = f.store(failed)) {
                assertThrows(
                        GovernanceException.class,
                        () -> store.review(actor, descriptor.identity()));
            }
            var view = f.store.review(actor, descriptor.identity());
            f.store.decide(actor, view, true);
            f.store.check(descriptor);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identityAndMfaAreRecheckedBeforeCommittingApproval(boolean revoke) {
        try (var f = new ReleaseFixture(16, 4, 262144, 5000, 10000)) {
            var descriptor = f.descriptor("v1");
            f.store.submit(f.publisher(), descriptor);
            var actor = f.reviewer();
            var view = f.store.review(actor, descriptor.identity());
            var advanced = new AtomicBoolean();
            AuditWitness clockChange =
                    new AuditWitness() {
                        public AuditCheckpoint read(String catalog, String stream) {
                            if (advanced.compareAndSet(false, true)) {
                                if (revoke) {
                                    f.revoked.add(actor.tokenIdentity());
                                } else {
                                    f.clock.time.addAndGet(20000);
                                }
                            }
                            return f.witness.read(catalog, stream);
                        }

                        public boolean advance(AuditCheckpoint expected, AuditCheckpoint next) {
                            return f.witness.advance(expected, next);
                        }
                    };
            try (var store = f.store(clockChange)) {
                assertEquals(
                        revoke ? "identity_token_inactive" : "identity_human_assurance_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> store.decide(actor, view, true))
                                .code());
            }
            assertEquals(
                    "PENDING",
                    f.store.inspect(f.reviewer(), descriptor.identity(), r -> {}).status());
            assertThrows(GovernanceException.class, () -> f.store.check(descriptor));
        }
    }

    @Test
    void singleConnectionPoolCanInitializeAndConfirmWithoutNestedBorrow() {
        try (var f = new ReleaseFixture()) {
            var permit = new Semaphore(1);
            javax.sql.DataSource pool =
                    new javax.sql.DataSource() {
                        public java.sql.Connection getConnection() throws java.sql.SQLException {
                            try {
                                if (!permit.tryAcquire(2, TimeUnit.SECONDS)) {
                                    throw new java.sql.SQLException("Single connection exhausted");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new java.sql.SQLException(interrupted);
                            }
                            java.sql.Connection delegate;
                            try {
                                delegate = f.primary.getConnection();
                            } catch (java.sql.SQLException failed) {
                                permit.release();
                                throw failed;
                            }
                            var released = new AtomicBoolean();
                            return (java.sql.Connection)
                                    java.lang.reflect.Proxy.newProxyInstance(
                                            getClass().getClassLoader(),
                                            new Class<?>[] {java.sql.Connection.class},
                                            (proxy, method, args) -> {
                                                try {
                                                    return method.invoke(delegate, args);
                                                } catch (
                                                        java.lang.reflect.InvocationTargetException
                                                                failure) {
                                                    throw failure.getCause();
                                                } finally {
                                                    if (method.getName().equals("close")
                                                            && released.compareAndSet(
                                                                    false, true)) {
                                                        permit.release();
                                                    }
                                                }
                                            });
                        }

                        public java.sql.Connection getConnection(String user, String password)
                                throws java.sql.SQLException {
                            return getConnection();
                        }

                        public java.io.PrintWriter getLogWriter() throws java.sql.SQLException {
                            return f.primary.getLogWriter();
                        }

                        public void setLogWriter(java.io.PrintWriter writer)
                                throws java.sql.SQLException {
                            f.primary.setLogWriter(writer);
                        }

                        public void setLoginTimeout(int seconds) throws java.sql.SQLException {
                            f.primary.setLoginTimeout(seconds);
                        }

                        public int getLoginTimeout() throws java.sql.SQLException {
                            return f.primary.getLoginTimeout();
                        }

                        public java.util.logging.Logger getParentLogger() {
                            return java.util.logging.Logger.getGlobal();
                        }

                        public <T> T unwrap(Class<T> type) throws java.sql.SQLException {
                            throw new java.sql.SQLException("Not a wrapper");
                        }

                        public boolean isWrapperFor(Class<?> type) {
                            return false;
                        }
                    };
            try (var store =
                    new JdbcToolReleaseStore(
                            pool,
                            "tool-releases",
                            "independent-tools",
                            f.policy,
                            f.keys,
                            f.witness,
                            f.clock)) {
                var descriptor = f.descriptor("v1");
                store.submit(f.publisher(), descriptor);
                var human = f.reviewer();
                store.decide(human, store.review(human, descriptor.identity()), true);
                store.check(descriptor);
                store.revoke(f.revoker(), descriptor.identity());
                assertThrows(GovernanceException.class, () -> store.check(descriptor));
            }
            assertEquals(1, permit.availablePermits());
        }
    }

    @Test
    void keyRotationUsesRetainedKeysAndCannotDowngradeAuthority() {
        try (var f = new ReleaseFixture()) {
            var first = f.descriptor("v1");
            f.approve(first);
            byte[] aes = new byte[32], mac = new byte[32];
            new java.security.SecureRandom().nextBytes(aes);
            new java.security.SecureRandom().nextBytes(mac);
            var next =
                    new StorageKeys.Version(
                            "v2",
                            new SecretKeySpec(aes, "AES"),
                            new SecretKeySpec(mac, "HmacSHA256"));
            StorageKeys rotating =
                    new StorageKeys() {
                        public Version active() {
                            return next;
                        }

                        public Version find(String id) {
                            return "v2".equals(id) ? next : f.keys.find(id);
                        }
                    };
            try (var rotated =
                    new JdbcToolReleaseStore(
                            f.primary,
                            "tool-releases",
                            "independent-tools",
                            f.policy,
                            rotating,
                            f.witness,
                            f.clock)) {
                assertEquals(f.store.identity(), rotated.identity());
                var second = f.descriptor(rotated, "v2", "new version");
                rotated.submit(f.publisher(), second);
                var human = f.reviewer();
                rotated.decide(human, rotated.review(human, second.identity()), true);
                rotated.check(first);
                rotated.check(second);
                assertThrows(GovernanceException.class, () -> f.store.check(first));
            }
        }
    }

    @Test
    void parallelFirstPublishersDoNotLoseDirectoryOrOverwriteVersion() throws Exception {
        try (var f = new ReleaseFixture();
                var peer = f.store(f.witness)) {
            var first = f.descriptor("v1");
            var second = f.descriptor("v2");
            var a = f.publisher();
            var b = f.publisher();
            var executor = Executors.newFixedThreadPool(2);
            var begin = new CountDownLatch(1);
            try {
                var one =
                        executor.submit(
                                () -> {
                                    begin.await();
                                    return f.store.submit(a, first);
                                });
                var two =
                        executor.submit(
                                () -> {
                                    begin.await();
                                    return peer.submit(b, second);
                                });
                begin.countDown();
                assertEquals(first.identity(), one.get(10, TimeUnit.SECONDS));
                assertEquals(second.identity(), two.get(10, TimeUnit.SECONDS));
                var human = f.reviewer();
                f.store.decide(human, f.store.review(human, first.identity()), true);
                peer.decide(human, peer.review(human, second.identity()), true);
                f.store.check(first);
                peer.check(second);
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
