package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import io.agt.core.*;
import io.agt.core.delegation.*;
import io.agt.core.effects.*;
import io.agt.core.policy.ParameterRule;
import io.agt.delegation.*;
import io.agt.delegation.jdbc.JdbcGrantRegistry;
import io.agt.effects.jdbc.JdbcEffectLedger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 两个真实 SDK JVM 经 HTTP/JDBC 共享受审副作用授权，不复制配额或重做业务。 */
class ReviewedRemoteProcessTest {
    @TempDir Path directory;
    static final String ISSUER = "urn:agt:reviewed-process", AUDIENCE = "worker";
    static final BudgetLimits CALLS = new BudgetLimits(2, 2, 60000);

    static TaskLimits limits(int depth) {
        return new TaskLimits(CALLS, 0, 4, depth);
    }

    static DelegationScope scope() {
        return new DelegationScope(
                "reviewed-effects", false, Map.of("pay", ParameterRule.object(Map.of())), Map.of());
    }

    static JdbcDataSource data(String url) {
        var ds = new JdbcDataSource();
        ds.setURL(url);
        return ds;
    }

    static JdbcEffectLedger effects(String url) {
        var result = new JdbcEffectLedger(data(url), "orders", Clock.systemUTC(), 4);
        result.initializeSchema();
        return result;
    }

