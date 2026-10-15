package io.agt.examples.live;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.agt.core.AgentControl;
import io.agt.core.JsonValues;
import io.agt.examples.live.config.AgentConfig;
import io.agt.examples.live.config.AgentPolicy;
import io.agt.examples.live.console.TerminalInput;
import io.agt.examples.live.console.TerminalTrace;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 持续对话验证：保留成功历史、失败后继续、清空历史与真实执行轨迹。 */
class ConversationTest {
    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {"agent", "read-only"})
    void continuesWithMemoryAndTrace(String scenario) throws Exception {
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    requests.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    int step = requests.size();
                    var message =
                            step == 1
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
                                                                    "writeFile",
                                                                    "arguments",
                                                                    "{\"path\":\"a.txt\",\"content\":\"written\"}"))))
                                    : Map.of("role", "assistant", "content", "answer-" + step);
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
                                                                    step == 1
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
        var output = new StringWriter();
        var trace = new TerminalTrace(record -> {}, new PrintWriter(output));
        try (var control =
                AgentControl.builder(AgentPolicy.create(scenario)).audit(trace).build()) {
            var memory = MessageWindowChatMemory.withMaxMessages(60);
            var model =
                    OpenAiChatModel.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                            .apiKey("test-placeholder")
                            .modelName("fixture")
                            .maxRetries(0)
                            .build();
            var assistant =
                    GeneralAgentDemo.assistant(control, model, workspace, null, memory, trace);
            var input =
                    new TerminalInput(
                            new BufferedReader(
                                    new StringReader(
                                            "first-turn\nfollowup\n/clear\nlast-turn\n/exit\n")));
            var config = new AgentConfig("", "", "", workspace, workspace, scenario, false, false);
            GeneralAgentDemo.converse(control, assistant, memory, input, trace, config, null);
            assertTrue(output.toString().contains("[模型] 请求中"));
            assertTrue(output.toString().contains("上下文已清空"));
            assertTrue(output.toString().contains("对话已结束"));
            assertEquals(3, output.toString().split("本轮预算", -1).length - 1);
            if (scenario.equals("agent")) {
                assertEquals(4, requests.size());
                assertTrue(requests.get(2).contains("first-turn"));
                assertTrue(requests.get(2).contains("followup"));
                assertTrue(output.toString().contains("[工具 →] writeFile"));
                assertTrue(output.toString().contains("[工具 ←] writeFile"));
            } else {
                assertEquals(3, requests.size());
                assertFalse(requests.get(1).contains("first-turn"));
                assertTrue(output.toString().contains("permission_denied"));
            }
            assertFalse(requests.get(requests.size() - 1).contains("followup"));
            assertTrue(requests.get(requests.size() - 1).contains("last-turn"));
        } finally {
            server.stop(0);
        }
    }
}
