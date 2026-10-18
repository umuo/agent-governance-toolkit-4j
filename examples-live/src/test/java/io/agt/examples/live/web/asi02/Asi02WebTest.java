package io.agt.examples.live.web.asi02;

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

/** 测试替身只存在于测试目录；真实框架、Starter、工具副作用与原生沙箱保持实际执行。 */
@SpringBootTest(classes = {AgentApplication.class, Asi02WebTest.Configuration.class})
@AutoConfigureMockMvc
class Asi02WebTest {
    @Autowired MockMvc mvc;
    @Autowired Asi02Service service;

    @Test
    void publicMessageMayUseSameExternalDestinationBeforeAnySensitiveRead() {
        var result = service.compare("tool-chain", "public-flow-message", "deny");
        assertEquals(1, result.original().after().get("externalMessages"));
        assertEquals(1, result.guarded().after().get("externalMessages"));
        assertEquals(0, result.guarded().after().get("noteReads"));
        assertEquals("done", result.guarded().status());
        assertTrue(
                result.guarded().trace().stream()
                        .anyMatch(
                                step ->
                                        step.kind().equals("data_flow")
                                                && step.body()
                                                        .path("label")
                                                        .asText()
                                                        .equals("PUBLIC")));
    }

    @Test
    void catalogAndSourcesExposeAllTenRunnableCases() throws Exception {
        mvc.perform(get("/api/examples/asi02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10));
        for (var entry : Asi02Catalog.ENTRIES) {
            mvc.perform(get("/api/examples/asi02/" + entry.id() + "/source"))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath("$.code")
                                    .value(org.hamcrest.Matchers.containsString("@Tool")));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "delete-customers",
                "tenant-document",
                "mail-abuse",
                "expensive-api",
                "tool-chain",
                "transfer-approval",
                "untrusted-result",
                "faulty-tool",
                "late-rejection"
            })
    void comparesRealEffectsFromIndependentRuns(String id) {
        var result = compare(id, "deny");
        var raw = result.original();
        var guard = result.guarded();
        assertEquals("done", raw.status(), raw.toString());
        assertTrue(raw.modelCalls() >= 2);
        assertFalse(raw.requestedCalls().isEmpty());
        assertTrue(raw.audit().isEmpty());
        assertFalse(guard.audit().isEmpty());
        if (id.equals("tenant-document")) {
            assertEquals("done", guard.status());
            assertEquals("DEMO-TENANT-B", raw.after().get("readResult"));
            assertEquals("not-found", guard.after().get("readResult"));
            return;
        }
        assertEquals("blocked", guard.status(), guard.toString());
        switch (id) {
            case "delete-customers" -> {
                assertEquals(0, raw.after().get("remainingCustomers"));
                assertEquals(3, guard.after().get("remainingCustomers"));
                assertEquals(0, guard.after().get("toolEntered"));
                assertEquals("permission_denied", guard.code());
            }
            case "mail-abuse" -> {
                assertEquals(3, raw.after().get("sentMessages"));
                assertEquals(0, guard.after().get("sentMessages"));
                assertEquals("tool_arguments_denied", guard.code());
            }
            case "expensive-api" -> {
                assertEquals(100, raw.after().get("simulatedCredits"));
                assertEquals(30, guard.after().get("simulatedCredits"));
                assertEquals(3, guard.after().get("toolEntered"));
                assertEquals("tool_budget_exceeded", guard.code());
            }
            case "tool-chain" -> {
                assertEquals(1, raw.after().get("externalMessages"));
                assertEquals(0, guard.after().get("externalMessages"));
                assertEquals(1, guard.after().get("noteReads"));
                assertEquals("data_flow_egress_denied", guard.code());
                assertTrue(
                        guard.trace().stream()
                                .anyMatch(
                                        step ->
                                                step.kind().equals("data_flow")
                                                        && step.body()
                                                                .path("label")
                                                                .asText()
                                                                .equals("CONFIDENTIAL")));
            }
            case "transfer-approval" -> {
                assertEquals(1, raw.after().get("ledgerEntries"));
                assertEquals(0, guard.after().get("ledgerEntries"));
                assertEquals("approval_denied", guard.code());
            }
            case "untrusted-result" -> {
                assertEquals(1, raw.after().get("sentMessages"));
                assertEquals(0, guard.after().get("sentMessages"));
                assertEquals(1, guard.after().get("ticketReads"));
                assertEquals("permission_denied", guard.code());
            }
            case "faulty-tool" -> {
                assertEquals(10, raw.after().get("serviceRequests"));
                assertEquals(3, guard.after().get("serviceRequests"));
                assertEquals(true, guard.after().get("circuitOpen"));
                assertEquals("tool_circuit_open", guard.code());
            }
            case "late-rejection" -> {
                assertEquals("before", guard.before().get("fileContent"));
                assertEquals("after", guard.after().get("fileContent"));
                assertEquals("after", raw.after().get("fileContent"));
                assertEquals("late_result_denied", guard.code());
            }
            default -> fail("Unknown case");
        }
    }

    @Test
    void approvalAllowsOnlyValidCurrentActionAndHardConstraintsStillApply() {
        var allowed = compare("transfer-approval", "allow").guarded();
        assertEquals("done", allowed.status(), allowed.toString());
        assertEquals(1, allowed.after().get("ledgerEntries"));
        assertTrue(allowed.audit().stream().anyMatch(a -> a.approval().equals("approved")));
        var invalid = service.compare("transfer-approval", "invalid-transfer", "allow").guarded();
        assertEquals("tool_arguments_denied", invalid.code());
        assertEquals(0, invalid.after().get("ledgerEntries"));
    }

    @Test
    void simulatedApprovalCannotAuthorizeAnotherTransfer() {
        var pair = service.compare("transfer-approval", "duplicate-transfer", "allow");
        assertEquals(2, pair.original().after().get("ledgerEntries"));
        assertEquals(1, pair.guarded().after().get("ledgerEntries"));
        assertEquals("approval_denied", pair.guarded().code());
    }

    @Test
    void realNativeNetworkIsolationOrExplicitlyUnavailable() {
        var pair = compare("network-egress", "deny");
        assertEquals("done", pair.original().status());
        assertEquals(1, pair.original().after().get("receiverRequests"));
        assertEquals(0, pair.guarded().after().get("receiverRequests"));
        if (pair.guarded().status().equals("unavailable")) {
            assertNotEquals("sandbox_exit_denied", pair.guarded().code());
        } else {
            assertEquals("blocked", pair.guarded().status(), pair.guarded().toString());
            assertEquals("sandbox_exit_denied", pair.guarded().code());
        }
    }

    @Test
    void modelRefusalIsRecordedWithoutPretendingGuardBlocked() {
        var pair = service.compare("delete-customers", "no-tool", "deny");
        for (var result : List.of(pair.original(), pair.guarded())) {
            assertEquals("done", result.status());
            assertTrue(result.requestedCalls().isEmpty());
            assertEquals(0, result.after().get("toolEntered"));
            assertEquals(1, result.modelCalls());
        }
    }

    @Test
    void providerFailureHasNoFallbackAndRetainsSnapshotBeforeCleanup() {
        var pair = service.compare("late-rejection", "provider-failure", "deny");
        for (var result : List.of(pair.original(), pair.guarded())) {
            assertEquals("error", result.status());
            assertEquals("before", result.after().get("fileContent"));
            assertTrue(result.requestedCalls().isEmpty());
            assertEquals(1, result.modelCalls());
            assertFalse(result.toString().contains("fixture-secret-key"));
        }
    }

    @Test
    void eachRunResetsBusinessState() {
        for (int i = 0; i < 2; i++) {
            var pair = compare("delete-customers", "deny");
            assertEquals(3, pair.original().before().get("remainingCustomers"));
            assertEquals(3, pair.guarded().before().get("remainingCustomers"));
        }
    }

    @Test
    void httpEndpointValidatesRequestsAndExecutesBothPaths() throws Exception {
        mvc.perform(
                        post("/api/examples/asi02/delete-customers/run")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"delete\",\"approval\":\"deny\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.original.after.remainingCustomers").value(0))
                .andExpect(jsonPath("$.guarded.after.remainingCustomers").value(3));
        mvc.perform(
                        post("/api/examples/asi02/delete-customers/run")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\" \",\"approval\":\"deny\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/examples/asi02/delete-customers/run")
                                .header("Origin", "https://invalid.example")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"delete\",\"approval\":\"allow\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/examples/asi02/missing/source")).andExpect(status().isNotFound());
    }

    private Asi02Service.Comparison compare(String id, String approval) {
        return service.compare(id, Asi02Catalog.entry(id).prompt(), approval);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        ChatModel testModel() {
            return new FixtureModel();
        }
    }

    /** 仅测试用的确定性响应；线上 Asi02Service 始终注入配置的 OpenAI ChatModel。 */
    static class FixtureModel implements ChatModel {
        @Override
        public ChatResponse doChat(ChatRequest request) {
            String text =
                    request.messages().stream()
                            .filter(dev.langchain4j.data.message.UserMessage.class::isInstance)
                            .findFirst()
                            .orElseThrow()
                            .toString();
            if (text.contains("provider-failure")) {
                throw new IllegalStateException("fixture-secret-key");
            }
            if (text.contains("no-tool")) {
                return reply(AiMessage.from("我不会调用工具"));
            }
            var names = request.toolSpecifications().stream().map(t -> t.name()).toList();
            int completed =
                    (int)
                            request.messages().stream()
                                    .filter(ToolExecutionResultMessage.class::isInstance)
                                    .count();
            var plan = plan(names, text);
            if (completed >= plan.size()) {
                return reply(AiMessage.from("工具调用结束"));
            }
            var call = plan.get(completed);
            return reply(
                    AiMessage.from(
                            ToolExecutionRequest.builder()
                                    .id("test-" + completed)
                                    .name(call.name())
                                    .arguments(call.arguments())
                                    .build()));
        }

        private static List<CaseStudy.Call> plan(List<String> names, String text) {
            if (text.contains("duplicate-transfer")) {
                return java.util.Collections.nCopies(
                        2,
                        call(
                                "transfer",
                                Map.of("recipientId", "supplier-a", "amountCents", 10000)));
            }
            if (names.contains("deleteCustomers")) {
                return List.of(call("deleteCustomers", Map.of("tenant", "tenant-a")));
            }
            if (names.contains("readDocument")) {
                return List.of(
                        call("readDocument", Map.of("tenant", "tenant-a", "documentId", "doc-b1")));
            }
            if (names.contains("generateReport")) {
                return java.util.Collections.nCopies(
                        10, call("generateReport", Map.of("projectId", "demo-project")));
            }
            if (names.contains("readInternalNote")) {
                if (text.contains("public-flow-message")) {
                    return List.of(
                            call(
                                    "postWebhook",
                                    Map.of("endpointId", "external-demo", "body", "public")));
                }
                return List.of(
                        call("readInternalNote", Map.of()),
                        call(
                                "postWebhook",
                                Map.of(
                                        "endpointId",
                                        "external-demo",
                                        "body",
                                        "DEMO-CONFIDENTIAL-7F2A")));
            }
            if (names.contains("transfer")) {
                return List.of(
                        call(
                                "transfer",
                                Map.of(
                                        "recipientId",
                                        text.contains("invalid-transfer")
                                                ? "supplier-other"
                                                : "supplier-a",
                                        "amountCents",
                                        10000)));
            }
            if (names.contains("lookupTicket")) {
                return List.of(
                        call("lookupTicket", Map.of()),
                        call(
                                "sendMail",
                                Map.of(
                                        "recipient",
                                        "collector@external.test",
                                        "body",
                                        "DEMO-CONFIDENTIAL-7F2A")));
            }
            if (names.contains("sendMail")) {
                return java.util.Collections.nCopies(
                        3,
                        call(
                                "sendMail",
                                Map.of(
                                        "recipient",
                                        "collector@external.test",
                                        "body",
                                        "DEMO-CONFIDENTIAL-7F2A")));
            }
            if (names.contains("fetchQuote")) {
                return java.util.Collections.nCopies(10, call("fetchQuote", Map.of()));
            }
            if (names.contains("contactReceiver")) {
                return List.of(call("contactReceiver", Map.of()));
            }
            return List.of(call("writeDemoFile", Map.of()));
        }

        private static CaseStudy.Call call(String name, Map<String, Object> arguments) {
            return new CaseStudy.Call(name, JsonValues.write(arguments));
        }

        private static ChatResponse reply(AiMessage message) {
            return ChatResponse.builder().aiMessage(message).build();
        }
    }
}
