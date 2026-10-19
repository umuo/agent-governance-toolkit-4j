package io.agt.tools.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.audit.jdbc.*;
import io.agt.core.*;
import io.agt.core.storage.*;
import io.agt.core.tools.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 独立 JVM 的已建立会话观察目录撤销；另验证真实文件库完全关闭后重开。 */
class ToolReleaseProcessTest {
    @TempDir Path directory;

    @Test
    void existingSessionInAnotherJvmChecksSharedDurableRevocationBeforeBusinessCall()
            throws Exception {
        try (var f = new ReleaseFixture()) {
            var descriptor = f.descriptor("v1");
            f.approve(descriptor);
            var server = Server.createTcpServer("-tcpPort", "0", "-tcpDaemon").start();
            var ready = directory.resolve("ready");
            var proceed = directory.resolve("proceed");
            var output = directory.resolve("worker.log");
            var key = f.keys.active();
            var process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty(
                                            "surefire.test.class.path",
                                            System.getProperty("java.class.path")),
                                    Worker.class.getName(),
                                    remote(server, f.primary),
                                    remote(server, f.anchors),
                                    Base64.getEncoder()
                                            .encodeToString(key.encryption().getEncoded()),
                                    Base64.getEncoder()
                                            .encodeToString(key.authentication().getEncoded()),
                                    ready.toString(),
                                    proceed.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile())
                            .start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertTrue(
                        Files.exists(ready),
                        Files.exists(output)
                                ? Files.readString(output)
                                : "Worker did not register");
                f.store.revoke(f.revoker(), descriptor.identity());
                Files.writeString(proceed, "continue");
                assertTrue(process.waitFor(20, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), Files.readString(output));
                assertTrue(
                        Files.readString(output).contains("DENIED:tool_release_revoked:0"),
                        Files.readString(output));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                }
                server.stop();
            }
        }
    }

    @Test
    void fileDatabaseReopenPreservesApprovalAndThenIrreversibleRevocation() {
        try (var f = new ReleaseFixture()) {
            f.primary.setURL("jdbc:h2:file:" + directory.resolve("releases") + ";WRITE_DELAY=0");
            f.anchors.setURL("jdbc:h2:file:" + directory.resolve("witness") + ";WRITE_DELAY=0");
            f.witness.initializeSchema();
            f.store.initializeSchema();
            var descriptor = f.descriptor("v1");
            f.approve(descriptor);
            f.store.close();
            var primary = data(f.primary.getURL());
            var anchor = new JdbcAuditWitness(data(f.anchors.getURL()), 2);
            try (var reopened =
                    new JdbcToolReleaseStore(
                            primary,
                            "tool-releases",
                            "independent-tools",
                            f.policy,
                            f.keys,
                            anchor,
                            f.clock)) {
                reopened.check(descriptor);
                reopened.revoke(f.revoker(), descriptor.identity());
            }
            try (var reopened =
                    new JdbcToolReleaseStore(
                            data(f.primary.getURL()),
                            "tool-releases",
                            "independent-tools",
                            f.policy,
                            f.keys,
                            new JdbcAuditWitness(data(f.anchors.getURL()), 2),
                            f.clock)) {
                assertEquals(
                        "tool_release_revoked",
                        assertThrows(GovernanceException.class, () -> reopened.check(descriptor))
                                .code());
            }
        }
    }

    static String remote(Server server, JdbcDataSource local) {
        return "jdbc:h2:tcp://127.0.0.1:"
                + server.getPort()
                + "/"
                + local.getURL().substring("jdbc:h2:".length())
                + ";IFEXISTS=TRUE";
    }

    static JdbcDataSource data(String url) {
        var source = new JdbcDataSource();
        source.setURL(url);
        return source;
    }

    public static final class Worker {
        public static void main(String[] args) throws Exception {
            var keys =
                    new StorageKeys() {
                        final Version version =
                                new Version(
                                        "v1",
                                        new SecretKeySpec(
                                                Base64.getDecoder().decode(args[2]), "AES"),
                                        new SecretKeySpec(
                                                Base64.getDecoder().decode(args[3]), "HmacSHA256"));

                        public Version active() {
                            return version;
                        }

                        public Version find(String id) {
                            return "v1".equals(id) ? version : null;
                        }
                    };
            try (var config = new ReleaseFixture();
                    var store =
                            new JdbcToolReleaseStore(
                                    data(args[0]),
                                    "tool-releases",
                                    "independent-tools",
                                    config.policy,
                                    keys,
                                    new JdbcAuditWitness(data(args[1]), 2),
                                    Clock.systemUTC())) {
                var descriptor = config.descriptor(store, "v1", "approved lookup");
                try (var catalog = new ReviewedToolCatalog(List.of(descriptor), 1024, store)) {
                    var lease = JdbcToolReleaseStoreTest.register(catalog, descriptor);
                    try (var control = JdbcToolReleaseStoreTest.control(lease);
                            var session = control.openSession(JdbcToolReleaseStoreTest.RUNTIME)) {
                        Files.writeString(Path.of(args[4]), "registered");
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                        while (!Files.exists(Path.of(args[5])) && System.nanoTime() < deadline) {
                            Thread.sleep(20);
                        }
                        if (!Files.exists(Path.of(args[5]))) {
                            throw new IllegalStateException("Parent did not revoke");
                        }
                        var calls = new java.util.concurrent.atomic.AtomicInteger();
                        try {
                            session.reviewedToolOperation(
                                    lease,
                                    Map.of(),
                                    v -> v,
                                    v -> {
                                        calls.incrementAndGet();
                                        return "ok";
                                    },
                                    v -> v,
                                    (o, v) -> (String) v);
                            throw new AssertionError("Revoked tool was executed");
                        } catch (GovernanceException denied) {
                            System.out.println("DENIED:" + denied.code() + ":" + calls.get());
                        }
                    }
                }
            }
        }
    }
}
