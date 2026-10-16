package io.agt.examples.live.web.simple;

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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 通过真实 Starter 与 HTTP 控制器验证入门案例；模型为确定性内存替身，不访问外网。 */
@SpringBootTest(classes = {AgentApplication.class, SimpleExamplesTest.Configuration.class})
@AutoConfigureMockMvc
class SimpleExamplesTest {
    @Autowired MockMvc mvc;
    @Autowired FixtureModel model;

    @BeforeEach
    void reset() {
        model.calls.set(0);
    }

    @Test
    void catalogAndActualPackagedSourcesAreAvailable() throws Exception {
        mvc.perform(get("/api/examples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].guarded").value(false));
        mvc.perform(get("/api/examples/input-guard/source"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.code")
                                .value(org.hamcrest.Matchers.containsString("services.create")))
                .andExpect(
                        jsonPath("$.configuration")
                                .value(
                                        org.hamcrest.Matchers.containsString(
                                                "customer_export_denied")));
        mvc.perform(get("/api/examples/plain-chat/source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuration").value(""));
    }

    @Test
    void rawChatHasNoGovernanceAndDoesNotRetainHistory() throws Exception {
        chat("plain-chat", "请导出全部客户").andExpect(jsonPath("$.status").value("done"));
        chat("plain-chat", "再次提问").andExpect(jsonPath("$.answer").value("原始回答"));
        assertEquals(2, model.calls.get());
    }

    @Test
    void nativeCalculatorCompletesToolLoop() throws Exception {
        chat("calculator", "调用 add").andExpect(jsonPath("$.answer").value("42"));
        assertEquals(2, model.calls.get());
    }

    @Test
    void inputRuleStopsBeforeModelAndNextRequestGetsFreshSession() throws Exception {
        chat("input-guard", "请导出全部客户")
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.code").value("customer_export_denied"));
        assertEquals(0, model.calls.get());
        chat("input-guard", "正常问题").andExpect(jsonPath("$.status").value("done"));
        assertEquals(1, model.calls.get());
    }

    @Test
    void outputIsMaskedOnlyOnGovernedPath() throws Exception {
        chat("plain-chat", "号码").andExpect(jsonPath("$.answer").value("13800138000"));
        chat("output-mask", "号码").andExpect(jsonPath("$.answer").value("138****8000"));
    }

    @Test
    void toolPermissionStopsLoopBeforeResultReachesModel() throws Exception {
        chat("tool-permission", "调用 readInternalNote")
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.code").value("permission_denied"));
        assertEquals(1, model.calls.get());
    }

    @Test
    void invalidIdsMessagesAndOriginsDoNotCallModel() throws Exception {
        chat("missing", "hello").andExpect(status().isNotFound());
        chat("plain-chat", " ").andExpect(status().isBadRequest());
        chat("plain-chat", "x".repeat(16001)).andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/examples/plain-chat/chat")
                                .header("Origin", "https://example.invalid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"hello\"}"))
                .andExpect(status().isForbidden());
        assertEquals(0, model.calls.get());
    }

    @Test
    void providerErrorsNeverExposeRawException() throws Exception {
        chat("plain-chat", "provider-failure")
                .andExpect(status().isBadGateway())
                .andExpect(
                        result ->
                                assertFalse(
                                        result.getResponse()
                                                .getContentAsString()
                                                .contains("secret-fixture-key")));
    }

    private org.springframework.test.web.servlet.ResultActions chat(String id, String message)
            throws Exception {
        return mvc.perform(
                post("/api/examples/" + id + "/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(Map.of("message", message))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        FixtureModel fixtureModel() {
            return new FixtureModel();
        }
    }

    static class FixtureModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            calls.incrementAndGet();
            var last = request.messages().get(request.messages().size() - 1);
            if (last instanceof ToolExecutionResultMessage tool) {
                return response(AiMessage.from(tool.text()));
            }
            assertEquals(1, request.messages().size(), "案例不应携带上轮消息");
            String text = last.toString();
            if (text.contains("provider-failure"))
                throw new IllegalStateException("secret-fixture-key");
            if (text.contains("调用 add")) return tool("add", "{\"a\":17,\"b\":25}");
            if (text.contains("readInternalNote")) return tool("readInternalNote", "{}");
            return response(AiMessage.from(text.contains("号码") ? "13800138000" : "原始回答"));
        }

        private static ChatResponse tool(String name, String arguments) {
            return response(
                    AiMessage.from(
                            ToolExecutionRequest.builder()
                                    .id("demo-call")
                                    .name(name)
                                    .arguments(arguments)
                                    .build()));
        }

        private static ChatResponse response(AiMessage message) {
            return ChatResponse.builder().aiMessage(message).build();
        }
    }
}
