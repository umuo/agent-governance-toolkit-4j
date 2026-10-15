package io.agt.examples.live;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.JsonValues;
import io.agt.core.TrustedContext;
import io.agt.core.approval.ApprovalResponse;
import io.agt.examples.live.config.AgentPolicy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 只在测试中使用本机协议夹具；生产入口始终使用配置的真实端点。 */
class GeneralAgentTest {
    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {"agent", "read-only", "approval", "budget"})
    void multiRoundTripAndGovernance(String scenario) throws Exception {
        var requests = new ArrayList<String>();
        var audit = new ArrayList<io.agt.core.audit.AuditRecord>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    requests.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    int step = requests.size();
                    String tool = step == 1 ? "writeFile" : "readFile";
                    String arguments =
                            step == 1
                                    ? "{\"path\":\"result.txt\",\"content\":\"real-file\"}"
                                    : "{\"path\":\"result.txt\"}";
                    var message =
                            step <= 2
                                    ? Map.of(
                                            "role",
                                            "assistant",
                                            "tool_calls",
                                            List.of(
                                                    Map.of(
                                                            "id",
                                                            "call-" + step,
                                                            "type",
                                                            "function",
                                                            "function",
                                                            Map.of(
                                                                    "name",
                                                                    tool,
                                                                    "arguments",
                                                                    arguments))))
                                    : Map.of("role", "assistant", "content", "verified");
                    byte[] bytes =
                            JsonValues.write(
                                            Map.of(
                                                    "id",
                                                    "response-" + step,
                                                    "object",
                                                    "chat.completion",
                                                    "created",
                                                    1,
                                                    "model",
                                                    "fixture",
                                                    "choices",
                                                    List.of(
                                                            Map.of(
                                                                    "index",
                                                                    0,
                                                                    "message",
                                                                    message,
                                                                    "finish_reason",
                                                                    step <= 2
                                                                            ? "tool_calls"
                                                                            : "stop")),
                                                    "usage",
                                                    Map.of(
                                                            "prompt_tokens",
                                                            3,
                                                            "completion_tokens",
                                                            2,
                                                            "total_tokens",
                                                            5)))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try (var control =
                        AgentControl.builder(AgentPolicy.create(scenario))
                                .audit(audit::add)
                                .approvalResolver(
                                        r ->
                                                CompletableFuture.completedFuture(
                                                        ApprovalResponse.deny(r)))
                                .build();
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "agent",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions(scenario, false)))) {
            var model =
                    OpenAiChatModel.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                            .apiKey("test-placeholder")
                            .modelName("fixture")
                            .maxRetries(0)
                            .build();
            var assistant = GeneralAgentDemo.assistant(control, model, workspace, null);
            if (scenario.equals("agent")) {
                assertEquals("verified", assistant.chat("write and read back"));
                assertEquals(3, requests.size());
                assertTrue(requests.get(2).contains("real-file"));
                assertEquals(2, session.budgetSnapshot().toolCalls());
            } else {
                var error = assertThrows(GovernanceException.class, () -> assistant.chat("write"));
                assertEquals(
                        switch (scenario) {
                            case "read-only" -> "session_failed";
                            case "approval" -> "session_failed";
                            default -> "model_budget_exceeded";
                        },
                        error.code());
                assertEquals(1, requests.size());
                assertFalse(audit.isEmpty());
                if (!scenario.equals("budget")) {
                    String expected =
                            scenario.equals("read-only") ? "permission_denied" : "approval_denied";
                    assertTrue(audit.stream().anyMatch(r -> expected.equals(r.reasonCode())));
                }
            }
            boolean written = scenario.equals("agent") || scenario.equals("budget");
            assertEquals(written, Files.exists(workspace.resolve("result.txt")));
            if (written) {
                assertEquals("real-file", Files.readString(workspace.resolve("result.txt")));
            }
        } finally {
            server.stop(0);
        }
    }
}
