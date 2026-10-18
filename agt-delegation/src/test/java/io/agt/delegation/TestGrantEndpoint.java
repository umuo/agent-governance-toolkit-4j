package io.agt.delegation;

import com.sun.net.httpserver.*;
import io.agt.core.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** 测试传输适配器：固定随机测试凭据映射身份；不向接收端暴露签发/根注册/撤销。 */
final class TestGrantEndpoint implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    final String credential;
    private final java.util.function.Consumer<String> observer;

    TestGrantEndpoint(GrantStatusClient registry) throws IOException {
        this(registry, ignored -> {});
    }

    TestGrantEndpoint(GrantStatusClient registry, java.util.function.Consumer<String> observer)
            throws IOException {
        this.observer = observer;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        credential = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        var context = server.createContext("/grants");
        context.setHandler(exchange -> handle(exchange, registry));
        server.start();
    }

    private void handle(HttpExchange exchange, GrantStatusClient registry) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())
                    || !("Bearer " + credential)
                            .equals(exchange.getRequestHeaders().getFirst("Authorization")))
                throw new GovernanceException("delegation_transport_denied");
            var bytes = exchange.getRequestBody().readNBytes(65537);
            if (bytes.length > 65536) throw new GovernanceException("delegation_request_denied");
            observer.accept(new String(bytes, StandardCharsets.UTF_8));
            var result =
                    new GrantStatusService((GrantRegistry) registry)
                            .handle(
                                    new AuthenticatedAgent("reader"),
                                    new String(bytes, StandardCharsets.UTF_8));
            respond(exchange, result.status(), result.body());
        } catch (GovernanceException denied) {
            respond(exchange, 403, Map.of("code", denied.code()));
        } catch (RuntimeException invalid) {
            respond(exchange, 400, Map.of("code", "delegation_request_denied"));
        } finally {
            exchange.close();
        }
    }

    static void respond(HttpExchange exchange, int status, Map<String, Object> value)
            throws IOException {
        byte[] bytes = JsonValues.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/grants");
    }

    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
