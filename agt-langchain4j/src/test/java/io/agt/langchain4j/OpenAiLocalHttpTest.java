package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.agt.core.AgentControl;
import io.agt.core.InterventionPoint;
import io.agt.core.JsonValues;
import io.agt.core.TrustedContext;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 官方 OpenAI 客户端对接本机 HTTP 测试。
 *
 * <p>验证发送到线上格式的真实消息、网络传输改写与工具循环的治理效果。
 */
class OpenAiLocalHttpTest {
    interface Assistant {
        String chat(String message);
    }

    interface AsyncAssistant {
        CompletableFuture<String> chat(String message);
    }

    static class Tools {
        final AtomicInteger effects = new AtomicInteger();

        @Tool
        String lookup(String tenant) {
            effects.incrementAndGet();
            return "private-result";
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void officialProviderRoundTripAndRedactionOnWire(boolean async) throws Exception {
        var requests = new ArrayList<String>();
        var tools = new Tools();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    requests.add(body);
                    Object message =
                            requests.size() == 1
                                    ? Map.of(
                                            "role",
                                            "assistant",
                                            "tool_calls",
                                            List.of(
                                                    Map.of(
                                                            "id",
                                                            "call-1",
                                                            "type",
                                                            "function",
                                                            "function",
                                                            Map.of(
                                                                    "name",
                                                                    "lookup",
                                                                    "arguments",
                                                                    "{\"tenant\":\"tenant-a\"}"))))
                                    : Map.of("role", "assistant", "content", "final-result");
                    String json =
                            JsonValues.write(
                                    Map.of(
                                            "id",
                                            "response-" + requests.size(),
                                            "object",
                                            "chat.completion",
                                            "created",
                                            1,
                                            "model",
                                            "local",
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "index",
                                                            0,
                                                            "message",
                                                            message,
                                                            "finish_reason",
                                                            requests.size() == 1
                                                                    ? "tool_calls"
                                                                    : "stop")),
                                            "usage",
                                            Map.of(
                                                    "prompt_tokens",
                                                    3,
                                                    "completion_tokens",
                                                    2,
                                                    "total_tokens",
                                                    5)));
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var policy =
                    StructuredPolicy.builder("http-v1")
                            .tool(
                                    "lookup",
                                    new ToolRule(
                                            Set.of("read"),
                                            ParameterRule.object(
                                                    Map.of(
                                                            "tenant",
                                                            ParameterRule.string("tenantId")),
                                                    "tenant"),
                                            false))
                            .content(
                                    new ContentRule(
                                            "redact",
                                            Set.of(InterventionPoint.POST_TOOL_CALL),
                                            List.of(),
                                            Map.of("private", "masked")))
                            .build();
            var model =
                    OpenAiChatModel.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                            .apiKey("local-test-placeholder")
                            .modelName("local")
                            .maxRetries(0)
                            .build();
            try (var control = AgentControl.builder(policy).audit(r -> {}).build();
                    var session =
                            control.openSession(
                                    new TrustedContext(
                                            "agent", "tenant-a", "user", Set.of("read")))) {
                var adapter = new LangChain4jGovernance(control);
                String answer =
                        async
                                ? adapter.aiServices(AsyncAssistant.class)
                                        .chatModel(model)
                                        .tools(tools)
                                        .build()
                                        .chat("lookup")
                                        .get(5, TimeUnit.SECONDS)
                                : adapter.aiServices(Assistant.class)
                                        .chatModel(model)
                                        .tools(tools)
                                        .build()
                                        .chat("lookup");
                assertEquals("final-result", answer);
                assertEquals(1, tools.effects.get());
                assertEquals(10, session.budgetSnapshot().tokens());
                assertEquals(2, requests.size());
                assertFalse(requests.get(1).contains("private-result"));
                assertTrue(requests.get(1).contains("masked-result"));
            }
        } finally {
            server.stop(0);
        }
    }
}
