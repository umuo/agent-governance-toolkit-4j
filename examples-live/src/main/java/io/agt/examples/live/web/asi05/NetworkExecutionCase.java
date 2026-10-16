package io.agt.examples.live.web.asi05;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.StructuredPolicy;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** curl 只访问宿主自建的回环接收器；防护以真实 OS 沙箱禁网。 */
public final class NetworkExecutionCase extends ExecutionCase {
    private final HttpServer server;
    private final AtomicInteger received = new AtomicInteger();

    public NetworkExecutionCase(boolean guarded) throws Exception {
        super(guarded);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    received.incrementAndGet();
                    exchange.sendResponseHeaders(200, 2);
                    try (var out = exchange.getResponseBody()) {
                        out.write(new byte[] {'O', 'K'});
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
    }

    @Tool("用固定 curl 命令联系本轮本机接收器，不接受 URL")
    public String runNetworkProgram() throws Exception {
        entered++;
        return execute(
                "/usr/bin/curl",
                List.of(
                        "--silent",
                        "--max-time",
                        "1",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                guarded,
                false,
                false,
                3000);
    }

    @Override
    public Map<String, Object> snapshot() {
        var state = new LinkedHashMap<>(super.snapshot());
        state.put("receiverRequests", received.get());
        return Map.copyOf(state);
    }

    @Override
    public StructuredPolicy policy() {
        return allow("runNetworkProgram");
    }

    @Override
    public void close() throws Exception {
        server.stop(0);
        super.close();
    }
}
