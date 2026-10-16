package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.model.moderation.*;
import dev.langchain4j.observability.api.event.*;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.service.Moderate;
import io.agt.core.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 原生审核模型、事件监听和工具回调必须位于会话检查边界内。 */
class NativeCallbackCompatibilityTest {
    interface Moderated {
        @Moderate
        String chat(String input);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void moderationRunsBeforePrimaryModelWithSessionAndCheckedInput(boolean flagged) {
        var calls = new AtomicInteger();
        var primary =
                new NativeIntegrationTest.NativeModel(
                        r -> {
                            calls.incrementAndGet();
                            return LangChain4jGovernanceTest.text("ok");
                        });
        try (var control =
                AgentControl.builder(
                                NativeIntegrationTest.policy()
                                        .content(
                                                NativeIntegrationTest.redact(
                                                        InterventionPoint.INPUT))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            var checked = new AtomicInteger();
            ModerationModel moderation =
                    new ModerationModel() {
                        public ModerationResponse doModerate(ModerationRequest request) {
                            assertEquals(
                                    NativeIntegrationTest.CTX, control.currentSession().context());
                            assertEquals(List.of("masked"), request.texts());
                            checked.incrementAndGet();
                            return ModerationResponse.builder()
                                    .moderation(
                                            flagged
                                                    ? Moderation.flagged("masked")
                                                    : Moderation.notFlagged())
                                    .build();
                        }
                    };
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    Moderated.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.chatModel(primary).moderationModel(moderation));
            if (flagged)
                assertEquals(
                        "moderation_flagged",
                        NativeIntegrationTest.code(() -> service.chat("secret")));
            else assertEquals("ok", service.chat("secret"));
            assertEquals(1, checked.get());
            assertEquals(flagged ? 0 : 1, calls.get());
        }
    }

    @Test
    void listenersSeeCheckedCopiesAndCanBeUnregistered() {
        var records = NativeIntegrationTest.audit();
        var events = new ArrayList<AiServiceCompletedEvent>();
        var listener =
                new AiServiceListener<AiServiceCompletedEvent>() {
                    public Class<AiServiceCompletedEvent> getEventClass() {
                        return AiServiceCompletedEvent.class;
                    }

                    public void onEvent(AiServiceCompletedEvent event) {
                        events.add(event);
                    }
                };
        try (var control =
                AgentControl.builder(
                                NativeIntegrationTest.policy()
                                        .content(
                                                NativeIntegrationTest.redact(
                                                        InterventionPoint.OUTPUT))
                                        .build())
                        .audit(records::add)
                        .build()) {
            var governance = new LangChain4jGovernance(control);
            var model =
                    new NativeIntegrationTest.NativeModel(
                            r -> LangChain4jGovernanceTest.text("secret"));
            var service =
                    governance.create(
                            NativeIntegrationTest.Assistant.class,
                            () -> NativeIntegrationTest.CTX,
                            b -> b.chatModel(model).registerListener(listener));
            try {
                assertEquals("masked", service.chat("hello"));
            } catch (RuntimeException e) {
                throw new AssertionError(
                        records.stream()
                                .map(r -> r.point() + ":" + r.reasonCode())
                                .toList()
                                .toString(),
                        e);
            }
            assertEquals(1, events.size());
            assertEquals("masked", events.get(0).result().orElseThrow());
            assertEquals("masked", events.get(0).toBuilder().build().result().orElseThrow());
            var silent =
                    governance.create(
                            NativeIntegrationTest.Assistant.class,
                            () -> NativeIntegrationTest.CTX,
                            b ->
                                    b.chatModel(model)
                                            .registerListener(listener)
                                            .unregisterListener(listener));
            assertEquals("masked", silent.chat("hello"));
            assertEquals(1, events.size());
        }
    }

