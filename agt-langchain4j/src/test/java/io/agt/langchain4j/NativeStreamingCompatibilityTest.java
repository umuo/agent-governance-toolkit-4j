package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.TokenStream;
import io.agt.core.*;
import io.agt.core.policy.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 原生实时/严格流、Reactive 背压、取消与回调边界的行为验收。 */
class NativeStreamingCompatibilityTest {
    interface Stream {
        TokenStream chat(String input);
    }

    interface Reactive {
        Flow.Publisher<String> chat(String input);
    }

    interface Mixed {
        String sync(String input);

        TokenStream chat(String input);
    }

    static final class Subscriber<T> implements Flow.Subscriber<T> {
        final List<T> values = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Flow.Subscription subscription;

        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
        }

        public void onNext(T value) {
            values.add(value);
        }

        public void onError(Throwable e) {
            done.completeExceptionally(e);
        }

        public void onComplete() {
            done.complete(null);
        }
    }

    @ParameterizedTest
    @EnumSource(StreamDelivery.class)
    void deltasFollowSelectedContractAndFinalResponseIsAlwaysChecked(StreamDelivery mode)
            throws Exception {
        var source = new AtomicReference<StreamingChatResponseHandler>();
        var ready = new CountDownLatch(1);
        var model =
                new StreamingGovernanceTest.Model(
                        (r, h) -> {
                            source.set(h);
                            h.onPartialResponse("secret");
                            ready.countDown();
                        });
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
                    new LangChain4jGovernance(control, mode)
                            .create(
                                    Stream.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.streamingChatModel(model));
            var stream = LangChain4jGovernance.buffered(service.chat("hello"));
            assertEquals(mode, stream.deliveryMode());
            var chunks = new CopyOnWriteArrayList<String>();
            stream.onPartialResponse(chunks::add).onError(e -> {});
            stream.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(mode == StreamDelivery.REALTIME ? List.of("secret") : List.of(), chunks);
            source.get().onCompleteResponse(LangChain4jGovernanceTest.text("secret"));
            assertEquals("masked", stream.completion().get(5, TimeUnit.SECONDS).aiMessage().text());
            assertEquals(
                    mode == StreamDelivery.REALTIME ? List.of("secret") : List.of("masked"),
                    chunks);
        }
    }

    @Test
    void defaultIsRealtimeAndCancellationStopsLateCallbacks() throws Exception {
        var source = new AtomicReference<StreamingChatResponseHandler>();
        var ready = new CountDownLatch(1);
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    Stream.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.streamingChatModel(
                                                    new StreamingGovernanceTest.Model(
                                                            (r, h) -> {
                                                                source.set(h);
                                                                h.onPartialResponse("a");
                                                                ready.countDown();
                                                            })));
            var stream = LangChain4jGovernance.buffered(service.chat("hello"));
            var chunks = new CopyOnWriteArrayList<String>();
            stream.onPartialResponse(chunks::add).ignoreErrors();
            stream.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("a"), chunks);
            assertTrue(stream.cancel());
            source.get().onPartialResponse("late");
            source.get().onCompleteResponse(LangChain4jGovernanceTest.text("late"));
            assertEquals(List.of("a"), chunks);
        }
    }

    @Test
    void mixedInterfaceUsesEachNativeModel() throws Exception {
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    Mixed.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.chatModel(
                                                            new NativeIntegrationTest.NativeModel(
                                                                    r ->
                                                                            LangChain4jGovernanceTest
                                                                                    .text("sync")))
                                                    .streamingChatModel(
                                                            new StreamingGovernanceTest.Model(
                                                                    (r, h) ->
                                                                            h.onCompleteResponse(
                                                                                    LangChain4jGovernanceTest
                                                                                            .text(
                                                                                                    "stream")))));
            assertEquals("sync", service.sync("hello"));
            var stream = LangChain4jGovernance.buffered(service.chat("hello"));
            stream.ignoreErrors().start();
            assertEquals("stream", stream.completion().get(5, TimeUnit.SECONDS).aiMessage().text());
        }
    }

    @ParameterizedTest
    @EnumSource(StreamDelivery.class)
    void reactiveStartsOnDemandAndRespectsDownstreamDemand(StreamDelivery mode) throws Exception {
        var source = new AtomicReference<StreamingChatResponseHandler>();
        var ready = new CountDownLatch(1);
        var model =
                new StreamingGovernanceTest.Model(
                        (r, h) -> {
                            source.set(h);
                            ready.countDown();
                        });
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control, mode)
                            .create(
                                    Reactive.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.streamingChatModel(model));
            var sub = new Subscriber<String>();
            service.chat("hello").subscribe(sub);
            assertEquals(0, model.calls.get());
            sub.subscription.request(1);
            if (!ready.await(2, TimeUnit.SECONDS)) {
                sub.done.get(1, TimeUnit.SECONDS);
                fail("model did not start");
            }
            source.get().onPartialResponse("a");
            source.get().onPartialResponse("b");
            source.get().onCompleteResponse(LangChain4jGovernanceTest.text("ab"));
            if (mode == StreamDelivery.REALTIME) {
                assertEquals(List.of("a"), sub.values);
                assertFalse(sub.done.isDone());
                sub.subscription.request(1);
                assertEquals(List.of("a", "b"), sub.values);
            } else {
                sub.done.get(5, TimeUnit.SECONDS);
                assertEquals(List.of("ab"), sub.values);
            }
            sub.done.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void invalidDemandDoesNotStartModelAndSingleRunCannotResubscribe() throws Exception {
        var model = new StreamingGovernanceTest.Model((r, h) -> fail("must not run"));
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    Reactive.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.streamingChatModel(model));
            var publisher = service.chat("hello");
            var sub = new Subscriber<String>();
            publisher.subscribe(sub);
            sub.subscription.request(0);
            assertTrue(sub.done.isCompletedExceptionally());
            assertEquals(0, model.calls.get());
            var again = new Subscriber<String>();
            publisher.subscribe(again);
            assertTrue(again.done.isCompletedExceptionally());
        }
    }

    interface Events {
        Flow.Publisher<dev.langchain4j.service.AiServiceStreamingEvent> chat(String input);
    }

    @ParameterizedTest
    @EnumSource(StreamDelivery.class)
    void reactiveEventsHaveCheckedFinalResponseAndKeepTheirNativeTypes(StreamDelivery mode)
            throws Exception {
        var model =
                new StreamingGovernanceTest.Model(
                        (r, h) -> {
                            h.onUnmappedRawEvent(Map.of("raw", "secret"));
                            h.onPartialResponse("sec");
                            h.onPartialResponse("ret");
                            h.onCompleteResponse(LangChain4jGovernanceTest.text("secret"));
                        });
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
                    new LangChain4jGovernance(control, mode)
                            .create(
                                    Events.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.streamingChatModel(model));
            var sub = new Subscriber<dev.langchain4j.service.AiServiceStreamingEvent>();
            service.chat("hello").subscribe(sub);
            sub.subscription.request(Long.MAX_VALUE);
            sub.done.get(5, TimeUnit.SECONDS);
            var last =
                    sub.values.stream()
                            .filter(
                                    v ->
                                            v
                                                    instanceof
                                                    dev.langchain4j.service.AiServiceStreamingEvent
                                                            .FinalResponseEvent)
                            .map(
                                    v ->
                                            (dev.langchain4j.service.AiServiceStreamingEvent
                                                            .FinalResponseEvent)
                                                    v)
                            .findFirst()
                            .orElseThrow();
            assertEquals("masked", last.chatResponse().aiMessage().text());
            assertEquals(
                    mode == StreamDelivery.REALTIME ? 1 : 0,
                    sub.values.stream()
                            .filter(
                                    v ->
                                            v
                                                    instanceof
                                                    dev.langchain4j.service.AiServiceStreamingEvent
                                                            .RawEvent)
                            .count());
            if (mode == StreamDelivery.STRICT)
                assertFalse(sub.values.toString().contains("secret"));
        }
    }

    @Test
    void reactiveCancellationClosesOwnedSessionAndStopsLateDeltas() throws Exception {
        var source = new AtomicReference<StreamingChatResponseHandler>();
        var ready = new CountDownLatch(1);
        var records = NativeIntegrationTest.audit();
        var closed = new CountDownLatch(1);
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(
                                record -> {
                                    records.add(record);
                                    if (record.point()
                                            == io.agt.core.InterventionPoint.AGENT_SHUTDOWN)
                                        closed.countDown();
                                })
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
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
            var sub = new Subscriber<String>();
            service.chat("hello").subscribe(sub);
            sub.subscription.request(1);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            sub.subscription.cancel();
            source.get().onPartialResponse("late");
            source.get().onCompleteResponse(LangChain4jGovernanceTest.text("late"));
            assertTrue(sub.values.isEmpty());
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertEquals(1, NativeIntegrationTest.shutdowns(records));
        }
    }
}
