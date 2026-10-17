package io.agt.examples.live.web.asi05;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.JsonValues;
import io.agt.examples.live.web.AgentApplication;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 模型替身只用于测试；真实框架、Starter、文件、进程和原生沙箱保持实际执行。 */
@SpringBootTest(classes = {AgentApplication.class, Asi05WebTest.Configuration.class})
@AutoConfigureMockMvc
class Asi05WebTest {
    @Autowired MockMvc mvc;
    @Autowired Asi05Service service;

    @Test
    void catalogAndPackagedSourcesExposeAllNineCases() throws Exception {
        mvc.perform(get("/api/examples/asi05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9));
        for (var entry : Asi05Catalog.ENTRIES) {
            mvc.perform(get("/api/examples/asi05/" + entry.id() + "/source"))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath("$.code").value(org.hamcrest.Matchers.containsString("@Tool")))
                    .andExpect(
                            jsonPath("$.rules")
                                    .value(org.hamcrest.Matchers.containsString("FixedCommand")))
                    .andExpect(
                            jsonPath("$.runner")
                                    .value(
                                            org.hamcrest.Matchers.containsString(
                                                    "delegate.chat(request)")));
        }
        mvc.perform(get("/api/examples/asi05/missing/source")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "execution-permission",
                "argument-injection",
                "executable-integrity",
                "execution-approval"
            })
    void executionGatesPreventActualFileEffects(String id) {
        var pair = compare(id, "deny");
        // 原生固定进程演示当前仅验收 macOS，其他平台必须明确不可用。
        if (pair.original().status().equals("unavailable")) {
            assertEquals("demo_platform_unavailable", pair.original().code());
            return;
        }
        assertEquals("done", pair.original().status(), pair.toString());
        assertEquals(1, pair.original().after().get("completedExecutions"));
        assertEquals("blocked", pair.guarded().status(), pair.toString());
        assertEquals(0, pair.guarded().after().get("executionAttempts"));
        assertEquals("before", pair.guarded().after().get("workspaceContent"));
        assertEquals("before", pair.guarded().after().get("outsideContent"));
        assertTrue(pair.guarded().audit().stream().anyMatch(a -> !a.reason().isEmpty()));
        switch (id) {
            case "execution-permission" -> {
                assertEquals("executed", pair.original().after().get("workspaceContent"));
                assertEquals("tool_not_allowed", pair.guarded().code());
                assertEquals(0, pair.guarded().after().get("toolEntered"));
            }
            case "argument-injection" -> {
                assertEquals("changed", pair.original().after().get("outsideContent"));
                assertEquals("tool_arguments_denied", pair.guarded().code());
            }
            case "executable-integrity" -> {
                assertEquals("tampered", pair.original().after().get("workspaceContent"));
                assertEquals("command_integrity_denied", pair.guarded().code());
            }
            case "execution-approval" -> {
                assertEquals("published", pair.original().after().get("workspaceContent"));
                assertEquals("approval_denied", pair.guarded().code());
            }
            default -> fail("Unknown case");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "workspace-escape",
                "readonly-workspace",
                "strict-read",
                "network-execution",
                "execution-timeout"
            })
    void nativeIsolationMustHaveActualEffectsOrExplicitlyBeUnavailable(String id) {
        var pair = compare(id, "deny");
        if (pair.original().status().equals("unavailable")) return;
        assertEquals("done", pair.original().status(), pair.toString());
        assertEquals(1, pair.original().after().get("completedExecutions"));
        if (pair.guarded().status().equals("unavailable")) {
            assertNotEquals("sandbox_exit_denied", pair.guarded().code());
            return;
        }
        assertEquals("blocked", pair.guarded().status(), pair.toString());
        assertEquals(
                id.equals("execution-timeout") ? "sandbox_timeout" : "sandbox_exit_denied",
                pair.guarded().code());
        assertEquals(0, pair.guarded().after().get("completedExecutions"));
        switch (id) {
            case "workspace-escape" -> {
                assertEquals("changed", pair.original().after().get("outsideContent"));
                assertEquals("before", pair.guarded().after().get("outsideContent"));
                assertEquals("report", pair.guarded().after().get("workspaceContent"));
            }
            case "readonly-workspace" -> {
                assertEquals("[deleted]", pair.original().after().get("workspaceContent"));
                assertEquals("before", pair.guarded().after().get("workspaceContent"));
            }
            case "strict-read" -> {
                assertEquals("before", pair.original().after().get("capturedOutput"));
                assertEquals("", pair.guarded().after().get("capturedOutput"));
            }
            case "network-execution" -> {
                assertEquals(1, pair.original().after().get("receiverRequests"));
                assertEquals(0, pair.guarded().after().get("receiverRequests"));
            }
            case "execution-timeout" -> {
                assertEquals(true, pair.guarded().after().get("deadlineExceeded"));
                assertEquals(true, pair.guarded().after().get("backendQuarantined"));
            }
            default -> fail("Unknown case");
        }
    }

    @Test
    void approvalIsBoundToFirstValidActionAndCannotOverrideParameterConstraints() {
        var pair = compare("execution-approval", "allow");
        if (!pair.guarded().status().equals("unavailable")) {
            assertEquals("done", pair.guarded().status(), pair.toString());
            assertEquals("published", pair.guarded().after().get("workspaceContent"));
            assertTrue(
                    pair.guarded().audit().stream().anyMatch(a -> a.approval().equals("approved")));
            var duplicate = service.compare("execution-approval", "duplicate", "allow").guarded();
            assertEquals("approval_denied", duplicate.code());
            assertEquals(1, duplicate.after().get("completedExecutions"));
        }
        var invalid = service.compare("execution-approval", "invalid-arguments", "allow").guarded();
        assertEquals("tool_arguments_denied", invalid.code());
        assertEquals(0, invalid.after().get("toolEntered"));
    }

    @Test
    void arbitraryScriptAndPathArgumentsNeverReachAProcessEvenInOriginalPath() {
        for (var result :
                List.of(
                        service.compare("argument-injection", "invalid-arguments", "allow")
                                .original(),
                        service.compare("argument-injection", "invalid-arguments", "allow")
                                .guarded())) {
            assertEquals("blocked", result.status());
            assertEquals(0, result.after().get("executionAttempts"));
            assertEquals("before", result.after().get("outsideContent"));
        }
    }

    @Test
    void modelRefusalProviderFailureAndRunResetRemainHonest() {
        var refused = service.compare("execution-permission", "no-tool", "deny");
        for (var result : List.of(refused.original(), refused.guarded())) {
            assertEquals("done", result.status());
            assertTrue(result.requestedCalls().isEmpty());
            assertEquals(0, result.after().get("toolEntered"));
        }
        var failed = service.compare("workspace-escape", "provider-failure", "deny");
        for (var result : List.of(failed.original(), failed.guarded())) {
            assertEquals("error", result.status());
            assertEquals("before", result.after().get("outsideContent"));
            assertFalse(result.toString().contains("fixture-secret"));
            assertEquals(1, result.modelCalls());
        }
        for (int i = 0; i < 2; i++) {
            var pair = compare("argument-injection", "deny");
            assertEquals("before", pair.original().before().get("outsideContent"));
            assertEquals("before", pair.guarded().before().get("workspaceContent"));
        }
    }

    @Test
    void httpValidatesOriginPayloadAndRunsBothPaths() throws Exception {
        mvc.perform(
                        post("/api/examples/asi05/execution-permission/run")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"run\",\"approval\":\"deny\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guarded.code").value("tool_not_allowed"));
        mvc.perform(
                        post("/api/examples/asi05/execution-permission/run")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\" \",\"approval\":\"deny\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/examples/asi05/execution-permission/run")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"run\",\"approval\":\"skip\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/examples/asi05/execution-permission/run")
                                .header("Origin", "https://invalid.example")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"run\",\"approval\":\"allow\"}"))
                .andExpect(status().isOk());
    }

    private Asi05Service.Comparison compare(String id, String approval) {
        return service.compare(id, Asi05Catalog.entry(id).prompt(), approval);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        ChatModel asi05TestModel() {
            return new FixtureModel();
        }
    }

    static final class FixtureModel implements ChatModel {
        @Override
        public ChatResponse doChat(ChatRequest request) {
            String text =
                    request.messages().stream()
                            .filter(dev.langchain4j.data.message.UserMessage.class::isInstance)
                            .findFirst()
                            .orElseThrow()
                            .toString();
            if (text.contains("provider-failure"))
                throw new IllegalStateException("fixture-secret");
            if (text.contains("no-tool")) return reply(AiMessage.from("未调用工具"));
            long completed =
                    request.messages().stream()
                            .filter(ToolExecutionResultMessage.class::isInstance)
                            .count();
            if (completed >= (text.contains("duplicate") ? 2 : 1))
                return reply(AiMessage.from("实验结束"));
            String name = request.toolSpecifications().get(0).name();
            Map<String, Object> arguments =
                    switch (name) {
                        case "generateReport" ->
                                Map.of(
                                        "profile",
                                        text.contains("invalid-arguments")
                                                ? "; arbitrary code ../"
                                                : "injected");
                        case "publishReport" ->
                                Map.of(
                                        "scriptId",
                                        text.contains("invalid-arguments")
                                                ? "unreviewed"
                                                : "reviewed-report");
                        default -> Map.of();
                    };
            return reply(
                    AiMessage.from(
                            ToolExecutionRequest.builder()
                                    .id("fixture-" + completed)
                                    .name(name)
                                    .arguments(JsonValues.write(arguments))
                                    .build()));
        }

        private static ChatResponse reply(AiMessage message) {
            return ChatResponse.builder().aiMessage(message).build();
        }
    }
}
