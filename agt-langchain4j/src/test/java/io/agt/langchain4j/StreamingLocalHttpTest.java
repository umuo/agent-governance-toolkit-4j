package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.TokenStream;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 官方流式客户端连接本机 SSE 测试。
 *
 * <p>核对真实工具参数、下一轮请求与最终交付，验证端到端流式治理效果。
 */
class StreamingLocalHttpTest {
    interface Assistant {
        TokenStream chat(String message);
    }

    static class Tools {
        final AtomicInteger effects = new AtomicInteger();

        @Tool
        String lookup(String tenant) {
            effects.incrementAndGet();
            return "secret-tool";
        }
    }

    static String chunk(Object delta, String finish) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finish);
        return "data: "
                + JsonValues.write(
                        Map.of(
                                "id",
                                "local-stream",
                                "object",
                                "chat.completion.chunk",
                                "created",
                                1,
                                "model",
                                "local",
                                "choices",
                                List.of(choice)))
                + "\n\n";
    }

    @Test
    void officialSseToolLoopDeliversOnlyRewrittenFinal() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
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
                    String data;
                    if (requests.size() == 1) {
                        data =
                                chunk(
                                                Map.of(
                                                        "role",
                                                        "assistant",
                                                        "tool_calls",
                                                        List.of(
                                                                Map.of(
                                                                        "index",
                                                                        0,
                                                                        "id",
                                                                        "id-1",
                                                                        "type",
                                                                        "function",
                                                                        "function",
                                                                        Map.of(
                                                                                "name",
                                                                                "lookup",
                                                                                "arguments",
                                                                                "{\"tenant\":\"")))),
                                                null)
                                        + chunk(
                                                Map.of(
                                                        "tool_calls",
                                                        List.of(
                                                                Map.of(
                                                                        "index",
                                                                        0,
                                                                        "function",
                                                                        Map.of(
                                                                                "arguments",
                                                                                "tenant\"}")))),
                                                null)
                                        + chunk(Map.of(), "tool_calls");
                    } else {
                        data =
                                chunk(Map.of("role", "assistant", "content", "sec"), null)
                                        + chunk(Map.of("content", "ret-final"), null)
                                        + chunk(Map.of(), "stop");
                    }
                    data +=
                            "data: "
                                    + JsonValues.write(
                                            Map.of(
                                                    "id",
                                                    "local-stream",
                                                    "object",
                                                    "chat.completion.chunk",
                                                    "created",
                                                    1,
                                                    "model",
                                                    "local",
                                                    "choices",
                                                    List.of(),
                                                    "usage",
                                                    Map.of(
                                                            "prompt_tokens",
                                                            3,
                                                            "completion_tokens",
                                                            2,
                                                            "total_tokens",
                                                            5)))
                                    + "\n\ndata: [DONE]\n\n";
                    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var model =
                    OpenAiStreamingChatModel.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                            .apiKey("local-test-placeholder")
                            .modelName("local")
                            .build();
            var p =
                    StructuredPolicy.builder("sse-v3")
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
                                            Set.of(
                                                    InterventionPoint.POST_TOOL_CALL,
                                                    InterventionPoint.OUTPUT),
                                            List.of(),
                                            Map.of("secret", "masked")))
                            .build();
            try (var control = AgentControl.builder(p).audit(r -> {}).build();
                    var session =
                            control.openSession(
                                    new TrustedContext(
                                            "agent", "tenant", "user", Set.of("read")))) {
                var stream =
                        LangChain4jGovernance.buffered(
                                new LangChain4jGovernance(control)
                                        .aiServices(Assistant.class)
                                        .streamingChatModel(model)
                                        .tools(tools)
                                        .build()
                                        .chat("lookup"));
                var partial = new CopyOnWriteArrayList<String>();
                stream.onPartialResponse(partial::add).start();
                assertEquals(
                        "masked-final",
                        stream.completion().get(10, TimeUnit.SECONDS).aiMessage().text());
                assertEquals(List.of("masked-final"), partial);
                assertEquals(2, requests.size());
                assertEquals(Boolean.TRUE, JsonValues.parseObject(requests.get(0)).get("stream"));
                assertFalse(requests.get(1).contains("secret-tool"));
                assertTrue(requests.get(1).contains("masked-tool"));
                assertEquals(1, tools.effects.get());
                assertEquals(10, session.budgetSnapshot().tokens());
            }
        } finally {
            server.stop(0);
        }
    }
}
