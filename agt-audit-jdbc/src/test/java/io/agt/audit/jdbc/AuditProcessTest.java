package io.agt.audit.jdbc;

import static io.agt.audit.jdbc.JdbcAuditVaultTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.agt.core.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditProcessTest {
    @TempDir Path directory;

    final class Environment implements AutoCloseable {
        final String catalog = UUID.randomUUID().toString(),
                primaryCredential = UUID.randomUUID().toString(),
                witnessCredential = UUID.randomUUID().toString(),
                httpCredential = UUID.randomUUID().toString();
        final KeyRing keys = new KeyRing();
        final JdbcDataSource primary = new JdbcDataSource(), anchor = new JdbcDataSource();
        final HttpServer http;
        final AtomicInteger mutations = new AtomicInteger();
        final List<Process> children = new ArrayList<>();
        Server sql;

        Environment() throws Exception {
            startSql();
            var witness = new JdbcAuditWitness(anchor, 3);
            witness.initializeSchema();
            vault().initializeSchema();
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext(
                    "/business",
                    exchange -> {
                        try {
                            if (!("Bearer " + httpCredential)
                                    .equals(
                                            exchange.getRequestHeaders()
                                                    .getFirst("Authorization"))) {
                                exchange.sendResponseHeaders(403, -1);
                                return;
                            }
                            exchange.getRequestBody().readNBytes(16385);
                            mutations.incrementAndGet();
                            byte[] result = "business-ok".getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, result.length);
                            exchange.getResponseBody().write(result);
                        } finally {
                            exchange.close();
                        }
                    });
            http.start();
        }

        void startSql() throws Exception {
            sql =
                    Server.createTcpServer(
                                    "-tcpPort",
                                    "0",
                                    "-tcpDaemon",
                                    "-ifNotExists",
                                    "-baseDir",
                                    directory.toString())
                            .start();
            String endpoint = "jdbc:h2:tcp://127.0.0.1:" + sql.getPort() + "/./";
            primary.setURL(endpoint + "audit-main;LOCK_TIMEOUT=5000");
            primary.setUser("sa");
            primary.setPassword(primaryCredential);
            anchor.setURL(endpoint + "audit-witness;LOCK_TIMEOUT=5000");
            anchor.setUser("sa");
            anchor.setPassword(witnessCredential);
        }

        JdbcAuditVault vault() {
            return new JdbcAuditVault(
                    primary,
                    "governance",
                    catalog,
                    AuditWorker.vaultPolicy(),
                    keys,
                    new JdbcAuditWitness(anchor, 3),
                    Clock.systemUTC(),
                    3);
        }

        Process worker(String mode) throws Exception {
            var child =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty(
                                            "surefire.test.class.path",
                                            System.getProperty("java.class.path")),
                                    AuditWorker.class.getName())
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            children.add(child);
            var config = new LinkedHashMap<String, Object>();
            config.put("primary", primary.getURL());
            config.put("primaryCredential", primaryCredential);
            config.put("witness", anchor.getURL());
            config.put("witnessCredential", witnessCredential);
            config.put("catalog", catalog);
            config.put("mode", mode);
            config.put(
                    "encryptionKey",
                    Base64.getEncoder().encodeToString(keys.active.encryption().getEncoded()));
            config.put(
                    "authenticationKey",
                    Base64.getEncoder().encodeToString(keys.active.authentication().getEncoded()));
            config.put("endpoint", "http://127.0.0.1:" + http.getAddress().getPort() + "/business");
            config.put("httpCredential", httpCredential);
            child.getOutputStream()
                    .write((JsonValues.write(config) + "\n").getBytes(StandardCharsets.UTF_8));
            child.getOutputStream().flush();
            return child;
        }

        void completed(Process child) throws Exception {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), "Worker failed before sanitized outcome");
            assertEquals(
                    "OK",
                    new String(child.getInputStream().readNBytes(4096), StandardCharsets.UTF_8)
                            .trim());
        }

        public void close() {
            for (var child : children)
                if (child.isAlive()) {
                    child.destroyForcibly();
                    try {
                        child.waitFor(3, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            http.stop(0);
            sql.stop();
        }
    }

    @Test
    void twoSdkJvmsShareDurableEncryptedChainAndIndependentWitnessAcrossStorageRestart()
            throws Exception {
        try (var environment = new Environment()) {
            var first = environment.worker("normal");
            var second = environment.worker("normal");
            environment.completed(first);
            environment.completed(second);
            assertEquals(2, environment.mutations.get());
            var receipt = environment.vault().verify(READER, null);
            assertEquals(10, receipt.sequence());
            environment.sql.stop();
            environment.startSql();
            var reopened = environment.vault();
            assertEquals(receipt, reopened.verify(READER, receipt));
            var events = reopened.readMetadata(READER, 0, 100);
            assertEquals(10, events.size());
            assertFalse(
                    JsonValues.write(
                                    events.stream()
                                            .map(JdbcAuditVault.RecordView::metadata)
                                            .toList())
                            .contains("worker-private-body"));
            var captured =
                    events.stream()
                            .filter(JdbcAuditVault.RecordView::bodyAvailable)
                            .findFirst()
                            .orElseThrow();
            assertTrue(
                    JsonValues.write(reopened.readBody(ADMIN, captured.sequence()))
                            .contains("worker-private-body"));
        }
    }

    @Test
    void killedAfterPreAuditCommitCannotRunBusinessAndNextJvmRepairsPendingWitness()
            throws Exception {
        try (var environment = new Environment()) {
            var worker = environment.worker("pause");
            var signal =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return new BufferedReader(
                                                    new InputStreamReader(
                                                            worker.getInputStream(),
                                                            StandardCharsets.UTF_8))
                                            .readLine();
                                } catch (IOException failed) {
                                    throw new CompletionException(failed);
                                }
                            });
            assertEquals("COMMITTED_PENDING", signal.get(12, TimeUnit.SECONDS));
            assertEquals(0, environment.mutations.get());
            worker.destroyForcibly();
            assertTrue(worker.waitFor(3, TimeUnit.SECONDS));
            environment.completed(environment.worker("normal"));
            assertEquals(1, environment.mutations.get());
            var receipt = environment.vault().verify(READER, null);
            assertEquals(7, receipt.sequence());
            assertEquals(
                    receipt,
                    new JdbcAuditWitness(environment.anchor, 3)
                            .read(receipt.catalogId(), receipt.streamId()));
        }
    }
}
