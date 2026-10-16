package io.agt.examples.live.web.experiment;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.agt.examples.live.web.WebAgentRuntimeFactory;
import io.agt.examples.live.web.asi02.Asi02Service;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;

/** 真实 OpenAI HTTP 序列化与工具循环；本机端点仅用于测试，网页不提供模型响应替身。 */
class ExperimentTraceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void capturesActualWireBodiesFullToolLoopAndShutdownWithoutCredentials() throws Exception {
        var received = new CopyOnWriteArrayList<JsonNode>();
        var server = endpoint(received, false);
        try {
            var pair = service(server).compare("delete-customers", "delete", "deny");
            var raw = pair.original();
            var guard = pair.guarded();
            assertEquals("done", raw.status(), raw.toString());
            assertEquals("permission_denied", guard.code());
            var requests =
                    raw.trace().stream().filter(s -> s.kind().equals("http_request")).toList();
            assertEquals(2, requests.size());
            assertEquals(received.get(0), requests.get(0).body());
            assertEquals("trace-fixture", requests.get(0).body().path("model").asText());
            assertTrue(requests.get(0).body().path("tools").toString().contains("deleteCustomers"));
            assertEquals(
                    "system", requests.get(0).body().path("messages").get(0).path("role").asText());
            assertTrue(requests.get(1).body().path("messages").toString().contains("tool_call_id"));
            assertTrue(requests.get(1).body().path("messages").toString().contains("tool-1"));
            assertEquals(received.get(1), requests.get(1).body());
            assertEquals(
                    2, raw.trace().stream().filter(s -> s.kind().equals("http_response")).count());
            assertTrue(
                    raw.trace().stream()
                            .anyMatch(
                                    s ->
                                            s.kind().equals("tool_result")
                                                    && s.body()
                                                                    .path("state")
                                                                    .path("remainingCustomers")
                                                                    .asInt()
                                                            == 0));
            assertTrue(
                    guard.trace().stream()
                            .noneMatch(
                                    s ->
                                            s.kind().equals("tool_start")
                                                    || s.kind().equals("tool_result")));
            assertTrue(
                    guard.trace().stream()
                            .anyMatch(
                                    s ->
                                            s.kind().equals("governance")
                                                    && s.body()
                                                            .path("audit")
                                                            .path("point")
                                                            .asText()
                                                            .equals("agent_shutdown")));
            for (var result : List.of(raw, guard)) {
                for (int i = 0; i < result.trace().size(); i++)
                    assertEquals(i + 1, result.trace().get(i).sequence());
                assertEquals("run_end", result.trace().get(result.trace().size() - 1).kind());
                String output = JSON.writeValueAsString(result);
                assertFalse(output.contains("fixture-auth-secret"));
                assertFalse(output.contains("Authorization"));
                assertFalse(output.contains("127.0.0.1"));
                assertFalse(output.contains("captureError"), output);
            }
            assertNull(ExperimentTrace.current());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerFailureKeepsAttemptBodyButHidesRawErrorAndUnbindsScope() throws Exception {
        var server = endpoint(new CopyOnWriteArrayList<>(), true);
        try {
            var pair = service(server).compare("delete-customers", "delete", "deny");
            for (var result : List.of(pair.original(), pair.guarded())) {
                assertEquals("error", result.status());
                assertTrue(result.trace().stream().anyMatch(s -> s.kind().equals("http_request")));
                assertTrue(result.trace().stream().anyMatch(s -> s.kind().equals("http_error")));
                assertFalse(JSON.writeValueAsString(result).contains("fixture-auth-secret"));
            }
            assertNull(ExperimentTrace.current());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nestedScopesAndConcurrentCallsCannotMixTraces() throws Exception {
        var outer = new ExperimentTrace();
        var inner = new ExperimentTrace();
        try (var scope = outer.bind()) {
            assertSame(outer, ExperimentTrace.current());
            try (var nested = inner.bind()) {
                assertSame(inner, ExperimentTrace.current());
            }
            assertSame(outer, ExperimentTrace.current());
            var pool = Executors.newFixedThreadPool(2);
            try {
                var jobs = new ArrayList<java.util.concurrent.Future<List<ExperimentTrace.Step>>>();
                for (int i = 0; i < 2; i++) {
                    int id = i;
                    jobs.add(
                            pool.submit(
                                    () -> {
                                        assertNull(ExperimentTrace.current());
                                        var trace = new ExperimentTrace();
                                        try (var local = trace.bind()) {
                                            ExperimentTrace.current()
                                                    .add("request", Map.of("id", id));
                                        }
                                        assertNull(ExperimentTrace.current());
                                        return trace.snapshot();
                                    }));
                }
                for (int i = 0; i < jobs.size(); i++)
                    assertEquals(i, jobs.get(i).get().get(0).body().path("id").asInt());
            } finally {
                pool.shutdownNow();
            }
        }
        assertNull(ExperimentTrace.current());
    }

    private static Asi02Service service(HttpServer server) {
        var model =
                OpenAiChatModel.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                        .apiKey("fixture-auth-secret")
                        .modelName("trace-fixture")
                        .maxRetries(0)
                        .timeout(Duration.ofSeconds(3))
                        .httpClientBuilder(new TraceHttpClientBuilder())
                        .build();
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("model", model);
        return new Asi02Service(
                new WebAgentRuntimeFactory(new StandardEnvironment()),
                beans.getBeanProvider(ChatModel.class));
    }

    private static HttpServer endpoint(List<JsonNode> received, boolean fail) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    JsonNode body = JSON.readTree(exchange.getRequestBody());
                    received.add(body);
                    boolean toolResult = false;
                    for (var message : body.path("messages"))
                        if (message.path("role").asText().equals("tool")) toolResult = true;
                    String message =
                            toolResult
                                    ? "{\"role\":\"assistant\",\"content\":\"done\"}"
                                    : "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"tool-1\",\"type\":\"function\",\"function\":{\"name\":\"deleteCustomers\",\"arguments\":\"{\\\"tenant\\\":\\\"tenant-a\\\"}\"}}]}";
                    String response =
                            fail
                                    ? "{\"error\":{\"message\":\"fixture-auth-secret\"}}"
                                    : "{\"id\":\"test-response\",\"model\":\"trace-fixture\",\"choices\":[{\"index\":0,\"message\":"
                                            + message
                                            + ",\"finish_reason\":\""
                                            + (toolResult ? "stop" : "tool_calls")
                                            + "\"}]}";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        return server;
    }
}
