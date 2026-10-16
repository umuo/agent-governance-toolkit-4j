package io.agt.examples.live.web;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.TrustedContext;
import io.agt.core.approval.ApprovalResponse;
import io.agt.core.audit.AuditRecord;
import io.agt.examples.live.config.AgentPolicy;
import io.agt.langchain4j.AiServiceOptions;
import io.agt.langchain4j.StreamDelivery;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 验证实际 Starter 属性绑定、按轮身份隔离与上下文销毁。 */
class WebAgentRuntimeFactoryTest {
    interface Assistant {
        String chat(String message);
    }

    @Test
    void starterBindsHostOptionsAndClosesOwnedSessionsAndRuntime() {
        var environment =
                new MockEnvironment()
                        .withProperty("agt.langchain4j.delivery", "STRICT")
                        .withProperty("agt.langchain4j.max-characters", "4096")
                        .withProperty("agt.langchain4j.max-events", "100")
                        .withProperty("agt.langchain4j.publisher-prefetch", "4");
        var factory = new WebAgentRuntimeFactory(environment);
        var identity =
                new TrustedContext(
                        "web-agent", "local", "owner", AgentPolicy.permissions("agent", false));
        List<AuditRecord> records = new ArrayList<>();
        var runtime =
                factory.open(
                        AgentPolicy.create("agent"),
                        identity,
                        records::add,
                        request ->
                                java.util.concurrent.CompletableFuture.completedFuture(
                                        ApprovalResponse.allow(request)));
        try (runtime) {
            var options = runtime.context().getBean(AiServiceOptions.class);
            assertEquals(StreamDelivery.STRICT, options.delivery());
            assertEquals(4096, options.streamingLimits().maxCharacters());
            assertEquals(100, options.streamingLimits().maxEvents());
            assertEquals(4, options.publisherPrefetch());
            ChatModel model =
                    new ChatModel() {
                        @Override
                        public ChatResponse doChat(ChatRequest request) {
                            assertEquals(identity, runtime.control().currentSession().context());
                            return ChatResponse.builder()
                                    .aiMessage(AiMessage.from("checked"))
                                    .build();
                        }
                    };
            var assistant =
                    runtime.services().create(Assistant.class, builder -> builder.chatModel(model));
            assertEquals("checked", assistant.chat("hello"));
            assertEquals(
                    "missing_session",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> runtime.control().currentSession())
                            .code());
            assertEquals(
                    1, records.stream().filter(r -> r.point() == InterventionPoint.INPUT).count());
            assertEquals(
                    1,
                    records.stream()
                            .filter(
                                    r ->
                                            r.point() == InterventionPoint.OUTPUT
                                                    && !r.verdict().equals("EXECUTION"))
                            .count());
            assertEquals(
                    1,
                    records.stream()
                            .filter(r -> r.point() == InterventionPoint.AGENT_SHUTDOWN)
                            .count());
        }
        assertFalse(runtime.context().isActive());
        assertThrows(GovernanceException.class, () -> runtime.control().openSession(identity));
    }

    @Test
    void runsDoNotShareRuntimeOrTrustedPermissions() {
        var factory = new WebAgentRuntimeFactory(new MockEnvironment());
        var reader =
                new TrustedContext(
                        "web-agent",
                        "local",
                        "reader",
                        AgentPolicy.permissions("read-only", false));
        var writer =
                new TrustedContext(
                        "web-agent", "local", "writer", AgentPolicy.permissions("agent", false));
        try (var first = factory.open(AgentPolicy.create("read-only"), reader, record -> {}, null);
                var second =
                        factory.open(AgentPolicy.create("agent"), writer, record -> {}, null)) {
            assertNotSame(first.control(), second.control());
            try (var session = first.control().openSession(first.identity())) {
                assertEquals(
                        "permission_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                first.control()
                                                        .runTool(
                                                                "writeFile",
                                                                java.util.Map.of(
                                                                        "path", "a", "content",
                                                                        "b"),
                                                                args -> "effect"))
                                .code());
            }
            try (var session = second.control().openSession(second.identity())) {
                assertEquals(
                        "effect",
                        second.control()
                                .runTool(
                                        "writeFile",
                                        java.util.Map.of("path", "a", "content", "b"),
                                        args -> "effect"));
            }
        }
    }
}
