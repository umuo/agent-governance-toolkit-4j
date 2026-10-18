package io.agt.effects.jdbc;

import static io.agt.core.effects.EffectLedger.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import io.agt.core.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 真实 HTTP、持久化业务记录与独立 JVM；模拟服务不代表真实支付厂商或生产数据库验收。 */
class EffectProcessTest {
    @TempDir Path directory;
    static final Key KEY = new Key("tenant", "user", "orders", "order-7");
    static final TrustedContext ADMIN =
            new TrustedContext(
                    "reviewer",
                    "tenant",
                    "user",
                    Set.of("governance:effects:inspect", "governance:effects:reconcile"));

    static class TestClock extends Clock {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());

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

    final class Environment implements AutoCloseable {
        final String credential = UUID.randomUUID().toString(),
                databaseCredential = UUID.randomUUID().toString();
        final JdbcDataSource data = new JdbcDataSource();
        final TestClock clock = new TestClock();
        final Server sql;
        final HttpServer http;
        final ExecutorService executor = Executors.newFixedThreadPool(4);
        final List<Process> children = new ArrayList<>();
        final CountDownLatch committed = new CountDownLatch(1),
                releaseResponse = new CountDownLatch(1);
        final AtomicInteger executions = new AtomicInteger(), recoveries = new AtomicInteger();
        final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        final JdbcEffectLedger ledger;
        final boolean holdResponse;

        Environment(boolean hold) throws Exception {
            holdResponse = hold;
            sql =
                    Server.createTcpServer(
                                    "-tcpPort",
                                    "0",
                                    "-tcpDaemon",
                                    "-ifNotExists",
                                    "-baseDir",
                                    directory.toString())
                            .start();
            data.setURL(
                    "jdbc:h2:tcp://127.0.0.1:" + sql.getPort() + "/./effects;LOCK_TIMEOUT=3000");
            data.setUser("sa");
            data.setPassword(databaseCredential);
            ledger = new JdbcEffectLedger(data, "orders", clock, 3);
            ledger.initializeSchema();
            try (var connection = data.getConnection();
                    var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE business_order (id INTEGER PRIMARY KEY, state VARCHAR(40), changes INTEGER)");
                statement.executeUpdate("INSERT INTO business_order VALUES(7,'open',0)");
                statement.executeUpdate(
                        "CREATE TABLE business_effect (effect_key VARCHAR(80) PRIMARY KEY, request_digest VARCHAR(80), receipt VARCHAR(128), result_value VARCHAR(40))");
            }
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(executor);
            http.createContext("/", this::handle);
            http.start();
        }

        void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("POST")
                        || !("Bearer " + credential)
                                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                var input =
                        JsonValues.parseObject(
                                new String(
                                        exchange.getRequestBody().readNBytes(16385),
                                        StandardCharsets.UTF_8));
                assertFalse(input.containsKey("secret"));
                assertFalse(input.containsKey("lease"));
                var path = exchange.getRequestURI().getPath();
                Map<String, Object> reply;
                if (path.equals("/state")) reply = Map.of("state", state());
                else if (path.equals("/execute")) {
                    executions.incrementAndGet();
                    reply = apply(input);
                    committed.countDown();
                    if (holdResponse) assertTrue(releaseResponse.await(15, TimeUnit.SECONDS));
                } else if (path.equals("/recover")) {
                    recoveries.incrementAndGet();
                    reply = recover(input);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = JsonValues.write(reply).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException disconnected) {
                // 被杀 worker 关闭 HTTP 连接；业务提交不会因此回滚。
            } catch (Throwable failure) {
                handlerFailure.compareAndSet(null, failure);
                try {
                    exchange.sendResponseHeaders(500, -1);
                } catch (IOException ignored) {
                }
            } finally {
                exchange.close();
            }
        }

        String state() throws Exception {
            try (var connection = data.getConnection();
                    var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery("SELECT state FROM business_order WHERE id=7")) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }

