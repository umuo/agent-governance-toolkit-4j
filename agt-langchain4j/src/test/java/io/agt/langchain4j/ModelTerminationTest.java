package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import io.agt.core.*;
import io.agt.core.execution.*;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.usage.UsageLimits;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelTerminationTest {
    static final TrustedContext ID = new TrustedContext("agent", "tenant", "user", Set.of());

    static AgentControl control() {
        return AgentControl.builder(StructuredPolicy.builder("model-stop").build())
                .audit(record -> {})
                .build();
    }

    static TaskLimits limits() {
        return new TaskLimits(new BudgetLimits(10, 10, 60000), 10, 5, 3);
    }

    static ChatRequest request() {
        return ChatRequest.builder()
                .messages(UserMessage.from("synthetic request"))
                .parameters(ChatRequestParameters.builder().modelName("fixture").build())
                .build();
    }

    static ChatResponse response() {
        return ChatResponse.builder().aiMessage(AiMessage.from("synthetic response")).build();
    }

    static void await(BooleanSupplier check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(check.getAsBoolean(), "Expected model execution lifecycle");
    }

    static LangChain4jGovernance governance(
            AgentControl control, ModelTerminationContract termination) {
        return new LangChain4jGovernance(
                control,
                new AiServiceOptions(
                        StreamDelivery.REALTIME, StreamingLimits.DEFAULT, 32, null, termination));
    }

    static final class PendingModel implements ChatModel {
        final CountDownLatch entered = new CountDownLatch(1);
        final CompletableFuture<ChatResponse> nativeCall = new CompletableFuture<>();

        public CompletableFuture<ChatResponse> chatAsync(
                ChatRequest request, ChatRequestOptions options) {
            entered.countDown();
            return nativeCall;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulNativeResponseConfirmsOneModelRequest(boolean async) throws Exception {
        try (var control = control();
                var task = control.openTaskSession(ID, limits());
                var binding = task.bind()) {
            ChatModel nativeModel =
                    new ChatModel() {
                        public ChatResponse chat(ChatRequest req, ChatRequestOptions options) {
                            return response();
                        }

                        public CompletableFuture<ChatResponse> chatAsync(
                                ChatRequest req, ChatRequestOptions options) {
                            return CompletableFuture.completedFuture(response());
                        }
                    };
            var model = new LangChain4jGovernance(control).guardModel(nativeModel);
            if (async) model.chatAsync(request()).get(3, TimeUnit.SECONDS);
            else model.chat(request());
            var snapshot = task.externalExecutionSnapshot();
            assertEquals(1, snapshot.entries().size());
            assertEquals("MODEL_REQUEST", snapshot.entries().get(0).kind());
            assertTrue(snapshot.allTerminationConfirmed());
            assertEquals(0, snapshot.active());
        }
    }

    @Test
    void cancelledFutureWithoutProviderProofFreezesSharedRootEvenWithoutUsageLimits()
            throws Exception {
        try (var control = control();
                var root = control.openTaskSession(ID, limits());
                var child = control.openDelegatedSession(root, ID);
                var binding = child.bind()) {
            var nativeModel = new PendingModel();
            var future =
                    new LangChain4jGovernance(control).guardModel(nativeModel).chatAsync(request());
            assertTrue(nativeModel.entered.await(2, TimeUnit.SECONDS));
            child.revoke();
            assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(nativeModel.nativeCall.isCancelled());
            assertFalse(root.externalExecutionSnapshot().allTerminationConfirmed());
            await(() -> root.externalExecutionSnapshot().unknown() == 1);
            assertEquals(1, root.externalExecutionSnapshot().active());
            assertThrows(GovernanceException.class, root::checkActive);
        }
    }

    @Test
    void independentProviderAcknowledgementCanConfirmAfterDeliveryCancellation() throws Exception {
        var acknowledgement = new CompletableFuture<TerminationEvidence>();
        var called = new CountDownLatch(1);
        ModelTerminationContract contract =
                actual ->
                        new ModelTerminationContract.Call() {
                            public CompletionStage<TerminationEvidence> requestCancellation() {
                                called.countDown();
                                return acknowledgement;
                            }
                        };
        try (var control = control();
                var root = control.openTaskSession(ID, limits());
                var child = control.openDelegatedSession(root, ID);
                var binding = child.bind()) {
            var nativeModel = new PendingModel();
            var future = governance(control, contract).guardModel(nativeModel).chatAsync(request());
            assertTrue(nativeModel.entered.await(2, TimeUnit.SECONDS));
            child.revoke();
            assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(called.await(2, TimeUnit.SECONDS));
            assertEquals(1, root.externalExecutionSnapshot().pendingCancellation());
            acknowledgement.complete(TerminationEvidence.CONFIRMED);
            await(() -> root.externalExecutionSnapshot().allTerminationConfirmed());
            assertEquals(
                    ExternalExecutionRegistry.State.CANCEL_CONFIRMED,
                    root.externalExecutionSnapshot().entries().get(0).state());
            root.checkActive();
        }
    }

    @Test
    void streamKeepsLateTerminalEvidenceWhileSuppressingCancelledResponse() throws Exception {
        var handler = new AtomicReference<StreamingChatResponseHandler>();
        var cancelled = new AtomicInteger();
        var delivered = new AtomicInteger();
        StreamingChatModel nativeModel =
                new StreamingChatModel() {
                    public void doChat(ChatRequest req, StreamingChatResponseHandler events) {
                        events.onPartialResponse(
                                new PartialResponse("preview"),
                                new PartialResponseContext(
                                        new StreamingHandle() {
                                            public void cancel() {
                                                cancelled.incrementAndGet();
                                            }

                                            public boolean isCancelled() {
                                                return cancelled.get() > 0;
                                            }
                                        }));
                        handler.set(events);
                    }
                };
        try (var control = control();
                var root = control.openTaskSession(ID, limits());
                var child = control.openDelegatedSession(root, ID);
                var binding = child.bind()) {
            var failed = new CompletableFuture<Throwable>();
            new LangChain4jGovernance(control)
                    .guardStreamingModel(nativeModel)
                    .chat(
                            request(),
                            new StreamingChatResponseHandler() {
                                public void onCompleteResponse(ChatResponse response) {
                                    delivered.incrementAndGet();
                                }

                                public void onError(Throwable error) {
                                    failed.complete(error);
                                }
                            });
            await(() -> handler.get() != null);
            child.revoke();
            failed.get(2, TimeUnit.SECONDS);
            assertFalse(root.externalExecutionSnapshot().allTerminationConfirmed());
            handler.get().onCompleteResponse(response());
            await(() -> root.externalExecutionSnapshot().allTerminationConfirmed());
            assertEquals(0, delivered.get());
            assertTrue(cancelled.get() > 0);
            assertEquals(
                    1,
                    root.externalExecutionSnapshot().entries().size(),
                    "Collector and model wrapper must share one registration");
            root.checkActive();
        }
    }

    @Test
    void unsafeResponseClassificationCannotRaceAheadOfAsyncDelivery() throws Exception {
        ModelTerminationContract contract =
                actual ->
                        new ModelTerminationContract.Call() {
                            public CompletionStage<TerminationEvidence> requestCancellation() {
                                return new CompletableFuture<>();
                            }

                            public TerminationEvidence completed(ChatResponse response) {
                                return TerminationEvidence.UNKNOWN;
                            }
                        };
        try (var control = control();
                var root = control.openTaskSession(ID, limits());
                var binding = root.bind()) {
            ChatModel nativeModel =
                    new ChatModel() {
                        public CompletableFuture<ChatResponse> chatAsync(
                                ChatRequest req, ChatRequestOptions options) {
                            return CompletableFuture.completedFuture(response());
                        }
                    };
            var future = governance(control, contract).guardModel(nativeModel).chatAsync(request());
            assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertEquals(
                    "external_execution_unconfirmed", root.externalExecutionSnapshot().fault());
        }
    }

    @Test
    void externalAdmissionFailureDoesNotDispatchUsageOrReachProvider() {
        var usage =
                new UsageLimits(
                        200,
                        BigDecimal.ONE,
                        "USD",
                        Map.of(
                                "fixture",
                                new UsageLimits.ModelRate(
                                        100, 50, BigDecimal.ONE, BigDecimal.ONE, Set.of())));
        var base = limits();
        var configured =
                new TaskLimits(
                        base.calls(),
                        base.maxDataCalls(),
                        base.maxConcurrentSessions(),
                        base.maxDelegationDepth(),
                        null,
                        usage);
        try (var control = control();
                var root = control.openTaskSession(ID, configured);
                var binding = root.bind()) {
            for (int i = 0; i < 128; i++)
                root.registerExternalExecution("PREPARED_JOB", CompletableFuture::new);
            var nativeCalls = new AtomicInteger();
            ChatModel nativeModel =
                    new ChatModel() {
                        public ChatResponse chat(ChatRequest req, ChatRequestOptions options) {
                            nativeCalls.incrementAndGet();
                            return response();
                        }
                    };
            var options =
                    new AiServiceOptions(
                            StreamDelivery.REALTIME,
                            StreamingLimits.DEFAULT,
                            32,
                            ModelUsageMeter.standardParameters(req -> 100));
            assertEquals(
                    "external_execution_capacity_exceeded",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            new LangChain4jGovernance(control, options)
                                                    .guardModel(nativeModel)
                                                    .chat(request()))
                            .code());
            assertEquals(0, nativeCalls.get());
            assertEquals(0, root.usageSnapshot().heldTokens());
            assertEquals(0, root.usageSnapshot().uncertainCalls());
        }
    }
}
