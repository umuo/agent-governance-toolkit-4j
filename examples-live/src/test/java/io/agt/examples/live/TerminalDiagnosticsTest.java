package io.agt.examples.live;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.agt.core.AgentControl;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 回归诊断日志：保留转换为 model_failed 之前的 HTTP 错误和完整异常链。 */
class TerminalDiagnosticsTest {
    @TempDir Path workspace;

    @Test
    void providerErrorIsVisibleBeforeGovernanceTranslation() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body =
                            "{\"error\":{\"message\":\"fixture-invalid-key\",\"type\":\"invalid_api_key\"}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(401, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        var output = new StringWriter();
        var trace = new TerminalTrace(record -> {}, new PrintWriter(output));
        try (var control = AgentControl.builder(AgentPolicy.create("agent")).audit(trace).build()) {
            var model =
                    OpenAiChatModel.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                            .apiKey("private-test-placeholder")
                            .modelName("fixture")
                            .maxRetries(0)
                            .logRequests(false)
                            .logResponses(false)
                            .build();
            var memory = MessageWindowChatMemory.withMaxMessages(60);
            var assistant =
                    GeneralAgentDemo.assistant(control, model, workspace, null, memory, trace);
            GeneralAgentDemo.converse(
                    control,
                    assistant,
                    memory,
                    new TerminalInput(new BufferedReader(new StringReader("hello\n/exit\n"))),
                    trace,
                    new AgentConfig("", "", "", workspace, workspace, "agent", false, false),
                    null);
            String log = output.toString();
            assertTrue(log.contains("[异常] 模型调用"), log);
            assertTrue(log.contains("fixture-invalid-key"), log);
            assertTrue(log.contains(".java:"), log);
            assertTrue(log.contains("model_failed"), log);
            assertTrue(log.contains("对话已结束"), log);
            assertFalse(log.contains("private-test-placeholder"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void printsNestedAndSuppressedExceptionsWithoutTruncation() {
        var output = new StringWriter();
        var trace = new TerminalTrace(record -> {}, new PrintWriter(output));
        var failure =
                new IllegalStateException(
                        "outer", new java.io.IOException("root-" + "x".repeat(1500)));
        failure.addSuppressed(new IllegalArgumentException("suppressed-detail"));
        trace.failure("test", failure);
        assertTrue(
                output.toString()
                        .contains("Caused by: java.io.IOException: root-" + "x".repeat(1500)));
        assertTrue(
                output.toString()
                        .contains(
                                "Suppressed: java.lang.IllegalArgumentException: suppressed-detail"));
    }
}
