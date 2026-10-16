package io.agt.examples.live.web.asi02;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.Tool;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.execution.CommandExecution;
import io.agt.core.execution.CommandLimits;
import io.agt.core.policy.StructuredPolicy;
import io.agt.sandbox.SandboxCommandBackend;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 本机接收器正向对照与真实沙箱网络拒绝；不接受模型指定 URL 或命令。 HOST 读取策略仅用于运行固定 curl，不宣称机密文件读取隔离。 */
public final class NetworkEgressCase implements CaseStudy {
    private final boolean guarded;
    private final AtomicInteger received = new AtomicInteger();
    private final HttpServer server;
    private final Path workspace;
    private AgentControl control;
    private int entered;

    public NetworkEgressCase(boolean guarded) throws Exception {
        this.guarded = guarded;
        workspace = Files.createTempDirectory("agt-asi02-network-");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    received.incrementAndGet();
                    exchange.sendResponseHeaders(200, 2);
                    try (var output = exchange.getResponseBody()) {
                        output.write(new byte[] {'O', 'K'});
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
    }

    @Override
    public void attach(AgentControl control) {
        this.control = control;
    }

    @Tool("向宿主固定的本机演示接收器发送请求")
    public String contactReceiver() throws Exception {
        entered++;
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        if (!guarded) {
            return HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(3))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body();
        }
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("mac")
                || !Files.isExecutable(Path.of("/usr/bin/curl"))) {
            throw new GovernanceException("demo_platform_unavailable");
        }
        try (var backend =
                SandboxCommandBackend.allowHostReads(
                        SandboxClient.create(),
                        SandboxPolicy.builder(workspace.toRealPath())
                                .readPolicy(ReadPolicy.HOST)
                                .network(NetworkPolicy.DENY)
                                .build())) {
            return backend.execute(
                    new CommandExecution(
                            Path.of("/usr/bin/curl").toRealPath(),
                            workspace.toRealPath(),
                            List.of("--silent", "--max-time", "1", url),
                            new CommandLimits(3000, 4096, 1)),
                    control.currentSession());
        } catch (LinkageError error) {
            throw new GovernanceException("sandbox_unavailable");
        }
    }

    public StructuredPolicy policy() {
        return StructuredPolicy.builder("asi02-network")
                .tool("contactReceiver", Rules.tool(Map.of()))
                .build();
    }

    public Map<String, Object> snapshot() {
        return Map.of("toolEntered", entered, "receiverRequests", received.get());
    }

    @Override
    public void close() throws Exception {
        server.stop(0);
        Files.deleteIfExists(workspace);
    }
}