        synchronized Map<String, Object> apply(Map<String, Object> input) throws Exception {
            // 此测试后端在同一事务中保存幂等结果并 CAS 业务状态；SDK 自身不假定两个数据库原子提交。
            try (var connection = data.getConnection()) {
                connection.setAutoCommit(false);
                try (var query =
                        connection.prepareStatement(
                                "SELECT request_digest,receipt,result_value FROM business_effect WHERE effect_key=?")) {
                    query.setString(1, (String) input.get("key"));
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) {
                            assertEquals(rows.getString(1), input.get("digest"));
                            var result =
                                    Map.<String, Object>of(
                                            "status",
                                            "COMMITTED",
                                            "receipt",
                                            rows.getString(2),
                                            "value",
                                            rows.getString(3));
                            connection.rollback();
                            return result;
                        }
                    }
                }
                assertEquals("FORWARD", input.get("phase"));
                assertEquals(Map.of("item", "book"), input.get("parameters"));
                try (var update =
                        connection.prepareStatement(
                                "UPDATE business_order SET state='paid',changes=changes+1 WHERE id=7 AND state=?")) {
                    update.setString(1, (String) input.get("state"));
                    assertEquals(
                            1,
                            update.executeUpdate(),
                            "Backend must enforce expected state atomically");
                }
                try (var insert =
                        connection.prepareStatement(
                                "INSERT INTO business_effect VALUES(?,?,?,?)")) {
                    insert.setString(1, (String) input.get("key"));
                    insert.setString(2, (String) input.get("digest"));
                    insert.setString(3, "receipt-7");
                    insert.setString(4, "paid");
                    insert.executeUpdate();
                }
                connection.commit();
                return Map.of("status", "COMMITTED", "receipt", "receipt-7", "value", "paid");
            }
        }

        Map<String, Object> recover(Map<String, Object> input) throws Exception {
            try (var connection = data.getConnection();
                    var query =
                            connection.prepareStatement(
                                    "SELECT request_digest,receipt,result_value FROM business_effect WHERE effect_key=?")) {
                query.setString(1, (String) input.get("key"));
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(input.get("digest"), rows.getString(1));
                    assertEquals(input.get("receipt"), rows.getString(2));
                    return Map.of("value", rows.getString(3));
                }
            }
        }

        Proof verifiedBusinessProof() throws Exception {
            try (var connection = data.getConnection();
                    var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery(
                                    "SELECT receipt,result_value FROM business_effect")) {
                assertTrue(rows.next());
                var proof = new Proof(rows.getString(1), JsonValues.digest(rows.getString(2)));
                assertFalse(rows.next());
                return proof;
            }
        }

        int changes() throws Exception {
            try (var connection = data.getConnection();
                    var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery(
                                    "SELECT changes FROM business_order WHERE id=7")) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }

        Process worker() throws Exception {
            var process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty(
                                            "surefire.test.class.path",
                                            System.getProperty("java.class.path")),
                                    EffectWorker.class.getName())
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            children.add(process);
            var config =
                    Map.of(
                            "jdbc",
                            data.getURL(),
                            "databaseCredential",
                            databaseCredential,
                            "catalog",
                            ledger.identity(),
                            "endpoint",
                            "http://127.0.0.1:" + http.getAddress().getPort(),
                            "credential",
                            credential);
            try (var writer =
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(JsonValues.write(config) + "\n");
            }
            return process;
        }

        String completed(Process process) throws Exception {
            assertTrue(
                    process.waitFor(18, TimeUnit.SECONDS), "Worker must finish within test bound");
            assertEquals(
                    0, process.exitValue(), "Worker failed before returning a sanitized outcome");
            assertNull(handlerFailure.get(), "HTTP test handler failed");
            return new String(process.getInputStream().readNBytes(4096), StandardCharsets.UTF_8)
                    .trim();
        }

        public void close() {
            releaseResponse.countDown();
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
            executor.shutdownNow();
            sql.stop();
        }
    }

    @Test
    void separateJvmRecoversBackendReceiptWithoutMutatingAgain() throws Exception {
        try (var environment = new Environment(false)) {
            assertEquals("RESULT:paid", environment.completed(environment.worker()));
            assertEquals("RESULT:paid", environment.completed(environment.worker()));
            assertEquals(1, environment.changes());
            assertEquals(1, environment.executions.get());
            assertEquals(1, environment.recoveries.get());
            assertEquals(
                    Status.COMMITTED, environment.ledger.inspect(KEY, ADMIN).forward().status());
        }
    }

    @Test
    void killAfterActualBackendCommitCannotReplayUntilTrustedReconciliation() throws Exception {
        try (var environment = new Environment(true)) {
            var worker = environment.worker();
            assertTrue(environment.committed.await(12, TimeUnit.SECONDS));
            assertEquals(1, environment.changes());
            assertEquals(Status.RUNNING, environment.ledger.inspect(KEY, ADMIN).forward().status());
            worker.destroyForcibly();
            assertTrue(worker.waitFor(3, TimeUnit.SECONDS));
            environment.releaseResponse.countDown();
            // 显式推进测试时钟模拟租约失联，随后回退；未知结果仍持久保存。
            var entry = environment.ledger.inspect(KEY, ADMIN).forward();
            environment.clock.now.set(entry.expiresAt() + 1);
            assertEquals(Status.UNKNOWN, environment.ledger.inspect(KEY, ADMIN).forward().status());
            environment.clock.now.set(1);
            assertEquals(
                    "DENIED:effect_outcome_unknown", environment.completed(environment.worker()));
            assertEquals(1, environment.executions.get());
            var proof = environment.verifiedBusinessProof();
            environment.ledger.reconcile(
                    KEY,
                    Phase.FORWARD,
                    entry.attempt(),
                    Status.COMMITTED,
                    proof,
                    ADMIN,
                    "business-lookup-7");
            assertEquals("RESULT:paid", environment.completed(environment.worker()));
            assertEquals(1, environment.changes());
            assertEquals(1, environment.executions.get());
            assertEquals(1, environment.recoveries.get());
        }
    }

    @Test
    void secondJvmCannotDispatchWhileFirstBackendResponseIsPending() throws Exception {
        try (var environment = new Environment(true)) {
            var first = environment.worker();
            assertTrue(environment.committed.await(12, TimeUnit.SECONDS));
            assertEquals("DENIED:effect_in_progress", environment.completed(environment.worker()));
            assertEquals(1, environment.executions.get());
            environment.releaseResponse.countDown();
            assertEquals("RESULT:paid", environment.completed(first));
            assertEquals("RESULT:paid", environment.completed(environment.worker()));
            assertEquals(1, environment.changes());
        }
    }
}
