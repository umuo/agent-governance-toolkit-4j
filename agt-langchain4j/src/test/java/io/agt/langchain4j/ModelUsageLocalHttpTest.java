package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import io.agt.core.*;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.usage.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 本机模拟 Provider，验证官方客户端实际 HTTP 正文，价格与计量均为固定测试数据。 */
class ModelUsageLocalHttpTest {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void outputCapIsSentOnWireAndOverBudgetRequestNeverReachesProvider(boolean async, boolean money)
            throws Exception {
        var requests = new CopyOnWriteArrayList<Map<String, Object>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    requests.add(
                            JsonValues.parseObject(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8)));
                    byte[] body =
                            JsonValues.write(
                                            Map.of(
                                                    "id",
                                                    "local-usage",
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
                                                            50)))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            var provider =
                    OpenAiChatModel.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                            .apiKey("local-test-placeholder")
                            .modelName("model")
                            .maxCompletionTokens(999)
                            .maxRetries(0)
                            .timeout(Duration.ofSeconds(5))
                            .build();
            ModelUsageMeter meter =
                    (request, outputLimit) -> {
                        var parameters =
                                OpenAiChatRequestParameters.builder()
                                        .overrideWith(request.parameters())
                                        .maxCompletionTokens(outputLimit)
                                        .build();
                        return new ModelUsageMeter.Bound(
                                request.toBuilder().parameters(parameters).build(), 100);
                    };
            var limits =
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
            try (var control =
                            AgentControl.builder(StructuredPolicy.builder("wire-usage").build())
                                    .audit(record -> {})
                                    .build();
                    var root =
                            control.openTaskSession(
                                    new TrustedContext("agent", "tenant", "user", Set.of()),
                                    new TaskLimits(
                                            new BudgetLimits(10, 10, 60000),
                                            10,
                                            3,
                                            1,
                                            null,
                                            limits));
                    var binding = root.bind()) {
                var guarded =
                        new LangChain4jGovernance(
                                        control,
                                        new AiServiceOptions(
                                                StreamDelivery.REALTIME,
                                                StreamingLimits.DEFAULT,
                                                32,
                                                meter))
                                .guardModel(provider);
                var request = ChatRequest.builder().messages(UserMessage.from("request")).build();
                for (int i = 0; i < 2; i++) {
                    var response =
                            async
                                    ? guarded.chatAsync(request).get(5, TimeUnit.SECONDS)
                                    : guarded.chat(request);
                    assertEquals("ok", response.aiMessage().text());
                }
                Exception error =
                        assertThrows(
                                Exception.class,
                                () -> {
                                    if (async) guarded.chatAsync(request).get(5, TimeUnit.SECONDS);
                                    else guarded.chat(request);
                                });
                assertEquals(
                        money ? "task_cost_budget_exceeded" : "task_token_budget_exceeded",
                        ((GovernanceException)
                                        (error instanceof ExecutionException
                                                ? error.getCause()
                                                : error))
                                .code());
                assertEquals(2, requests.size());
                for (var actual : requests) {
                    assertEquals(50, ((Number) actual.get("max_completion_tokens")).intValue());
                    assertTrue(actual.get("max_tokens") == null);
                    assertEquals("model", actual.get("model"));
                }
                assertEquals(100, root.usageSnapshot().settledTokens());
                assertEquals(0, root.usageSnapshot().heldTokens());
                assertEquals(
                        0, root.usageSnapshot().settledCost().compareTo(new BigDecimal("0.00012")));
            }
        } finally {
            server.stop(0);
        }
    }
}
