package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.TokenUsage;
import io.agt.core.*;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.usage.*;
import io.agt.langchain4j.internal.streaming.StreamingAdapters;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ModelUsageAdapterTest {
    private static final TrustedContext IDENTITY =
            new TrustedContext("agent", "tenant", "user", Set.of());
    private static final ModelUsageMeter METER = ModelUsageMeter.standardParameters(request -> 100);

    private TaskLimits limits(long tokens) {
        return new TaskLimits(
                new BudgetLimits(20, 20, 60000),
                20,
                10,
                3,
                null,
                new UsageLimits(
                        tokens,
                        BigDecimal.ONE,
                        "USD",
                        Map.of(
                                "model",
                                new UsageLimits.ModelRate(
                                        100, 50, BigDecimal.ONE, new BigDecimal("2"), Set.of()))));
    }

    private AgentControl runtime() {
        return AgentControl.builder(StructuredPolicy.builder("usage-model").build())
                .audit(record -> {})
                .build();
    }

    private ChatResponse response(TokenUsage usage) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .metadata(
                        ChatResponseMetadata.builder().modelName("model").tokenUsage(usage).build())
                .build();
    }

    private static class Model implements ChatModel {
        final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        final ChatResponse response;

        Model(ChatResponse response) {
            this.response = response;
        }

        public ChatRequestParameters defaultRequestParameters() {
            return ChatRequestParameters.builder().modelName("model").build();
        }

        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return response;
        }

        public CompletableFuture<ChatResponse> chatAsync(
                ChatRequest request, ChatRequestOptions options) {
            return CompletableFuture.completedFuture(doChat(request));
        }
    }

    private LangChain4jGovernance adapter(AgentControl control, ModelUsageMeter meter) {
        return new LangChain4jGovernance(
                control,
                new AiServiceOptions(StreamDelivery.REALTIME, StreamingLimits.DEFAULT, 32, meter));
    }

    private ChatResponse invoke(ChatModel model, boolean async) throws Exception {
        var request = ChatRequest.builder().messages(UserMessage.from("request")).build();
        return async ? model.chatAsync(request).get(5, TimeUnit.SECONDS) : model.chat(request);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realAdapterCapsOutputAndSettlesBeforeAuthorizingAnotherCall(boolean async)
            throws Exception {
        var model = new Model(response(new TokenUsage(40, 10, 50)));
        try (var control = runtime();
                var session = control.openTaskSession(IDENTITY, limits(200));
                var binding = session.bind()) {
            var guarded = adapter(control, METER).guardModel(model);
            assertEquals("ok", invoke(guarded, async).aiMessage().text());
            assertEquals("ok", invoke(guarded, async).aiMessage().text());
            Exception error = assertThrows(Exception.class, () -> invoke(guarded, async));
            Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
            assertEquals("task_token_budget_exceeded", ((GovernanceException) cause).code());
            assertEquals(2, model.requests.size());
            assertTrue(
                    model.requests.stream()
                            .allMatch(request -> request.parameters().maxOutputTokens() == 50));
            assertEquals(100, session.usageSnapshot().settledTokens());
            assertEquals(0, session.usageSnapshot().heldTokens());
            assertEquals(
                    0, session.usageSnapshot().settledCost().compareTo(new BigDecimal("0.00012")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingProviderUsageKeepsReservationAndFailsTheRootTask(boolean async) throws Exception {
        var model = new Model(response(null));
        try (var control = runtime();
                var session = control.openTaskSession(IDENTITY, limits(1000));
                var binding = session.bind()) {
            Exception error =
                    assertThrows(
                            Exception.class,
                            () -> invoke(adapter(control, METER).guardModel(model), async));
            Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
            assertEquals("model_usage_unknown", ((GovernanceException) cause).code());
            assertEquals(150, session.usageSnapshot().heldTokens());
            assertEquals(1, session.usageSnapshot().uncertainCalls());
            assertEquals("model_usage_unknown", session.usageSnapshot().fault());
        }
    }

    @Test
    void missingMeterOrChangedRequestNeverReachesProvider() {
        var model = new Model(response(new TokenUsage(40, 10, 50)));
        try (var control = runtime();
                var root = control.openTaskSession(IDENTITY, limits(1000));
                var first = control.openDelegatedSession(root, IDENTITY);
                var second = control.openDelegatedSession(root, IDENTITY)) {
            try (var binding = first.bind()) {
                var error =
                        assertThrows(
                                GovernanceException.class,
                                () -> invoke(adapter(control, null).guardModel(model), false));
                assertEquals("model_usage_meter_unavailable", error.code());
            }
            try (var binding = second.bind()) {
                ModelUsageMeter changed =
                        (request, output) ->
                                new ModelUsageMeter.Bound(
                                        request.toBuilder()
                                                .messages(UserMessage.from("different input"))
                                                .build(),
                                        100);
                var error =
                        assertThrows(
                                GovernanceException.class,
                                () -> invoke(adapter(control, changed).guardModel(model), false));
                assertEquals("model_usage_request_changed", error.code());
            }
            assertTrue(model.requests.isEmpty());
            assertEquals(0, root.usageSnapshot().heldTokens());
        }
    }

    interface Assistant {
        String chat(String input);
    }

    @Test
    void nativeServiceOptionsCarryMeteringWithoutOpeningIndependentBudget() {
        var model = new Model(response(new TokenUsage(40, 10, 50)));
        try (var control = runtime();
                var session = control.openTaskSession(IDENTITY, limits(1000));
                var binding = session.bind()) {
            var assistant =
                    adapter(control, METER)
                            .create(
                                    Assistant.class,
                                    () -> IDENTITY,
                                    builder -> builder.chatModel(model));
            assertEquals("ok", assistant.chat("request"));
            assertEquals(50, session.usageSnapshot().settledTokens());
            assertEquals(1, session.taskSnapshot().activeSessions());
        }
    }

    @ParameterizedTest
    @EnumSource(StreamDelivery.class)
    void streamingUsesTheSameReservationAndSettlesReportedUsage(StreamDelivery delivery)
            throws Exception {
        var calls = new AtomicInteger();
        StreamingChatModel model =
                new StreamingChatModel() {
                    public ChatRequestParameters defaultRequestParameters() {
                        return ChatRequestParameters.builder().modelName("model").build();
                    }

                    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                        calls.incrementAndGet();
                        assertEquals(50, request.parameters().maxOutputTokens());
                        handler.onPartialResponse("ok");
                        handler.onCompleteResponse(response(new TokenUsage(40, 10, 50)));
                    }
                };
        try (var control = runtime();
                var session = control.openTaskSession(IDENTITY, limits(1000));
                var binding = session.bind()) {
            var done = new CompletableFuture<ChatResponse>();
            StreamingAdapters.guard(control, model, StreamingLimits.DEFAULT, delivery, METER)
                    .chat(
                            ChatRequest.builder().messages(UserMessage.from("request")).build(),
                            new StreamingChatResponseHandler() {
                                public void onPartialResponse(String text) {}

                                public void onCompleteResponse(ChatResponse response) {
                                    done.complete(response);
                                }

                                public void onError(Throwable error) {
                                    done.completeExceptionally(error);
                                }
                            });
            assertEquals("ok", done.get(5, TimeUnit.SECONDS).aiMessage().text());
            assertEquals(1, calls.get());
            assertEquals(50, session.usageSnapshot().settledTokens());
            assertEquals(0, session.usageSnapshot().heldTokens());
        }
    }
}
