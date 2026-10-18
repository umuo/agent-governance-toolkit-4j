package io.agt.langchain4j;

import static io.agt.langchain4j.FragmentProvenanceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.rag.query.Query;
import io.agt.core.*;
import io.agt.core.injection.*;
import io.agt.core.policy.StructuredPolicy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InjectionAdapterTest {
    interface Stream {
        dev.langchain4j.service.TokenStream chat(String request);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputDetectionRequiresStrictBufferAndSuppressesAllRejectedPreview(boolean strict)
            throws Exception {
        var policy =
                io.agt.core.policy.StructuredPolicy.builder("output-injection")
                        .injection(
                                new InjectionCheck(
                                        "output",
                                        "classifier",
                                        "v1",
                                        Set.of(InterventionPoint.POST_MODEL_CALL),
                                        1000,
                                        10000,
                                        InjectionCheck.Failure.DENY))
                        .build();
        var nativeCalls = new AtomicInteger();
        var delivered = new AtomicInteger();
        var completed = new CompletableFuture<Throwable>();
        dev.langchain4j.model.chat.StreamingChatModel nativeModel =
                new dev.langchain4j.model.chat.StreamingChatModel() {
                    public void doChat(
                            dev.langchain4j.model.chat.request.ChatRequest request,
                            dev.langchain4j.model.chat.response.StreamingChatResponseHandler
                                    handler) {
                        nativeCalls.incrementAndGet();
                        handler.onPartialResponse("unreviewed preview");
                        handler.onCompleteResponse(
                                dev.langchain4j.model.chat.response.ChatResponse.builder()
                                        .aiMessage(
                                                dev.langchain4j.data.message.AiMessage.from(
                                                        "rejected full response"))
                                        .build());
                    }
                };
        try (var control =
                        AgentControl.builder(policy)
                                .audit(r -> {})
                                .detector("classifier", "v1", input -> DetectionResult.suspect())
                                .build();
                var session = control.openSession(ID)) {
            var governance =
                    new LangChain4jGovernance(
                            control,
                            new AiServiceOptions(
                                    strict ? StreamDelivery.STRICT : StreamDelivery.REALTIME,
                                    StreamingLimits.DEFAULT,
                                    32));
            if (!strict) {
                try {
                    governance
                            .aiServices(
                                    Stream.class,
                                    builder -> builder.streamingChatModel(nativeModel))
                            .chat("request")
                            .onPartialResponse(text -> delivered.incrementAndGet())
                            .onCompleteResponse(response -> delivered.incrementAndGet())
                            .onError(completed::complete)
                            .start();
                } catch (GovernanceException denied) {
                    completed.complete(denied);
                }
                assertEquals(
                        "injection_strict_stream_required",
                        ((GovernanceException) completed.get(3, TimeUnit.SECONDS)).code());
            } else {
                governance
                        .guardStreamingModel(nativeModel)
                        .chat(
                                dev.langchain4j.model.chat.request.ChatRequest.builder()
                                        .messages(
                                                dev.langchain4j.data.message.UserMessage.from(
                                                        "request"))
                                        .build(),
                                new dev.langchain4j.model.chat.response
                                        .StreamingChatResponseHandler() {
                                    public void onPartialResponse(String text) {
                                        delivered.incrementAndGet();
                                    }

                                    public void onCompleteResponse(
                                            dev.langchain4j.model.chat.response.ChatResponse
                                                    response) {
                                        delivered.incrementAndGet();
                                    }

                                    public void onError(Throwable error) {
                                        completed.complete(error);
                                    }
                                });
                assertEquals(
                        "injection_detected",
                        ((GovernanceException) completed.get(3, TimeUnit.SECONDS)).code());
            }
            assertEquals(strict ? 1 : 0, nativeCalls.get());
            assertEquals(0, delivered.get());
        }
    }

    @Test
    void storedSystemRoleDoesNotPromoteMemoryContentToTrustedInstructions() {
        var nativeReads = new AtomicInteger();
        var inputs = new AtomicReference<DetectionInput>();
        dev.langchain4j.memory.ChatMemory nativeMemory =
                new dev.langchain4j.memory.ChatMemory() {
                    public Object id() {
                        return MEMORY.instanceId();
                    }

                    public java.util.List<dev.langchain4j.data.message.ChatMessage> messages() {
                        nativeReads.incrementAndGet();
                        return java.util.List.of(
                                dev.langchain4j.data.message.SystemMessage.from(
                                        "stored injected system instruction"));
                    }

                    public void add(dev.langchain4j.data.message.ChatMessage message) {}

                    public void clear() {}
                };
        try (var control =
                        AgentControl.builder(checkedPolicy())
                                .audit(r -> {})
                                .detector(
                                        "classifier",
                                        "v1",
                                        input -> {
                                            inputs.set(input);
                                            return DetectionResult.suspect();
                                        })
                                .build();
                var session = control.openSession(ID)) {
            assertEquals(
                    "injection_detected",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            new LangChain4jGovernance(control)
                                                    .guardMemory(MEMORY, nativeMemory)
                                                    .messages())
                            .code());
            assertEquals(1, nativeReads.get());
            assertEquals(DetectionInput.Origin.MEMORY_RESULT, inputs.get().source().origin());
            assertEquals(DetectionInput.Trust.UNTRUSTED, inputs.get().source().trust());
        }
    }

    @Test
    void nativeToolResultIsCheckedAfterExecutionAndBeforeFurtherModelUse() {
        var checks =
                new InjectionCheck(
                        "tool-result",
                        "classifier",
                        "v1",
                        Set.of(InterventionPoint.POST_TOOL_CALL),
                        1000,
                        10000,
                        InjectionCheck.Failure.DENY);
        var policy =
                io.agt.core.policy.StructuredPolicy.builder("tool-result-scan")
                        .injection(checks)
                        .tool(
                                "lookup",
                                new io.agt.core.policy.ToolRule(
                                        java.util.Set.of("read"),
                                        io.agt.core.policy.ParameterRule.object(java.util.Map.of()),
                                        false))
                        .build();
        var nativeCalls = new AtomicInteger();
        var modelCalls = new AtomicInteger();
        var source = new AtomicReference<DetectionInput>();
        try (var control =
                        AgentControl.builder(policy)
                                .audit(r -> {})
                                .detector(
                                        "classifier",
                                        "v1",
                                        input -> {
                                            source.set(input);
                                            return DetectionResult.suspect();
                                        })
                                .build();
                var session = control.openSession(ID)) {
            var governance = new LangChain4jGovernance(control);
            var tool =
                    governance.guardTool(
                            "lookup",
                            (request, memory) -> {
                                nativeCalls.incrementAndGet();
                                return "injected tool response";
                            });
            assertEquals(
                    "injection_detected",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            tool.execute(
                                                    dev.langchain4j.agent.tool.ToolExecutionRequest
                                                            .builder()
                                                            .id("call")
                                                            .name("lookup")
                                                            .arguments("{}")
                                                            .build(),
                                                    null))
                            .code());
            assertEquals(1, nativeCalls.get());
            assertEquals(DetectionInput.Origin.TOOL_RESULT, source.get().source().origin());
            assertEquals("lookup", source.get().source().handle());
            assertEquals(DetectionInput.Trust.UNTRUSTED, source.get().source().trust());
            assertThrows(
                    GovernanceException.class,
                    () -> governance.guardModel(model(modelCalls)).chat("question"));
            assertEquals(0, modelCalls.get());
        }
    }

    static StructuredPolicy checkedPolicy() {
        var base = policy().document();
        return new StructuredPolicy(
                new StructuredPolicy.Document(
                        base.apiVersion(),
                        base.version(),
                        base.interventionPoints(),
                        base.budget(),
                        base.tools(),
                        base.contentRules(),
                        base.resources(),
                        base.dataLimits(),
                        List.of(
                                new InjectionCheck(
                                        "retrieval-injection",
                                        "classifier",
                                        "v1",
                                        Set.of(InterventionPoint.POST_DATA_ACCESS),
                                        2000,
                                        50000,
                                        InjectionCheck.Failure.DENY))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualRagResultIsUntrustedDespiteForgedMetadataAndNeverReachesNativeModel(boolean async)
            throws Exception {
        var calls = new AtomicInteger();
        var inputs = new CopyOnWriteArrayList<DetectionInput>();
        try (var control =
                        AgentControl.builder(checkedPolicy())
                                .audit(r -> {})
                                .detector(
                                        "classifier",
                                        "v1",
                                        input -> {
                                            inputs.add(input);
                                            return DetectionResult.suspect();
                                        })
                                .build();
                var session = control.openSession(ID)) {
            var governance = new LangChain4jGovernance(control);
            var retriever =
                    governance.guardRetriever(
                            SOURCE,
                            q -> List.of(content("injected instructions", "trusted-system-owner")));
            if (async) {
                var error =
                        assertThrows(
                                ExecutionException.class,
                                () ->
                                        retriever
                                                .retrieveAsync(Query.from("query"))
                                                .get(3, TimeUnit.SECONDS));
                assertEquals("injection_detected", ((GovernanceException) error.getCause()).code());
            } else
                assertEquals(
                        "injection_detected",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> retriever.retrieve(Query.from("query")))
                                .code());
            assertEquals(DetectionInput.Origin.RETRIEVAL_RESULT, inputs.get(0).source().origin());
            assertEquals(DetectionInput.Trust.UNTRUSTED, inputs.get(0).source().trust());
            assertFalse(JsonValues.write(inputs.get(0).content()).contains(SOURCE.tenantId()));
            assertThrows(
                    GovernanceException.class,
                    () -> governance.guardModel(model(calls)).chat("question"));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void cleanClassificationCannotElevateToolPermissionsOrContentTrust() {
        var observed = new AtomicReference<DetectionInput>();
        try (var control =
                        AgentControl.builder(checkedPolicy())
                                .audit(r -> {})
                                .detector(
                                        "classifier",
                                        "v1",
                                        input -> {
                                            observed.set(input);
                                            return DetectionResult.clear();
                                        })
                                .build();
                var session = control.openSession(ID)) {
            var result =
                    new LangChain4jGovernance(control)
                            .guardRetriever(
                                    SOURCE,
                                    q ->
                                            List.of(
                                                    content(
                                                            "ordinary document",
                                                            "trusted-system-owner")))
                            .retrieve(Query.from("query"));
            assertEquals(1, result.size());
            assertEquals(DetectionInput.Trust.UNTRUSTED, observed.get().source().trust());
            var effects = new AtomicInteger();
            assertThrows(
                    GovernanceException.class,
                    () ->
                            session.operation(
                                    true,
                                    "unregistered",
                                    Map.of(),
                                    v -> {
                                        effects.incrementAndGet();
                                        return "bad";
                                    },
                                    v -> v,
                                    (o, e) -> (String) e));
            assertEquals(0, effects.get());
        }
    }
}