    @Test
    void independentJvmsShareReviewedQuotaAndDurableEffectWithoutLeakingPayloadToAuthority()
            throws Exception {
        var ds =
                data(
                        "jdbc:h2:file:"
                                + directory.resolve("authority")
                                + ";WRITE_DELAY=0;LOCK_TIMEOUT=3000");
        var registry =
                new JdbcGrantRegistry(
                        ds,
                        "primary",
                        Clock.systemUTC(),
                        null,
                        4,
                        JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
        registry.initializeSchema();
        String effectUrl =
                "jdbc:h2:file:" + directory.resolve("effects") + ";WRITE_DELAY=0;LOCK_TIMEOUT=3000";
        var ledger = effects(effectUrl);
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        var requests = new CopyOnWriteArrayList<Map<String, Object>>();
        var mutations = new AtomicInteger();
        var recoveries = new AtomicInteger();
        var applied = new AtomicReference<Map<String, Object>>();
        String credential = UUID.randomUUID().toString();
        var executor = Executors.newFixedThreadPool(8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        var service = new GrantStatusService(registry);
        server.createContext(
                "/grants",
                exchange -> {
                    try (exchange) {
                        if (!("Bearer " + credential)
                                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                            exchange.sendResponseHeaders(403, -1);
                            return;
                        }
                        String input =
                                new String(
                                        exchange.getRequestBody().readNBytes(65536),
                                        StandardCharsets.UTF_8);
                        requests.add(JsonValues.parseObject(input));
                        var response = service.handle(new AuthenticatedAgent("reader"), input);
                        respond(exchange, response.status(), response.body());
                    }
                });
        server.createContext(
                "/business",
                exchange -> {
                    try (exchange) {
                        var call =
                                JsonValues.parseObject(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8));
                        switch (exchange.getRequestURI().getPath()) {
                            case "/business/state" ->
                                    respond(
                                            exchange,
                                            200,
                                            Map.of(
                                                    "state",
                                                    applied.get() == null ? "open" : "paid"));
                            case "/business/execute" -> {
                                if (!applied.compareAndSet(null, call)) {
                                    respond(exchange, 409, Map.of());
                                    return;
                                }
                                mutations.incrementAndGet();
                                respond(exchange, 200, Map.of("value", "paid"));
                            }
                            case "/business/recover" -> {
                                if (!call.equals(applied.get())) {
                                    respond(exchange, 409, Map.of());
                                    return;
                                }
                                recoveries.incrementAndGet();
                                respond(exchange, 200, Map.of("value", "paid"));
                            }
                            default -> exchange.sendResponseHeaders(404, -1);
                        }
                    }
                });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (var catalog = ReviewedEffectToolTest.catalog("v1");
                var control =
                        ReviewedEffectToolTest.control(
                                ReviewedEffectToolTest.register(catalog), ledger, r -> {});
                var source =
                        control.openTaskSession(
                                ReviewedEffectToolTest.ID, limits(2), null, scope());
                var authority =
                        new DelegationAuthority(
                                ISSUER,
                                "key",
                                (RSAPrivateKey) keys.getPrivate(),
                                registry,
                                Clock.systemUTC());
                var root = authority.register(source)) {
            assertEquals(
                    "OK:paid",
                    worker(authority, root, keys, uri, credential, effectUrl, false, "first"));
            assertEquals(
                    "OK:paid",
                    worker(authority, root, keys, uri, credential, effectUrl, true, "second"));
            assertEquals(
                    "DENIED:task_tool_budget_exceeded",
                    worker(authority, root, keys, uri, credential, effectUrl, false, "third"));
            assertEquals(1, mutations.get());
            assertEquals(1, recoveries.get());
            var reopened =
                    new JdbcGrantRegistry(
                            ds,
                            "primary",
                            Clock.systemUTC(),
                            null,
                            4,
                            JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
            assertEquals(
                    2,
                    reopened.inspect(
                                    root.lease().grantId(),
                                    new TrustedContext(
                                            "auditor",
                                            "tenant",
                                            "user",
                                            Set.of("governance:inspect")))
                            .toolCalls());
            assertEquals(
                    EffectLedger.Status.COMMITTED,
                    effects(effectUrl)
                            .inspect(ReviewedEffectToolTest.KEY, ReviewedEffectToolTest.INSPECTOR)
                            .forward()
                            .status());
            var commits =
                    requests.stream().filter(r -> "reviewed_commit".equals(r.get("op"))).toList();
            assertEquals(3, commits.size());
            assertTrue(
                    commits.stream()
                            .allMatch(
                                    r ->
                                            ReviewedEffectToolTest.register(catalog)
                                                    .identity()
                                                    .equals(r.get("binding"))));
            assertTrue(commits.stream().allMatch(r -> r.get("projection") == null));
            assertFalse(JsonValues.write(commits).contains(ReviewedEffectToolTest.INTENT.id()));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    static void respond(HttpExchange exchange, int status, Map<String, Object> value)
            throws java.io.IOException {
        byte[] body = JsonValues.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    String worker(
            DelegationAuthority authority,
            DelegationAuthority.Root root,
            KeyPair keys,
            URI uri,
            String credential,
            String effects,
            boolean async,
            String name)
            throws Exception {
        var token =
                authority.issue(
                        root.lease(),
                        "reader",
                        AUDIENCE,
                        Set.of("pay"),
                        scope(),
                        Duration.ofSeconds(40));
        var output = directory.resolve(name + ".log");
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                Worker.class.getName(),
                                token.value(),
                                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                                uri.toString(),
                                credential,
                                effects,
                                Boolean.toString(async))
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Worker did not finish");
            assertEquals(0, process.exitValue(), Files.readString(output));
            return Files.readAllLines(output).stream()
                    .filter(v -> v.startsWith("OK:") || v.startsWith("DENIED:"))
                    .findFirst()
                    .orElseThrow();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static final class Worker {
        public static void main(String[] args) throws Exception {
            var key =
                    (RSAPublicKey)
                            KeyFactory.getInstance("RSA")
                                    .generatePublic(
                                            new X509EncodedKeySpec(
                                                    Base64.getDecoder().decode(args[1])));
            URI endpoint = URI.create(args[2]);
            var http = HttpClient.newHttpClient();
            var backend =
                    new EffectBackend() {
                        Map<String, Object> request(String operation, Call call) throws Exception {
                            var input =
                                    Map.of(
                                            "key",
                                            call.idempotencyKey(),
                                            "digest",
                                            call.requestDigest());
                            var response =
                                    http.send(
                                            HttpRequest.newBuilder(
                                                            endpoint.resolve(
                                                                    "/business/" + operation))
                                                    .timeout(Duration.ofSeconds(3))
                                                    .POST(
                                                            HttpRequest.BodyPublishers.ofString(
                                                                    JsonValues.write(input)))
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString());
                            if (response.statusCode() != 200)
                                throw new IllegalStateException("Business state mismatch");
                            return JsonValues.parseObject(response.body());
                        }

                        public String currentState(Call call) throws Exception {
                            return (String) request("state", call).get("state");
                        }

                        public Reply execute(Call call) throws Exception {
                            return Reply.committed(
                                    "receipt-7", request("execute", call).get("value"));
                        }

                        public Object recover(Call call, EffectLedger.Proof proof)
                                throws Exception {
                            return request("recover", call).get("value");
                        }
                    };
            var ledger = effects(args[4]);
            try (var catalog = ReviewedEffectToolTest.catalog("v1");
                    var control =
                            ReviewedEffectToolTest.control(
                                    ReviewedEffectToolTest.register(catalog), ledger, r -> {});
                    var verifier =
                            new DelegationVerifier(
                                    ISSUER,
                                    AUDIENCE,
                                    Map.of("key", key),
                                    HttpGrantStatusClient.loopbackForTesting(
                                            endpoint.resolve("/grants"), () -> args[3]),
                                    Clock.systemUTC());
                    var session =
                            control.openAuthorizedSession(
                                    verifier.accept(
                                            new DelegationToken(args[0]),
                                            new AuthenticatedAgent("reader")),
                                    limits(1),
                                    null);
                    var binding = session.bind()) {
                var tool =
                        new LangChain4jGovernance(control)
                                .guardEffectTool(
                                        "pay", ledger, s -> ReviewedEffectToolTest.INTENT, backend);
                try {
                    var request = EffectToolTest.request("remote-call");
                    // 完整 SDK 路径含多次 1 秒 RPC 和文件库事务，不沿用本机工具的 5 秒等待。
                    String value =
                            Boolean.parseBoolean(args[5])
                                    ? tool.executeAsync(request, null)
                                            .get(25, TimeUnit.SECONDS)
                                            .resultText()
                                    : tool.execute(request, null);
                    System.out.println("OK:" + value);
                } catch (GovernanceException denied) {
                    System.out.println("DENIED:" + denied.code());
                }
            }
        }
    }
}
