package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import io.agt.core.*;
import io.agt.core.delegation.DelegationScope;
import io.agt.core.execution.ExternalExecutionRegistry;
import io.agt.core.flow.*;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.usage.*;
import io.agt.delegation.*;
import io.agt.delegation.jdbc.JdbcGrantRegistry;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelUsageRemoteHttpTest {
    static final BudgetLimits CALLS = new BudgetLimits(10, 10, 30000);
    static KeyPair keys;

    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    static StructuredPolicy policy() {
        return StructuredPolicy.builder("remote-wire").budget(CALLS).build();
    }

    static TaskLimits receiverLimits() {
        return new TaskLimits(CALLS, 10, 1, 0);
    }

    static final class Worker implements AutoCloseable {
        final Process process;
        final BufferedWriter input;
        final BufferedReader output;
        final ExecutorService reader = Executors.newSingleThreadExecutor();

        Worker(Map<String, Object> config) throws Exception {
            process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty(
                                            "surefire.test.class.path",
                                            System.getProperty("java.class.path")),
                                    RemoteUsageWorker.class.getName())
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            input =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    process.getOutputStream(), StandardCharsets.UTF_8));
            output =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8));
            input.write(JsonValues.write(config) + "\n");
            input.flush();
            assertEquals("READY", read(12));
        }

        void send(String command) throws IOException {
            input.write(command + "\n");
            input.flush();
        }

        String read(int seconds) throws Exception {
            return reader.submit(output::readLine).get(seconds, TimeUnit.SECONDS);
        }

        String run(String command) throws Exception {
            send(command);
            return read(6);
        }

        void quit() throws Exception {
            if (process.isAlive()) send("QUIT");
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        }

        public void close() {
            process.destroyForcibly();
            reader.shutdownNow();
            try {
                input.close();
                output.close();
            } catch (IOException ignored) {
            }
        }
    }

    static final class Fixture implements AutoCloseable {
        final ExecutorService executor = Executors.newCachedThreadPool();
        final HttpServer endpoint, provider;
        final CopyOnWriteArrayList<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final GrantRegistry registry;
        final org.h2.jdbcx.JdbcDataSource database;
        final AgentControl sender = AgentControl.builder(policy()).audit(record -> {}).build();
        final HostSession source;
        final DelegationAuthority authority;
        final DelegationAuthority.Root root;
        final String credential = UUID.randomUUID().toString();
        final DelegationScope scope = new DelegationScope("remote-wire", true, Map.of(), Map.of());

        Fixture(boolean money, boolean hang) throws Exception {
            this(money, hang, false);
        }

        Fixture(boolean money, boolean hang, boolean sensitive) throws Exception {
            this(money, hang, sensitive, false);
        }

        Fixture(boolean money, boolean hang, boolean sensitive, boolean durable) throws Exception {
            if (durable) {
                database = new org.h2.jdbcx.JdbcDataSource();
                database.setURL(
                        "jdbc:h2:mem:model-work-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                var jdbc = freshRegistry();
                jdbc.initializeSchema();
                registry = jdbc;
            } else {
                database = null;
                registry = new InMemoryGrantRegistry(Clock.systemUTC());
            }
            var usage =
                    new UsageLimits(
                            money ? 1000 : 200,
                            money ? new BigDecimal("0.00026") : BigDecimal.ONE,
                            "USD",
                            Map.of(
                                    "model",
                                    new UsageLimits.ModelRate(
                                            100,
                                            50,
                                            BigDecimal.ONE,
                                            new BigDecimal("2"),
                                            Set.of())));
            source =
                    sender.openTaskSession(
                            new TrustedContext("coordinator", "tenant", "user", Set.of()),
                            new TaskLimits(
                                    CALLS,
                                    10,
                                    3,
                                    2,
                                    sensitive
                                            ? new DataFlowPolicy(
                                                    Map.of(),
                                                    Map.of(),
                                                    Map.of(),
                                                    Set.of(),
                                                    DataLabel.CONFIDENTIAL,
                                                    DataLabel.PUBLIC,
                                                    DataLabel.SECRET)
                                            : null,
                                    usage),
                            null,
                            scope);
            authority =
                    new DelegationAuthority(
                            "urn:agt:usage-test",
                            "key-1",
                            (RSAPrivateKey) keys.getPrivate(),
                            registry,
                            Clock.systemUTC());
            root = authority.register(source);
            var service = new GrantStatusService(registry);
            endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            endpoint.setExecutor(executor);
            endpoint.createContext(
                    "/grants",
                    exchange -> {
                        try (exchange) {
                            if (!"POST".equals(exchange.getRequestMethod())
                                    || !("Bearer " + credential)
                                            .equals(
                                                    exchange.getRequestHeaders()
                                                            .getFirst("Authorization"))) {
                                respond(
                                        exchange,
                                        403,
                                        Map.of("code", "delegation_transport_denied"));
                                return;
                            }
                            var bytes = exchange.getRequestBody().readNBytes(65537);
                            if (bytes.length > 65536) {
                                respond(exchange, 400, Map.of("code", "delegation_request_denied"));
                                return;
                            }
                            var response =
                                    service.handle(
                                            new AuthenticatedAgent("reader"),
                                            new String(bytes, StandardCharsets.UTF_8));
                            respond(exchange, response.status(), response.body());
                        }
                    });
            endpoint.start();
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.setExecutor(executor);
            provider.createContext(
                    "/v1/chat/completions",
                    exchange -> {
                        try (exchange) {
                            requests.add(
                                    JsonValues.parseObject(
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8)));
                            entered.countDown();
                            if (hang) {
                                try {
                                    release.await(10, TimeUnit.SECONDS);
                                } catch (InterruptedException stopped) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            respond(
                                    exchange,
                                    200,
                                    Map.of(
                                            "id",
                                            "remote-usage-test",
                                            "object",
                                            "chat.completion",
                                            "created",
                                            1,
                                            "model",
                                            "model",
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "index",
                                                            0,
                                                            "finish_reason",
                                                            "stop",
                                                            "message",
                                                            Map.of(
                                                                    "role",
                                                                    "assistant",
                                                                    "content",
                                                                    "ok"))),
                                            "usage",
                                            Map.of(
                                                    "prompt_tokens",
                                                    40,
                                                    "completion_tokens",
                                                    10,
                                                    "total_tokens",
                                                    50)));
                        }
                    });
            provider.start();
        }

        Worker worker() throws Exception {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            "model-worker",
                            Set.of(),
                            scope,
                            Duration.ofSeconds(25));
            return new Worker(
                    Map.of(
                            "token",
                            token.value(),
                            "publicKey",
                            Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                            "authority",
                            "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/grants",
                            "credential",
                            credential,
                            "provider",
                            "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1"));
        }

        UsageBudget.Snapshot snapshot() {
            return registry.usageSnapshot(root.lease(), source.sessionId());
        }

        JdbcGrantRegistry freshRegistry() {
            return new JdbcGrantRegistry(
                    database,
                    "model-work",
                    Clock.systemUTC(),
                    null,
                    3,
                    JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
        }

        public void close() {
            release.countDown();
            root.close();
            authority.close();
            source.close();
            sender.close();
            endpoint.stop(0);
            provider.stop(0);
            executor.shutdownNow();
        }
    }

    static void respond(HttpExchange exchange, int status, Map<String, Object> value)
            throws IOException {
        byte[] bytes = JsonValues.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoJvmWorkersCannotMultiplyTokenOrCostBudget(boolean money) throws Exception {
        try (var f = new Fixture(money, false);
                var a = f.worker();
                var b = f.worker()) {
            assertEquals("OK", a.run("RUN"));
            assertEquals("OK", b.run("ASYNC"));
            assertEquals(
                    "DENIED:"
                            + (money ? "task_cost_budget_exceeded" : "task_token_budget_exceeded"),
                    a.run("RUN"));
            a.quit();
            b.quit();
            assertEquals(2, f.requests.size());
            for (var request : f.requests) {
                assertEquals(50, ((Number) request.get("max_completion_tokens")).intValue());
                assertNull(request.get("max_tokens"));
                assertEquals("model", request.get("model"));
            }
            assertEquals(100, f.snapshot().settledTokens());
            assertEquals(0, f.snapshot().heldTokens());
            assertEquals(0, new BigDecimal("0.00012").compareTo(f.snapshot().settledCost()));
        }
    }

    @Test
    void killedDispatchedWorkerRetainsItsHoldAndStopsTheOtherJvm() throws Exception {
        try (var f = new Fixture(false, true);
                var a = f.worker();
                var b = f.worker()) {
            a.send("RUN");
            assertTrue(f.entered.await(5, TimeUnit.SECONDS));
            a.process.destroyForcibly();
            assertTrue(a.process.waitFor(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (f.snapshot().fault() == null && System.nanoTime() < deadline) Thread.sleep(25);
            assertEquals("model_usage_unconfirmed", f.snapshot().fault());
            assertEquals(150, f.snapshot().heldTokens());
            assertEquals(1, f.snapshot().uncertainCalls());
            assertEquals(0, f.snapshot().settledTokens());
            assertTrue(b.run("RUN").startsWith("DENIED:"));
            b.quit();
            assertEquals(1, f.requests.size());
        }
    }

    @Test
    void remoteFlowClearanceBlocksTheOfficialClientBeforeProviderOrUsageDispatch()
            throws Exception {
        try (var f = new Fixture(false, false, true);
                var worker = f.worker()) {
            assertEquals("DENIED:data_flow_model_denied", worker.run("RUN"));
            worker.quit();
            assertTrue(f.requests.isEmpty());
            assertEquals(DataLabel.CONFIDENTIAL, f.root.flowSnapshot().label());
            assertEquals(0, f.snapshot().settledTokens());
            assertEquals(0, f.snapshot().heldTokens());
            assertEquals(0, f.snapshot().uncertainCalls());
            assertNull(f.snapshot().fault());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void officialClientModelWorkIsVisibleToFreshJdbcAuthorityAfterWorkerExit(boolean killed)
            throws Exception {
        try (var f = new Fixture(false, killed, false, true);
                var a = f.worker();
                var b = f.worker()) {
            if (killed) {
                a.send("RUN");
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                a.process.destroyForcibly();
                assertTrue(a.process.waitFor(5, TimeUnit.SECONDS));
            } else {
                assertEquals("OK", a.run("RUN"));
                assertEquals("OK", b.run("ASYNC"));
                a.quit();
                b.quit();
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            ExternalExecutionRegistry.Snapshot snapshot;
            do {
                snapshot =
                        f.freshRegistry()
                                .inspect(
                                        f.root.lease().grantId(),
                                        new TrustedContext(
                                                "inspector",
                                                "tenant",
                                                "user",
                                                Set.of("governance:inspect")))
                                .external();
                if (killed ? snapshot.unknown() == 1 : snapshot.allTerminationConfirmed()) break;
                Thread.sleep(20);
            } while (System.nanoTime() < end);
            assertEquals(killed ? 1 : 2, snapshot.entries().size());
            assertTrue(snapshot.entries().stream().allMatch(e -> e.kind().equals("MODEL_REQUEST")));
            assertEquals(!killed, snapshot.allTerminationConfirmed());
            assertEquals(killed ? 1 : 0, snapshot.active());
            if (killed) {
                assertEquals("external_execution_unconfirmed", snapshot.fault());
                assertTrue(b.run("RUN").startsWith("DENIED:"));
                assertEquals(150, f.snapshot().heldTokens());
                b.quit();
            }
        }
    }
}
