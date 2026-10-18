package io.agt.delegation;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import io.agt.core.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpGrantStatusClientTest {
    @Test
    void trustedEndpointCannotContainCredentialsOrRedirectToArbitraryHosts() {
        for (String value :
                List.of(
                        "http://example.com/grants",
                        "https://user:pass@example.com/grants",
                        "https://example.com/grants?token=x",
                        "https://example.com/grants#fragment",
                        "file:///grants"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new HttpGrantStatusClient(URI.create(value), () -> "test"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HttpGrantStatusClient.loopbackForTesting(
                                URI.create("http://example.com/grants"), () -> "test"));
    }

    @Test
    void noRedirectsAndMalformedOrOversizeResponsesFailClosed() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mode = new AtomicInteger();
        var redirected = new AtomicInteger();
        server.createContext(
                "/unexpected",
                exchange -> {
                    redirected.incrementAndGet();
                    exchange.close();
                });
        server.createContext(
                "/grants",
                exchange -> {
                    try (exchange) {
                        switch (mode.get()) {
                            case 0 -> {
                                exchange.getResponseHeaders().set("Location", "/unexpected");
                                TestGrantEndpoint.respond(exchange, 307, Map.of("ok", true));
                            }
                            case 1 -> {
                                byte[] oversized = new byte[1_048_577];
                                exchange.sendResponseHeaders(200, oversized.length);
                                exchange.getResponseBody().write(oversized);
                            }
                            case 2 -> TestGrantEndpoint.respond(exchange, 200, Map.of("ok", false));
                            case 3 ->
                                    TestGrantEndpoint.respond(
                                            exchange,
                                            503,
                                            Map.of("code", "task_tool_budget_exceeded"));
                            default ->
                                    TestGrantEndpoint.respond(
                                            exchange,
                                            403,
                                            Map.of("code", "delegation_inactive_denied"));
                        }
                    }
                });
        server.start();
        try {
            var client =
                    HttpGrantStatusClient.loopbackForTesting(
                            URI.create(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                                            + "/grants"),
                            () -> "test");
            var lease = LeaseSecrets.create("grant");
            for (int i = 0; i < 4; i++) {
                mode.set(i);
                assertEquals(
                        "delegation_registry_unavailable",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> client.check(lease, "session"))
                                .code());
            }
            mode.set(4);
            assertEquals(
                    "delegation_inactive_denied",
                    assertThrows(GovernanceException.class, () -> client.check(lease, "session"))
                            .code());
            assertEquals(0, redirected.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deadlineIncludesSlowResponseBody() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        var release = new CountDownLatch(1);
        server.createContext(
                "/grants",
                exchange -> {
                    try (exchange) {
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('{');
                        exchange.getResponseBody().flush();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        server.start();
        try {
            var client =
                    HttpGrantStatusClient.loopbackForTesting(
                            URI.create(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                                            + "/grants"),
                            () -> "test");
            long start = System.nanoTime();
            assertEquals(
                    "delegation_registry_unavailable",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> client.check(LeaseSecrets.create("grant"), "session"))
                            .code());
            assertTrue(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000,
                    "Full response body must time out");
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
