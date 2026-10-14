package io.agt.examples.asi02;

import com.sun.net.httpserver.HttpServer;
import io.agt.examples.support.ToolScenario;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 已授权的工具内部 HTTP 请求不会自动成为第二个治理动作。
 *
 * <p>直接运行 main；公共接线在 ToolScenario，策略和工具行为在本类。
 */
public final class InternalHttpDemo {
    public static void main(String[] args) throws Exception {
        var connections = new AtomicInteger();
        // 仅访问本机回环服务，不向外部站点发送任何数据。
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/synthetic",
                exchange -> {
                    connections.incrementAndGet();
                    exchange.sendResponseHeaders(200, 2);
                    try (var output = exchange.getResponseBody()) {
                        output.write(new byte[] {'o', 'k'});
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            var uri =
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/synthetic");
            ToolScenario.run(
                    "工具内部 HTTP",
                    ToolScenario.allowed(false).build(),
                    ToolScenario.identity(Set.of("read")),
                    ToolScenario.VALID,
                    null,
                    values -> {
                        var request =
                                HttpRequest.newBuilder(uri)
                                        .timeout(Duration.ofSeconds(3))
                                        .GET()
                                        .build();
                        return HttpClient.newHttpClient()
                                .send(request, HttpResponse.BodyHandlers.ofString())
                                .body();
                    },
                    null,
                    "ok",
                    1);
            if (connections.get() != 1) {
                throw new IllegalStateException("回环 HTTP 验收失败");
            }
            System.out.println("实际 HTTP 请求=1；需工具内部目标许可、网络层控制或进程沙箱约束。");
        } finally {
            server.stop(0);
        }
    }
}