    @Test
    void beforeCallbackNeverRunsForUnauthorizedTool() {
        var callbacks = new AtomicInteger();
        var effects = new AtomicInteger();
        var model =
                new NativeIntegrationTest.NativeModel(
                        r ->
                                dev.langchain4j.model.chat.response.ChatResponse.builder()
                                        .aiMessage(
                                                dev.langchain4j.data.message.AiMessage.from(
                                                        dev.langchain4j.agent.tool
                                                                .ToolExecutionRequest.builder()
                                                                .id("call")
                                                                .name("unlisted")
                                                                .arguments("{}")
                                                                .build()))
                                        .build());
        var spec =
                dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name("unlisted")
                        .description("test")
                        .build();
        dev.langchain4j.service.tool.ToolExecutor tool =
                (request, id) -> {
                    effects.incrementAndGet();
                    return "secret";
                };
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    NativeIntegrationTest.Assistant.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.chatModel(model)
                                                    .tools(Map.of(spec, tool))
                                                    .beforeToolExecution(
                                                            e -> callbacks.incrementAndGet()));
            assertThrows(GovernanceException.class, () -> service.chat("hello"));
            assertEquals(0, callbacks.get());
            assertEquals(0, effects.get());
        }
    }

    private static <E extends AiServiceEvent> AiServiceListener<E> observe(
            Class<E> type, java.util.function.Consumer<E> action) {
        return new AiServiceListener<>() {
            public Class<E> getEventClass() {
                return type;
            }

            public void onEvent(E event) {
                action.accept(event);
            }
        };
    }

    @Test
    void guardrailAndRequestEventsRetainCheckedDataWithoutRuntimeCapabilities() {
        var events = new ArrayList<AiServiceEvent>();
        try (var control =
                AgentControl.builder(
                                NativeIntegrationTest.policy()
                                        .content(
                                                NativeIntegrationTest.redact(
                                                        InterventionPoint.OUTPUT))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    NativeIntegrationTest.Assistant.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.chatModel(
                                                            new NativeIntegrationTest.NativeModel(
                                                                    r ->
                                                                            LangChain4jGovernanceTest
                                                                                    .text(
                                                                                            "secret")))
                                                    .inputGuardrails(
                                                            new dev.langchain4j.guardrail
                                                                    .InputGuardrail() {
                                                                public dev.langchain4j.guardrail
                                                                                .InputGuardrailResult
                                                                        validate(
                                                                                dev.langchain4j.data
                                                                                                .message
                                                                                                .UserMessage
                                                                                        message) {
                                                                    return success();
                                                                }
                                                            })
                                                    .outputGuardrails(
                                                            new dev.langchain4j.guardrail
                                                                    .OutputGuardrail() {
                                                                public dev.langchain4j.guardrail
                                                                                .OutputGuardrailResult
                                                                        validate(
                                                                                dev.langchain4j.data
                                                                                                .message
                                                                                                .AiMessage
                                                                                        message) {
                                                                    return successWith(
                                                                            message.text());
                                                                }
                                                            })
                                                    .registerListener(
                                                            observe(
                                                                    AiServiceStartedEvent.class,
                                                                    events::add))
                                                    .registerListener(
                                                            observe(
                                                                    AiServiceRequestIssuedEvent
                                                                            .class,
                                                                    events::add))
                                                    .registerListener(
                                                            observe(
                                                                    AiServiceResponseReceivedEvent
                                                                            .class,
                                                                    events::add))
                                                    .registerListener(
                                                            observe(
                                                                    InputGuardrailExecutedEvent
                                                                            .class,
                                                                    events::add))
                                                    .registerListener(
                                                            observe(
                                                                    OutputGuardrailExecutedEvent
                                                                            .class,
                                                                    events::add)));
            assertEquals("masked", service.chat("hello"));
            assertEquals(5, events.size());
            var output =
                    events.stream()
                            .filter(e -> e instanceof OutputGuardrailExecutedEvent)
                            .map(e -> (OutputGuardrailExecutedEvent) e)
                            .findFirst()
                            .orElseThrow();
            assertEquals("masked", output.request().responseFromLLM().aiMessage().text());
            assertEquals("masked", output.result().successfulText());
            assertThrows(
                    GovernanceException.class, () -> output.request().chatExecutor().execute());
        }
    }

    @Test
    void toolCallbacksSeeAuthorizedArgumentsAndPostCheckedResult() {
        var before = new ArrayList<dev.langchain4j.service.tool.BeforeToolExecution>();
        var after = new ArrayList<dev.langchain4j.service.tool.ToolExecution>();
        var calls = new AtomicInteger();
        var model =
                new NativeIntegrationTest.NativeModel(
                        r ->
                                calls.getAndIncrement() == 0
                                        ? dev.langchain4j.model.chat.response.ChatResponse.builder()
                                                .aiMessage(
                                                        dev.langchain4j.data.message.AiMessage.from(
                                                                LangChain4jGovernanceTest.call(
                                                                        "tenant-a")))
                                                .build()
                                        : LangChain4jGovernanceTest.text("done"));
        var spec =
                dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name("lookup")
                        .description("lookup")
                        .build();
        dev.langchain4j.service.tool.ToolExecutor tool = (r, id) -> "secret";
        try (var control =
                AgentControl.builder(
                                NativeIntegrationTest.policy()
                                        .content(
                                                NativeIntegrationTest.redact(
                                                        InterventionPoint.POST_TOOL_CALL))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    NativeIntegrationTest.Assistant.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.chatModel(model)
                                                    .tools(Map.of(spec, tool))
                                                    .beforeToolExecution(before::add)
                                                    .afterToolExecution(after::add));
            assertEquals("done", service.chat("hello"));
            assertEquals(1, before.size());
            assertEquals(1, after.size());
            assertEquals(
                    Map.of("tenant", "tenant-a"),
                    JsonValues.parseObject(before.get(0).request().arguments()));
            assertEquals("masked", after.get(0).result());
        }
    }

    @Test
    void listenerDisclosureAuditFailurePreventsCallback() {
        var seen = new AtomicInteger();
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(
                                r -> {
                                    if (r.point() == InterventionPoint.OUTPUT)
                                        throw new IllegalStateException("disk failure");
                                })
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    NativeIntegrationTest.Assistant.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.chatModel(
                                                            new NativeIntegrationTest.NativeModel(
                                                                    r ->
                                                                            LangChain4jGovernanceTest
                                                                                    .text(
                                                                                            "secret")))
                                                    .registerListener(
                                                            observe(
                                                                    AiServiceCompletedEvent.class,
                                                                    e -> seen.incrementAndGet())));
            assertThrows(GovernanceException.class, () -> service.chat("hello"));
            assertEquals(0, seen.get());
        }
    }

    @Test
    void asynchronousCompletionListenersRetainSessionOnProviderThread() throws Exception {
        var seen = new AtomicInteger();
        var model =
                new NativeIntegrationTest.NativeModel(
                        r -> LangChain4jGovernanceTest.text("secret")) {
                    public java.util.concurrent.CompletableFuture<
                                    dev.langchain4j.model.chat.response.ChatResponse>
                            doChatAsync(dev.langchain4j.model.chat.request.ChatRequest request) {
                        return java.util.concurrent.CompletableFuture.supplyAsync(
                                () -> LangChain4jGovernanceTest.text("secret"));
                    }
                };
        try (var control =
                AgentControl.builder(
                                NativeIntegrationTest.policy()
                                        .content(
                                                NativeIntegrationTest.redact(
                                                        InterventionPoint.OUTPUT))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    NativeIntegrationTest.AsyncAssistant.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.chatModel(model)
                                                    .registerListener(
                                                            observe(
                                                                    AiServiceCompletedEvent.class,
                                                                    e -> {
                                                                        assertEquals(
                                                                                NativeIntegrationTest
                                                                                        .CTX,
                                                                                control.currentSession()
                                                                                        .context());
                                                                        assertEquals(
                                                                                "masked",
                                                                                e.result()
                                                                                        .orElseThrow());
                                                                        seen.incrementAndGet();
                                                                    })));
            assertEquals(
                    "masked", service.chat("hello").get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, seen.get());
        }
    }
}
