package io.agt.examples.live.web.tracing;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.examples.live.web.AgentApplication;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = {AgentApplication.class, TraceWebTest.Configuration.class})
@AutoConfigureMockMvc
class TraceWebTest {
    static final Path directory;

    static {
        try {
            directory = Files.createTempDirectory("agt-trace-test-");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        props.add("agt.examples.traces-directory", directory::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired MultiAgentTraceService service;
    @Autowired FixtureModel model;

    @Test
    void realParallelToolLoopProducesRecoverableTreeAndExactDenial() throws Exception {
        model.fail = false;
        var response =
                mvc.perform(post("/api/agent-traces"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        String id =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(response.getContentAsString())
                        .path("traceId")
                        .asText();
        var view = finished(id);
        assertEquals("completed", view.status());
        var agents = view.spans().stream().filter(n -> n.kind().equals("agent")).toList();
        assertEquals(3, agents.size());
        var root =
                agents.stream()
                        .filter(n -> n.agentId().equals("coordinator-agent"))
                        .findFirst()
                        .orElseThrow();
        var children = agents.stream().filter(n -> !n.spanId().equals(root.spanId())).toList();
        assertEquals(2, children.stream().map(TraceStore.Node::runId).distinct().count());
        assertTrue(children.stream().allMatch(n -> n.parentRunId().equals(root.runId())));
        assertTrue(
                children.get(0).startNanos() < children.get(1).endNanos()
                        && children.get(1).startNanos() < children.get(0).endNanos(),
                "两个子 Agent 实际并行");
        Set<String> spans =
                view.spans().stream()
                        .map(TraceStore.Node::spanId)
                        .collect(java.util.stream.Collectors.toSet());
        assertTrue(
                view.spans().stream()
                        .filter(n -> !n.spanId().equals(root.spanId()))
                        .allMatch(n -> spans.contains(n.parentSpanId())));
        assertEquals(1, view.effects().get("factReads"));
        assertEquals(0, view.effects().getOrDefault("deliveries", 0));
        var denied =
                view.spans().stream()
                        .filter(n -> n.name().equals("tool deliverDigest"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(root.runId(), denied.parentRunId());
        assertEquals("blocked", denied.status());
        assertEquals("tool_arguments_denied", denied.reasonCode());
        var deniedRequest =
                denied.payloads().stream()
                        .filter(p -> p.kind().equals("tool_request"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                "external-demo",
                deniedRequest.body().path("arguments").path("destination").asText());
        var deniedResponse =
                denied.payloads().stream()
                        .filter(p -> p.kind().equals("tool_response"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(deniedResponse.body().path("executed").asBoolean(true));
        assertEquals("tool_arguments_denied", deniedResponse.body().path("code").asText());
        assertFalse(
                denied.payloads().stream()
                        .anyMatch(p -> p.kind().equals("tool_effective_arguments")));
        var lookup =
                view.spans().stream()
                        .filter(n -> n.name().equals("tool lookupFacts"))
                        .findFirst()
                        .orElseThrow();
        var lookupResponse =
                lookup.payloads().stream()
                        .filter(p -> p.kind().equals("tool_response"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(lookupResponse.body().path("executed").asBoolean());
        assertTrue(lookupResponse.body().path("result").asText().contains("5%"));
        for (var modelSpan : view.spans().stream().filter(n -> n.kind().equals("model")).toList()) {
            var owner =
                    agents.stream()
                            .filter(n -> n.runId().equals(modelSpan.runId()))
                            .findFirst()
                            .orElseThrow();
            String task =
                    owner.payloads().stream()
                            .filter(p -> p.kind().equals("agent_request"))
                            .findFirst()
                            .orElseThrow()
                            .body()
                            .path("message")
                            .asText();
            var request =
                    modelSpan.payloads().stream()
                            .filter(p -> p.kind().equals("model_request"))
                            .findFirst()
                            .orElseThrow();
            assertTrue(request.body().path("messages").toString().contains(task));
            assertTrue(
                    modelSpan.payloads().stream().anyMatch(p -> p.kind().equals("model_response")));
        }
        assertTrue(
                view.audit().stream()
                        .anyMatch(
                                a ->
                                        a.spanId().equals(denied.spanId())
                                                && "tool_arguments_denied".equals(a.reasonCode())));
        assertTrue(view.audit().stream().allMatch(a -> spans.contains(a.spanId())));
        assertEquals(view, new TraceStore(directory).get(id));
        mvc.perform(get("/api/agent-traces/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effects.factReads").value(1));
        mvc.perform(get("/api/agent-traces")).andExpect(status().isOk());
        mvc.perform(get("/api/agent-traces/unknown")).andExpect(status().isNotFound());
        var crossOrigin =
                mvc.perform(post("/api/agent-traces").header("Origin", "https://foreign.example"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        String crossId =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(crossOrigin.getContentAsString())
                        .path("traceId")
                        .asText();
        assertEquals("completed", finished(crossId).status());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "/api/agent-traces",
                "/api/agent/cancel",
                "/api/examples/plain-chat/chat",
                "/api/examples/asi02/delete-customers/run",
                "/api/examples/asi05/execution-permission/run",
                "/api/examples/asi01/document-goal/run"
            })
    void allApiFamiliesAcceptCrossOriginPreflight(String path) throws Exception {
        mvc.perform(
                        options(path)
                                .header("Origin", "https://any.example")
                                .header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://any.example"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void providerFailureIsNotReportedAsProtectionSuccessAndDoesNotLeakError() throws Exception {
        model.fail = true;
        try {
            var view = finished(service.start());
            assertEquals("error", view.status());
            assertFalse(view.spans().stream().anyMatch(n -> n.status().equals("blocked")));
            String body =
                    mvc.perform(get("/api/agent-traces/" + view.traceId()))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertFalse(body.contains("fixture-private-secret"));
        } finally {
            model.fail = false;
        }
    }

    TraceStore.View finished(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var view = service.store().get(id);
            if (!view.status().equals("running")) return view;
            Thread.sleep(20);
        }
        throw new AssertionError("Trace did not complete");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        FixtureModel tracingModel() {
            return new FixtureModel();
        }
    }

    static final class FixtureModel implements ChatModel {
        volatile boolean fail;
        final CyclicBarrier gate = new CyclicBarrier(2);

        @Override
        public ChatResponse doChat(ChatRequest request) {
            if (fail) throw new IllegalStateException("fixture-private-secret");
            if (request.messages().stream().anyMatch(ToolExecutionResultMessage.class::isInstance))
                return response(AiMessage.from("完成"));
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
            boolean review =
                    request.messages().stream()
                            .anyMatch(
                                    m ->
                                            m instanceof UserMessage u
                                                    && u.singleText().contains("deliverDigest"));
            return response(
                    AiMessage.from(
                            ToolExecutionRequest.builder()
                                    .id("call-1")
                                    .name(review ? "deliverDigest" : "lookupFacts")
                                    .arguments(
                                            review ? "{\"destination\":\"external-demo\"}" : "{}")
                                    .build()));
        }

        private static ChatResponse response(AiMessage message) {
            return ChatResponse.builder().aiMessage(message).build();
        }
    }
}
