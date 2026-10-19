package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.TokenStream;
import io.agt.core.*;
import io.agt.core.audit.AuditRecord;
import io.agt.core.policy.*;
import io.agt.core.streaming.ChunkBufferOptions;
import io.agt.langchain4j.internal.streaming.StreamingAdapters;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class GuardedStreamingTest {
    interface Stream {
        TokenStream chat(String text);
    }

    interface Reactive {
        Flow.Publisher<String> chat(String text);
    }

    static final ContentRule LOCAL =
            new ContentRule(
                    "local",
                    Set.of(InterventionPoint.POST_MODEL_CALL),
                    List.of("ABCDEF"),
                    Map.of());

    static AiServiceOptions options() {
        return new AiServiceOptions(
                StreamDelivery.GUARDED,
                StreamingLimits.DEFAULT,
                4,
                new ChunkBufferOptions(3, "，。！？\n", Duration.ofMillis(10), 4096, Set.of("local")));
    }

    static StructuredPolicy.Builder policy() {
        return NativeIntegrationTest.policy().content(LOCAL);
    }

    interface FluxService {
        reactor.core.publisher.Flux<String> chat(String input);
    }

    @Test
    void providerPushExceedingPublisherCreditFailsAndCancelsHandle() throws Exception {
        var source = new AtomicReference<StreamingChatResponseHandler>();
        var ready = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build()) {
            var service =
                    new LangChain4jGovernance(control, options())
                            .create(
                                    Reactive.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.streamingChatModel(
                                                    new StreamingGovernanceTest.Model(
                                                            (r, h) -> {
                                                                source.set(h);
                                                                ready.countDown();
                                                            })));
            var sub = new NativeStreamingCompatibilityTest.Subscriber<String>();
            service.chat("hello").subscribe(sub);
            sub.subscription.request(1);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            source.get()
                    .onPartialResponse(
                            new PartialResponse("safe ".repeat(100)),
                            new PartialResponseContext(
                                    new StreamingHandle() {
                                        public void cancel() {
                                            cancelled.set(true);
                                        }

                                        public boolean isCancelled() {
                                            return cancelled.get();
                                        }
                                    }));
            assertThrows(ExecutionException.class, () -> sub.done.get(5, TimeUnit.SECONDS));
            assertTrue(cancelled.get());
            assertEquals(1, sub.values.size());
        }
    }

    @Test
    void coreSessionTerminationClearsPendingGateAndTimer() {
        try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build()) {
            var session = control.openDetachedSession(NativeIntegrationTest.CTX);
            var chunks = new ArrayList<String>();
            var gate = session.chunkBuffer(options().guardrails(), "round", chunks::add, e -> {});
            gate.accept("AB");
            assertTrue(gate.hasScheduledCheck());
            session.close();
            assertFalse(gate.hasScheduledCheck());
            assertEquals(0, gate.bufferedCharacters());
            gate.accept("CDEF");
            assertTrue(chunks.isEmpty());
        }
    }

    @Test
    void toolRoundsResetLocalStateAndDeniedRoundCannotExecuteTools() throws Exception {
        for (boolean deny : List.of(false, true)) {
            var records = new CopyOnWriteArrayList<AuditRecord>();
            var tools = new StreamingGovernanceTest.Tools();
            var calls = new AtomicInteger();
            var builder = StreamingGovernanceTest.policy().content(LOCAL);
            try (var control = AgentControl.builder(builder.build()).audit(records::add).build()) {
                var service =
                        new LangChain4jGovernance(control, options())
                                .create(
                                        Stream.class,
                                        () -> NativeIntegrationTest.CTX,
                                        b ->
                                                b.tools(tools)
                                                        .streamingChatModel(
                                                                new StreamingGovernanceTest.Model(
                                                                        (r, h) -> {
                                                                            if (calls
                                                                                            .incrementAndGet()
                                                                                    == 1) {
                                                                                String text =
                                                                                        deny
                                                                                                ? "ABCDEF"
                                                                                                : "ABC";
                                                                                h.onPartialResponse(
                                                                                        text);
                                                                                var call =
                                                                                        StreamingGovernanceTest
                                                                                                .call(
                                                                                                        NativeIntegrationTest
                                                                                                                .CTX
                                                                                                                .tenantId());
                                                                                h
                                                                                        .onCompleteResponse(
                                                                                                call
                                                                                                        .toBuilder()
                                                                                                        .aiMessage(
                                                                                                                dev
                                                                                                                        .langchain4j
                                                                                                                        .data
                                                                                                                        .message
                                                                                                                        .AiMessage
                                                                                                                        .builder()
                                                                                                                        .text(
                                                                                                                                text)
                                                                                                                        .toolExecutionRequests(
                                                                                                                                call.aiMessage()
                                                                                                                                        .toolExecutionRequests())
                                                                                                                        .build())
                                                                                                        .build());
                                                                            } else {
                                                                                h.onPartialResponse(
                                                                                        "DEF");
                                                                                h
                                                                                        .onCompleteResponse(
                                                                                                LangChain4jGovernanceTest
                                                                                                        .text(
                                                                                                                "DEF"));
                                                                            }
                                                                        })));
                var stream = LangChain4jGovernance.buffered(service.chat("hello"));
                stream.ignoreErrors().start();
                if (deny) {
                    assertThrows(
                            ExecutionException.class,
                            () -> stream.completion().get(5, TimeUnit.SECONDS));
                    assertEquals(0, tools.effects.get());
                    assertEquals(1, calls.get());
                } else {
                    stream.completion().get(5, TimeUnit.SECONDS);
                    assertEquals(1, tools.effects.get());
                    assertEquals(2, calls.get());
                    var localIds =
                            records.stream()
                                    .filter(r -> r.executionOutcome().equals("stream_local"))
                                    .map(AuditRecord::actionId)
                                    .collect(java.util.stream.Collectors.toSet());
                    assertEquals(2, localIds.size());
                    assertTrue(
                            records.stream()
                                    .filter(
                                            r ->
                                                    r.point() == InterventionPoint.POST_MODEL_CALL
                                                            && !r.executionOutcome()
                                                                    .equals("stream_local"))
                                    .map(AuditRecord::actionId)
                                    .collect(java.util.stream.Collectors.toSet())
                                    .containsAll(localIds));
                }
            }
        }
    }

    @Test
    void nativeOutputGuardrailsAreRejectedBeforeStreaming() {
        try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new LangChain4jGovernance(control, options())
                                    .create(
                                            Stream.class,
                                            () -> NativeIntegrationTest.CTX,
                                            b ->
                                                    b.streamingChatModel(
                                                                    new StreamingGovernanceTest
                                                                            .Model((r, h) -> {}))
                                                            .outputGuardrails(
                                                                    new dev.langchain4j.guardrail
                                                                            .OutputGuardrail() {
                                                                        public dev.langchain4j
                                                                                        .guardrail
                                                                                        .OutputGuardrailResult
                                                                                validate(
                                                                                        dev
                                                                                                        .langchain4j
                                                                                                        .data
                                                                                                        .message
                                                                                                        .AiMessage
                                                                                                message) {
                                                                            return dev.langchain4j
                                                                                    .guardrail
                                                                                    .OutputGuardrailResult
                                                                                    .success();
                                                                        }
                                                                    })));
        }
    }

    @Test
    void fluxUsesGuardedChunks() throws Exception {
        try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build()) {
            var service =
                    new LangChain4jGovernance(control, options())
                            .create(
                                    FluxService.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.streamingChatModel(
                                                    new StreamingGovernanceTest.Model(
                                                            (r, h) -> {
                                                                h.onPartialResponse(
                                                                        "safe safe safe");
                                                                h.onCompleteResponse(
                                                                        LangChain4jGovernanceTest
                                                                                .text(
                                                                                        "safe safe safe"));
                                                            })));
            assertEquals(
                    "safe safe safe",
                    String.join(
                            "", service.chat("hello").collectList().block(Duration.ofSeconds(5))));
        }
    }

    @Test
    void localAuditFailureAndCancellationDiscardPendingText() throws Exception {
        for (boolean auditFailure : List.of(false, true)) {
            var source = new AtomicReference<StreamingChatResponseHandler>();
            var ready = new CountDownLatch(1);
            try (var control =
                    AgentControl.builder(policy().build())
                            .audit(
                                    r -> {
                                        if (auditFailure
                                                && r.executionOutcome().equals("stream_local"))
                                            throw new IllegalStateException("sensitive");
                                    })
                            .build()) {
                var service =
                        new LangChain4jGovernance(control, options())
                                .create(
                                        Stream.class,
                                        () -> NativeIntegrationTest.CTX,
                                        b ->
                                                b.streamingChatModel(
                                                        new StreamingGovernanceTest.Model(
                                                                (r, h) -> {
                                                                    source.set(h);
                                                                    ready.countDown();
                                                                })));
                var stream = LangChain4jGovernance.buffered(service.chat("hello"));
                var chunks = new CopyOnWriteArrayList<String>();
                stream.onPartialResponse(chunks::add).ignoreErrors().start();
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                if (auditFailure) {
                    source.get().onPartialResponse("safe safe safe");
                    assertThrows(
                            ExecutionException.class,
                            () -> stream.completion().get(5, TimeUnit.SECONDS));
                } else {
                    source.get().onPartialResponse("AB");
                    stream.cancel();
                    source.get().onPartialResponse("CDEF");
                }
                assertTrue(chunks.isEmpty());
            }
        }
    }

    @Test
    void tokenStreamDeliversSafePrefixAndBlocksSplitWordCancellingLateHandle() throws Exception {
        var source = new AtomicReference<StreamingChatResponseHandler>();
        var ready = new CountDownLatch(1);
        var records = new CopyOnWriteArrayList<AuditRecord>();
        var cancelled = new AtomicBoolean();
        try (var control = AgentControl.builder(policy().build()).audit(records::add).build()) {
            var service =
                    new LangChain4jGovernance(control, options())
                            .create(
                                    Stream.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.streamingChatModel(
                                                    new StreamingGovernanceTest.Model(
                                                            (r, h) -> {
                                                                source.set(h);
                                                                h.onPartialResponse("普通文本足够长ABC");
                                                                ready.countDown();
                                                            })));
            var stream = LangChain4jGovernance.buffered(service.chat("hello"));
            var chunks = new CopyOnWriteArrayList<String>();
            var errors = new AtomicInteger();
            stream.onPartialResponse(chunks::add).onError(e -> errors.incrementAndGet()).start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertFalse(chunks.isEmpty());
            assertFalse(String.join("", chunks).contains("A"));
            source.get().onPartialResponse("DEF后续");
            assertThrows(
                    ExecutionException.class, () -> stream.completion().get(5, TimeUnit.SECONDS));
            source.get()
                    .onPartialResponse(
                            new PartialResponse("late"),
                            new PartialResponseContext(
                                    new StreamingHandle() {
                                        public void cancel() {
                                            cancelled.set(true);
                                        }

                                        public boolean isCancelled() {
                                            return cancelled.get();
                                        }
                                    }));
            source.get().onCompleteResponse(LangChain4jGovernanceTest.text("普通文本足够长ABCDEF后续"));
            assertTrue(cancelled.get());
            assertEquals(1, errors.get());
            assertTrue("普通文本足够长".startsWith(String.join("", chunks)));
            var local =
                    records.stream()
                            .filter(r -> r.executionOutcome().equals("stream_local"))
                            .toList();
            assertTrue(
                    local.stream()
                            .anyMatch(
                                    r ->
                                            r.verdict().equals("DENY")
                                                    && r.reasonCode().equals("local")));
            assertTrue(
                    local.stream()
                            .allMatch(
                                    r ->
                                            r.inputIdentity() == null
                                                    && r.warnings().stream()
                                                            .anyMatch(
                                                                    w -> w.startsWith("offset="))));
        }
    }

    @Test
    void normalAndEvaluateOnlyStreamsCompleteWithoutReplay() throws Exception {
        for (var mode : EnforcementMode.values()) {
            String text = mode == EnforcementMode.ENFORCE ? "普通安全回答足够长结束" : "普通ABCDEF结束";
            try (var control =
                    AgentControl.builder(policy().build()).mode(mode).audit(r -> {}).build()) {
                var service =
                        new LangChain4jGovernance(control, options())
                                .create(
                                        Stream.class,
                                        () -> NativeIntegrationTest.CTX,
                                        b ->
                                                b.streamingChatModel(
                                                        new StreamingGovernanceTest.Model(
                                                                (r, h) -> {
                                                                    for (char c :
                                                                            text.toCharArray())
                                                                        h.onPartialResponse(
                                                                                String.valueOf(c));
                                                                    h.onCompleteResponse(
                                                                            LangChain4jGovernanceTest
                                                                                    .text(text));
                                                                })));
                var stream = LangChain4jGovernance.buffered(service.chat("hello"));
                var chunks = new CopyOnWriteArrayList<String>();
                stream.onPartialResponse(chunks::add).ignoreErrors().start();
                assertEquals(text, stream.completion().get(5, TimeUnit.SECONDS).aiMessage().text());
                assertEquals(text, String.join("", chunks));
            }
        }
    }

    @Test
    void authoritativeMismatchAndOutputDenialNeverFlushTail() throws Exception {
        for (boolean mismatch : List.of(true, false)) {
            var builder = policy();
            if (!mismatch)
                builder.content(
                        new ContentRule(
                                "final_deny",
                                Set.of(InterventionPoint.OUTPUT),
                                List.of("tail"),
                                Map.of()));
            try (var control = AgentControl.builder(builder.build()).audit(r -> {}).build()) {
                var service =
                        new LangChain4jGovernance(control, options())
                                .create(
                                        Stream.class,
                                        () -> NativeIntegrationTest.CTX,
                                        b ->
                                                b.streamingChatModel(
                                                        new StreamingGovernanceTest.Model(
                                                                (r, h) -> {
                                                                    h.onPartialResponse("tail");
                                                                    h.onCompleteResponse(
                                                                            LangChain4jGovernanceTest
                                                                                    .text(
                                                                                            mismatch
                                                                                                    ? "other"
                                                                                                    : "tail"));
                                                                })));
                var stream = LangChain4jGovernance.buffered(service.chat("hello"));
                var chunks = new CopyOnWriteArrayList<String>();
                stream.onPartialResponse(chunks::add).ignoreErrors().start();
                assertThrows(
                        ExecutionException.class,
                        () -> stream.completion().get(5, TimeUnit.SECONDS));
                assertTrue(chunks.isEmpty());
            }
        }
    }

    @Test
    void publisherSharesGateAndErrorsWithoutFurtherDemand() throws Exception {
        var source = new AtomicReference<StreamingChatResponseHandler>();
        var ready = new CountDownLatch(1);
        try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build()) {
            var service =
                    new LangChain4jGovernance(control, options())
                            .create(
                                    Reactive.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.streamingChatModel(
                                                    new StreamingGovernanceTest.Model(
                                                            (r, h) -> {
                                                                source.set(h);
                                                                ready.countDown();
                                                            })));
            var sub = new NativeStreamingCompatibilityTest.Subscriber<String>();
            service.chat("hello").subscribe(sub);
            sub.subscription.request(1);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            source.get().onPartialResponse("安全文本足够长ABC");
            assertEquals(1, sub.values.size());
            source.get().onPartialResponse("DEF后续");
            assertThrows(ExecutionException.class, () -> sub.done.get(5, TimeUnit.SECONDS));
            assertFalse(String.join("", sub.values).contains("A"));
        }
    }

    @Test
    void handlerUsesSameGateAndSuppressesSideChannels() throws Exception {
        try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
                var session = control.openSession(NativeIntegrationTest.CTX)) {
            var model =
                    StreamingAdapters.guard(
                            control,
                            new StreamingGovernanceTest.Model(
                                    (r, h) -> {
                                        h.onPartialThinking(new PartialThinking("ABCDEF"));
                                        h.onUnmappedRawEvent(Map.of("raw", "ABCDEF"));
                                        h.onPartialResponse("safe output safe");
                                        h.onCompleteResponse(
                                                LangChain4jGovernanceTest.text("safe output safe"));
                                    }),
                            StreamingLimits.DEFAULT,
                            StreamDelivery.GUARDED,
                            options().guardrails());
            var done = new CompletableFuture<Void>();
            var parts = new CopyOnWriteArrayList<String>();
            model.chat(
                    dev.langchain4j.model.chat.request.ChatRequest.builder()
                            .messages(dev.langchain4j.data.message.UserMessage.from("hello"))
                            .build(),
                    new StreamingChatResponseHandler() {
                        public void onPartialResponse(String text) {
                            parts.add(text);
                        }

                        public void onPartialThinking(PartialThinking value) {
                            fail("thinking leak");
                        }

                        public void onUnmappedRawEvent(Object value) {
                            fail("raw leak");
                        }

                        public void onCompleteResponse(ChatResponse response) {
                            done.complete(null);
                        }

                        public void onError(Throwable error) {
                            done.completeExceptionally(error);
                        }
                    });
            done.get(5, TimeUnit.SECONDS);
            assertEquals("safe output safe", String.join("", parts));
        }
    }

    @Test
    void rejectsUnboundOrTransformingPoliciesAndMissingConfig() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AiServiceOptions(StreamDelivery.GUARDED, StreamingLimits.DEFAULT, 32));
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(r -> {})
                        .build()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new LangChain4jGovernance(control, options()));
        }
        try (var control =
                AgentControl.builder(
                                policy().content(
                                                NativeIntegrationTest.redact(
                                                        InterventionPoint.OUTPUT))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new LangChain4jGovernance(control, options()));
        }
    }
}
