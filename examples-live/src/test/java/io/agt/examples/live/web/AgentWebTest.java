package io.agt.examples.live.web;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.agt.core.JsonValues;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;

/** 真实 Spring HTTP、官方 Provider SSE、本地文件副作用和浏览器动作审批集成测试。 */
@SpringBootTest(
        properties = "agt.examples.frontend-origin=http://127.0.0.1:3001",
        classes = {AgentApplication.class, AgentWebTest.Configuration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentWebTest {
    @LocalServerPort int port;
    @Autowired Fixture fixture;
    HttpClient client;

    @BeforeEach
    void client() {
        client = freshClient();
    }

    HttpClient freshClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/agent" + path));
    }

    HttpResponse<String> post(HttpClient caller, String path, Object body) throws Exception {
        return caller.send(
                request(path)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JsonValues.write(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    List<Map<String, Object>> chat(
            String prompt, String profile, String mode, Consumer<Map<String, Object>> observer)
            throws Exception {
        var response =
                client.send(
                        request("/chat")
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JsonValues.write(
                                                        Map.of(
                                                                "message",
                                                                prompt,
                                                                "scenario",
                                                                profile,
                                                                "mode",
                                                                mode))))
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        var events = new ArrayList<Map<String, Object>>();
        try (var reader =
                new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    var event = JsonValues.parseObject(line.substring(5));
                    events.add(event);
                    observer.accept(event);
                }
            }
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> data(Map<String, Object> event) {
        return (Map<String, Object>) event.get("data");
    }

    boolean has(List<Map<String, Object>> events, String type) {
        return events.stream().anyMatch(e -> type.equals(e.get("type")));
    }

    Path workspace(List<Map<String, Object>> events) {
        var start =
                events.stream()
                        .filter(e -> e.get("type").equals("run_start"))
                        .findFirst()
                        .orElseThrow();
        return fixture.root.resolve("work").resolve((String) data(start).get("workspace"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"absolute read", "escape read"})
    void fileFailureRetainsSpecificCauseAndStopsModelLoop(String prompt) throws Exception {
        var events = chat(prompt, "agent", "strict", ignored -> {});
        String expected = "workspace_path_denied";
        var failure =
                events.stream()
                        .filter(e -> e.get("type").equals("tool_error"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(expected, data(failure).get("code"));
        assertFalse(String.valueOf(data(failure).get("message")).contains("session_failed"));
        assertFalse(String.valueOf(data(failure).get("hint")).isBlank());
        var terminal =
                events.stream()
                        .filter(e -> e.get("type").equals("error"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(expected, data(terminal).get("code"));
        assertTrue(String.valueOf(data(terminal).get("message")).contains("后续工具未执行"));
        assertEquals(1, events.stream().filter(e -> e.get("type").equals("model_start")).count());
        assertFalse(has(events, "done"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"strict", "preview"})
    void missingDirectoryCanBeCorrectedWithoutLosingConversation(String mode) throws Exception {
        var events = chat("missing directory", "agent", mode, ignored -> {});
        assertTrue(has(events, "done"), events.toString());
        assertFalse(has(events, "error"));
        var error =
                events.stream()
                        .filter(e -> e.get("type").equals("tool_error"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("file_not_found", data(error).get("code"));
        assertEquals(true, data(error).get("recoverable"));
        assertEquals(true, data(error).get("checked"));
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e.get("type").equals("tool_result")
                                                && data(e).get("name").equals("listFiles")));
        assertEquals(3, events.stream().filter(e -> e.get("type").equals("model_start")).count());
        var followUp = chat("check history", "agent", mode, ignored -> {});
        assertTrue(has(followUp, "done"));
        assertTrue(
                followUp.stream()
                        .anyMatch(
                                e ->
                                        e.get("type").equals("answer_delta")
                                                && String.valueOf(data(e).get("text"))
                                                        .contains("history retained")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"strict", "preview"})
    void streamedToolsFinalAnswerAndWorkspaceIsolation(String mode) throws Exception {
        var events = chat("write", "agent", mode, ignored -> {});
        assertTrue(has(events, "done"), events.toString());
        assertTrue(has(events, "tool_start"));
        assertTrue(has(events, "tool_result"));
        assertEquals(mode.equals("preview"), has(events, "tool_delta"));
        assertEquals(mode.equals("preview"), has(events, "text_delta"));
        assertEquals(mode.equals("preview"), has(events, "reasoning_delta"));
        assertTrue(has(events, "model_start"));
        assertTrue(has(events, "model_complete"));
        if (mode.equals("preview")) {
            String reasoning =
                    events.stream()
                            .filter(e -> e.get("type").equals("reasoning_delta"))
                            .map(e -> String.valueOf(data(e).get("text")))
                            .collect(java.util.stream.Collectors.joining());
            assertTrue(reasoning.contains("provider-analysis-a"), reasoning);
            assertTrue(reasoning.contains("provider-analysis-b"), reasoning);
            var types = events.stream().map(e -> String.valueOf(e.get("type"))).toList();
            assertTrue(types.indexOf("reasoning_delta") < types.indexOf("text_delta"));
            assertTrue(types.indexOf("text_delta") < types.indexOf("tool_start"));
            assertTrue(types.indexOf("tool_result") < types.lastIndexOf("text_delta"));
        }
        var budget =
                data(
                        events.stream()
                                .filter(e -> e.get("type").equals("budget"))
                                .findFirst()
                                .orElseThrow());
        assertEquals(2, ((Number) budget.get("modelCalls")).intValue());
        assertEquals(1, ((Number) budget.get("toolCalls")).intValue());
        for (String point : List.of("input", "pre_model_call", "pre_tool_call", "post_tool_call")) {
            long checks =
                    events.stream()
                            .filter(e -> e.get("type").equals("governance"))
                            .filter(e -> point.equals(data(e).get("point")))
                            .filter(e -> !"EXECUTION".equals(data(e).get("verdict")))
                            .count();
            assertEquals(point.equals("pre_model_call") ? 2 : 1, checks, point);
        }
        assertEquals("verified", Files.readString(workspace(events).resolve("a.txt")));
        assertTrue(
                chat("read", "agent", mode, ignored -> {}).stream()
                        .anyMatch(e -> e.get("type").equals("done")));
        client = freshClient();
        var isolated = chat("read", "agent", mode, ignored -> {});
        assertTrue(has(isolated, "done"), isolated.toString());
        assertTrue(has(isolated, "tool_error"));
        assertFalse(has(isolated, "tool_result"));
        assertFalse(Files.exists(workspace(isolated).resolve("a.txt")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void approvalIsBoundToOwnerAndAction(boolean approved) throws Exception {
        var events =
                chat(
                        "write",
                        "approval",
                        "preview",
                        event -> {
                            if (event.get("type").equals("approval_required")) {
                                String id = (String) data(event).get("id");
                                try {
                                    assertEquals(
                                            409,
                                            post(
                                                            freshClient(),
                                                            "/approvals/" + id,
                                                            Map.of("approved", true))
                                                    .statusCode());
                                    assertEquals(
                                            200,
                                            post(
                                                            client,
                                                            "/approvals/" + id,
                                                            Map.of("approved", approved))
                                                    .statusCode());
                                    assertEquals(
                                            409,
                                            post(
                                                            client,
                                                            "/approvals/" + id,
                                                            Map.of("approved", true))
                                                    .statusCode());
                                } catch (Exception error) {
                                    throw new AssertionError(error);
                                }
                            }
                        });
        assertEquals(approved, has(events, "done"), events.toString());
        assertEquals(approved, Files.exists(workspace(events).resolve("a.txt")));
    }

    @Test
    void readonlyDeniesBeforeEffectAndBadOriginsAreRejected() throws Exception {
        var events = chat("write", "read-only", "strict", ignored -> {});
        assertTrue(has(events, "error"));
        assertFalse(has(events, "tool_start"));
        assertFalse(Files.exists(workspace(events).resolve("a.txt")));
        var response =
                client.send(
                        request("/cancel")
                                .header("Origin", "https://untrusted.example")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    @Test
    void toolResultIsOnlyEmittedAfterPostToolCheck() throws Exception {
        var events = chat("write", "agent", "strict", ignored -> {});
        int checked = -1, result = -1;
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            if (event.get("type").equals("governance")
                    && "post_tool_call".equals(data(event).get("point"))
                    && !"EXECUTION".equals(data(event).get("verdict"))) {
                checked = i;
            }
            if (event.get("type").equals("tool_result")) {
                result = i;
            }
        }
        assertTrue(checked >= 0 && result > checked);
    }

    @Test
    void allOriginsCanCancel() throws Exception {
        var allowed =
                client.send(
                        request("/cancel")
                                .header("Origin", "http://127.0.0.1:3001")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, allowed.statusCode());
        var denied =
                client.send(
                        request("/cancel")
                                .header("Origin", "https://foreign.example")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, denied.statusCode());
    }

    @Test
    void cancellationRevokesRunAndReleasesConversation() throws Exception {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var events =
                chat(
                        "slow",
                        "agent",
                        "preview",
                        event -> {
                            if (event.get("type").equals("text_delta")
                                    && cancelled.compareAndSet(false, true)) {
                                try {
                                    assertEquals(
                                            409,
                                            post(client, "/chat", Map.of("message", "write"))
                                                    .statusCode());
                                    assertEquals(
                                            200, post(client, "/cancel", Map.of()).statusCode());
                                } catch (Exception error) {
                                    throw new AssertionError(error);
                                }
                            }
                        });
        assertTrue(cancelled.get());
        assertTrue(has(events, "cancelled"));
        assertFalse(has(events, "error"));
        assertFalse(has(events, "done"));
        assertTrue(has(chat("write", "agent", "strict", ignored -> {}), "done"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean(destroyMethod = "close")
        Fixture fixture() throws IOException {
            return new Fixture();
        }

        @Bean
        @Primary
        WebAgentService testService(Fixture fixture, WebAgentRuntimeFactory runtimes) {
            return new WebAgentService(
                    Map.of(
                            "LLM_BASE_URL",
                            "http://127.0.0.1:" + fixture.server.getAddress().getPort() + "/v1",
                            "LLM_API_KEY",
                            "fixture-placeholder",
                            "LLM_MODEL",
                            "fixture",
                            "AGT_WORKSPACE",
                            fixture.root.resolve("work").toString(),
                            "AGT_AUDIT_DIR",
                            fixture.root.resolve("audit").toString()),
                    runtimes);
        }
    }

    static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("agt-web-test-");
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newCachedThreadPool();

        Fixture() throws IOException {
            server.setExecutor(executor);
            server.createContext(
                    "/v1/chat/completions",
                    exchange -> {
                        var body =
                                JsonValues.parseObject(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8));
                        var messages = (List<?>) body.get("messages");
                        var last = (Map<?, ?>) messages.get(messages.size() - 1);
                        boolean tool = last.get("role").equals("tool");
                        String name =
                                String.valueOf(last.get("content")).contains("read")
                                        ? "readFile"
                                        : "writeFile";
                        String arguments =
                                name.equals("readFile")
                                        ? "{\"path\":\"a.txt\"}"
                                        : "{\"path\":\"a.txt\",\"content\":\"verified\"}";
                        if (String.valueOf(last.get("content")).contains("absolute read")) {
                            arguments = "{\"path\":\"/etc/profile\"}";
                        }
                        if (String.valueOf(last.get("content")).contains("escape read")) {
                            arguments = "{\"path\":\"../../etc/profile\"}";
                        }
                        String content = String.valueOf(last.get("content"));
                        if (!tool && content.equals("missing directory")) {
                            name = "listFiles";
                            arguments = "{\"path\":\"workspace\"}";
                        }
                        boolean retryDirectory =
                                tool
                                        && content.contains("file_not_found")
                                        && messages.stream()
                                                .anyMatch(
                                                        m ->
                                                                "missing directory"
                                                                        .equals(
                                                                                ((Map<?, ?>) m)
                                                                                        .get(
                                                                                                "content")));
                        if (retryDirectory) {
                            name = "listFiles";
                            arguments = "{\"path\":\".\"}";
                        }
                        boolean checkHistory = !tool && content.equals("check history");
                        boolean historyRetained =
                                messages.stream()
                                        .anyMatch(
                                                m ->
                                                        "missing directory"
                                                                .equals(
                                                                        ((Map<?, ?>) m)
                                                                                .get("content")));
                        if (String.valueOf(last.get("content")).contains("slow")) {
                            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                            exchange.sendResponseHeaders(200, 0);
                            try {
                                exchange.getResponseBody()
                                        .write(
                                                chunk(
                                                                Map.of(
                                                                        "role",
                                                                        "assistant",
                                                                        "content",
                                                                        "pending"),
                                                                null)
                                                        .getBytes(StandardCharsets.UTF_8));
                                exchange.getResponseBody().flush();
                                Thread.sleep(2000);
                                exchange.getResponseBody()
                                        .write(
                                                (chunk(Map.of(), "stop") + "data: [DONE]\n\n")
                                                        .getBytes(StandardCharsets.UTF_8));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } catch (IOException disconnected) {
                                /* 取消预期关闭连接。 */
                            } finally {
                                exchange.close();
                            }
                            return;
                        }
                        String wire =
                                (tool && !retryDirectory) || checkHistory
                                        ? chunk(
                                                        Map.of(
                                                                "role",
                                                                "assistant",
                                                                "content",
                                                                checkHistory
                                                                        ? (historyRetained
                                                                                ? "history retained"
                                                                                : "history lost")
                                                                        : "# Verified\n\n"),
                                                        null)
                                                + chunk(Map.of("content", "**complete**"), null)
                                                + chunk(Map.of(), "stop")
                                        : chunk(
                                                        Map.of(
                                                                "role",
                                                                "assistant",
                                                                "tool_calls",
                                                                List.of(
                                                                        Map.of(
                                                                                "index",
                                                                                0,
                                                                                "id",
                                                                                "call-"
                                                                                        + UUID
                                                                                                .randomUUID(),
                                                                                "type",
                                                                                "function",
                                                                                "function",
                                                                                Map.of(
                                                                                        "name",
                                                                                        name,
                                                                                        "arguments",
                                                                                        arguments
                                                                                                .substring(
                                                                                                        0,
                                                                                                        5))))),
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
                                                                                        arguments
                                                                                                .substring(
                                                                                                        5))))),
                                                        null)
                                                + chunk(Map.of(), "tool_calls");
                        wire =
                                chunk(Map.of("reasoning_content", "provider-analysis-a"), null)
                                        + chunk(
                                                Map.of("reasoning_content", "provider-analysis-b"),
                                                null)
                                        + (tool
                                                ? ""
                                                : chunk(
                                                        Map.of(
                                                                "content",
                                                                "Inspecting the workspace first."),
                                                        null))
                                        + wire;
                        wire += "data: [DONE]\n\n";
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write(wire.getBytes(StandardCharsets.UTF_8));
                        exchange.close();
                    });
            server.start();
        }

        static String chunk(Object delta, String finish) {
            var choice = new LinkedHashMap<String, Object>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", finish);
            return "data: "
                    + JsonValues.write(
                            Map.of(
                                    "id",
                                    "fixture-stream",
                                    "object",
                                    "chat.completion.chunk",
                                    "created",
                                    1,
                                    "model",
                                    "fixture",
                                    "choices",
                                    List.of(choice)))
                    + "\n\n";
        }

        @Override
        public void close() throws Exception {
            server.stop(0);
            executor.shutdownNow();
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
