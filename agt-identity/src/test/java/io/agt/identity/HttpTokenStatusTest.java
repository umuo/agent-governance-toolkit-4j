package io.agt.identity;

import static io.agt.identity.IdentityFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import io.agt.core.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class HttpTokenStatusTest {
    static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        final AtomicInteger calls = new AtomicInteger(), redirected = new AtomicInteger();
        final AtomicReference<String> request = new AtomicReference<>(),
                authorization = new AtomicReference<>();
        volatile byte[] response;
        volatile int code = 200, delay;
        volatile String type = "application/json";

        Endpoint(Map<String, Object> claims) throws Exception {
            var fields = new LinkedHashMap<>(claims);
            fields.put("active", true);
            response = JsonValues.write(fields).getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext(
                    "/introspect",
                    exchange -> {
                        calls.incrementAndGet();
                        request.set(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        var body = response;
                        try (exchange) {
                            exchange.getResponseHeaders().set("Content-Type", type);
                            if (code == 302)
                                exchange.getResponseHeaders()
                                        .set("Location", uri().resolve("/redirect").toString());
                            exchange.sendResponseHeaders(code, body.length);
                            if (delay > 0) {
                                try {
                                    Thread.sleep(delay);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            exchange.getResponseBody().write(body);
                        } catch (IOException ended) {
                            /* 客户端拒绝响应后正常断开。 */
                        }
                    });
            server.createContext(
                    "/redirect",
                    exchange -> {
                        redirected.incrementAndGet();
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                    });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/introspect");
        }

        HttpTokenStatus adapter(int timeout) {
            return HttpTokenStatus.loopbackForTesting(
                    uri(), () -> "Basic synthetic-test-only", timeout);
        }

        void body(Object fields) {
            response = JsonValues.write(fields).getBytes(StandardCharsets.UTF_8);
        }

        public void close() {
            server.stop(0);
            workers.shutdownNow();
        }
    }

    @Test
    void actualHttpIntrospectionBindsTokenAndCoreToolInvocation() throws Exception {
        var claims = claims();
        var token = token(claims);
        var policy = IdentitySessionTest.rules("http-v1");
        var business = new AtomicInteger();
        try (var endpoint = new Endpoint(claims);
                var verifier = verifier(endpoint.adapter(1000), new MutableClock());
                var control = AgentControl.builder(policy).audit(r -> {}).build();
                var permit =
                        verifier.verify(token)
                                .permit("agent", policy, IdentitySessionTest.scope());
                var session =
                        control.openAuthorizedSession(permit, IdentitySessionTest.limits(), null)) {
            assertEquals(
                    "ok",
                    IdentitySessionTest.call(
                            session,
                            args -> {
                                business.incrementAndGet();
                                return "ok";
                            }));
            assertTrue(endpoint.calls.get() > 1);
            assertEquals("Basic synthetic-test-only", endpoint.authorization.get());
            assertEquals(
                    "token=" + token.value() + "&token_type_hint=access_token",
                    endpoint.request.get());
            endpoint.body(Map.of("active", false));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            IdentitySessionTest.call(
                                    session,
                                    args -> {
                                        business.incrementAndGet();
                                        return "leak";
                                    }));
            assertEquals(1, business.get());
        }
    }

    @Test
    void activeAloneOrDifferentScopeCannotAuthorizeIdentity() throws Exception {
        var claims = claims();
        try (var endpoint = new Endpoint(claims);
                var verifier = verifier(endpoint.adapter(1000), new MutableClock())) {
            var token = token(claims);
            var actor = verifier.verify(token);
            actor.requireHuman();
            for (var change :
                    List.<Map.Entry<String, Object>>of(
                            Map.entry("agt_tenant", "tenant-b"),
                            Map.entry("sub", "another"),
                            Map.entry("agt_roles", List.of("reader", "reader")),
                            Map.entry("client_id", "review-ui"),
                            Map.entry("exp", NOW / 1000 + 59),
                            Map.entry("amr", List.of("pwd")),
                            Map.entry("active", "true"))) {
                var fields = new LinkedHashMap<>(claims);
                fields.put("active", true);
                fields.put(change.getKey(), change.getValue());
                endpoint.body(fields);
                assertThrows(
                        GovernanceException.class, () -> verifier.verify(token), change.getKey());
            }
            endpoint.body(Map.of("active", true));
            assertThrows(GovernanceException.class, () -> verifier.verify(token));
        }
    }

    @Test
    void redirectsMalformedBodiesAndResponseLimitFailClosed() throws Exception {
        var claims = claims();
        try (var endpoint = new Endpoint(claims);
                var verifier = verifier(endpoint.adapter(1000), new MutableClock())) {
            var token = token(claims);
            endpoint.code = 302;
            assertThrows(GovernanceException.class, () -> verifier.verify(token));
            assertEquals(0, endpoint.redirected.get());
            endpoint.code = 200;
            for (byte[] body :
                    List.of(
                            new byte[] {(byte) 0xc3, (byte) 0x28},
                            "{\"active\":true,\"active\":false}".getBytes(StandardCharsets.UTF_8),
                            new byte[65537])) {
                endpoint.response = body;
                assertThrows(GovernanceException.class, () -> verifier.verify(token));
            }
            var fields = new LinkedHashMap<>(claims);
            fields.put("active", true);
            endpoint.body(fields);
            endpoint.type = "text/plain";
            assertThrows(GovernanceException.class, () -> verifier.verify(token));
        }
    }

    @Test
    void deadlineIncludesSlowBodyAfterSuccessfulHeaders() throws Exception {
        var claims = claims();
        try (var endpoint = new Endpoint(claims);
                var verifier = verifier(endpoint.adapter(100), new MutableClock())) {
            endpoint.delay = 1000;
            long start = System.nanoTime();
            assertThrows(GovernanceException.class, () -> verifier.verify(token(claims)));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
            assertEquals(1, endpoint.calls.get());
        }
    }

    @Test
    void productionEndpointRequiresFixedHttpsAndNeverAllowsQueryCredentials() {
        for (String endpoint :
                List.of(
                        "http://127.0.0.1/introspect",
                        "https://user:secret@example.invalid/introspect",
                        "https://example.invalid/introspect?token=value",
                        "https://example.invalid/introspect#fragment"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new HttpTokenStatus(URI.create(endpoint), () -> "Basic synthetic", 1000));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HttpTokenStatus.loopbackForTesting(
                                URI.create("http://localhost/introspect"),
                                () -> "Basic synthetic",
                                1000));
    }
}
