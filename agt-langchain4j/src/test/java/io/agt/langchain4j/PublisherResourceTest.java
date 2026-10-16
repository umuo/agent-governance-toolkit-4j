package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.model.chat.response.RawStreamingEvent;
import dev.langchain4j.service.AiServiceStreamingEvent;
import io.agt.core.*;
import io.agt.core.policy.ContentRule;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 真实 demand 交互、长流文本完整检查及交付前快照的资源边界。 */
class PublisherResourceTest {
    static class Receiver<T> implements Flow.Subscriber<T> {
        final List<T> values = Collections.synchronizedList(new ArrayList<>());
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Flow.Subscription subscription;

        public void onSubscribe(Flow.Subscription source) {
            subscription = source;
        }

        public void onNext(T value) {
            values.add(value);
        }

        public void onError(Throwable error) {
            done.completeExceptionally(error);
        }

        public void onComplete() {
            done.complete(null);
        }
    }

    static final class Source implements Flow.Publisher<Object>, Flow.Subscription {
        final int count;
        final AtomicInteger produced = new AtomicInteger();
        final AtomicInteger maxRequest = new AtomicInteger();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch parked = new CountDownLatch(1);
        Flow.Subscriber<? super Object> downstream;

        Source(int count) {
            this.count = count;
        }

        public void subscribe(Flow.Subscriber<? super Object> subscriber) {
            downstream = subscriber;
            subscriber.onSubscribe(this);
        }

        public void request(long n) {
            maxRequest.accumulateAndGet(Math.toIntExact(n), Math::max);
            for (long i = 0; i < n && !cancelled.get() && produced.get() < count; i++) {
                int number = produced.getAndIncrement();
                downstream.onNext("" + (char) ('a' + number % 26));
                if (produced.get() == GovernedPublisher.PREFETCH + 1) parked.countDown();
            }
            if (produced.get() == count && !cancelled.get()) downstream.onComplete();
        }

        public void cancel() {
            cancelled.set(true);
        }
    }

    @Test
    void slowConsumerKeepsOnlyFinitePrefetchAndRefillsAfterDemand() throws Exception {
        var source = new Source(200);
        try (var control =
                        AgentControl.builder(NativeIntegrationTest.policy().build())
                                .audit(r -> {})
                                .build();
                var session = control.openSession(NativeIntegrationTest.CTX)) {
            var publisher =
                    new GovernedPublisher(
                            session,
                            "hello",
                            input -> source,
                            String.class,
                            StreamDelivery.REALTIME,
                            false);
            var receiver = new Receiver<Object>();
            publisher.subscribe(receiver);
            receiver.subscription.request(1);
            assertTrue(source.parked.await(5, TimeUnit.SECONDS));
            assertEquals(1, receiver.values.size());
            assertEquals(GovernedPublisher.PREFETCH + 1, source.produced.get());
            assertTrue(source.maxRequest.get() <= GovernedPublisher.PREFETCH);
            receiver.subscription.request(Long.MAX_VALUE);
            receiver.done.get(5, TimeUnit.SECONDS);
            assertEquals(200, receiver.values.size());
            assertEquals(200, source.produced.get());
        }
    }

    @ParameterizedTest
    @EnumSource(StreamDelivery.class)
    void longTextStreamDoesNotKeepOneJsonArrayEntryPerFragment(StreamDelivery mode)
            throws Exception {
        // 超过 JSON 容器 4096 项，但低于流事件 10000 项；不应因重复保存 String 分片失败。
        var source = new Source(5000);
        try (var control =
                        AgentControl.builder(NativeIntegrationTest.policy().build())
                                .audit(r -> {})
                                .build();
                var session = control.openSession(NativeIntegrationTest.CTX)) {
            var receiver = new Receiver<Object>();
            new GovernedPublisher(session, "hello", input -> source, String.class, mode, false)
                    .subscribe(receiver);
            receiver.subscription.request(Long.MAX_VALUE);
            receiver.done.get(5, TimeUnit.SECONDS);
            assertEquals(mode == StreamDelivery.STRICT ? 1 : 5000, receiver.values.size());
            assertEquals(
                    5000,
                    receiver.values.stream().map(String::valueOf).mapToInt(String::length).sum());
            assertTrue(source.maxRequest.get() <= GovernedPublisher.PREFETCH);
        }
    }

    @Test
    void cancellingWithQueuedFragmentsStopsRefillAndLateDelivery() throws Exception {
        var source = new Source(200);
        try (var control =
                        AgentControl.builder(NativeIntegrationTest.policy().build())
                                .audit(r -> {})
                                .build();
                var session = control.openSession(NativeIntegrationTest.CTX)) {
            var receiver = new Receiver<Object>();
            new GovernedPublisher(
                            session,
                            "hello",
                            input -> source,
                            String.class,
                            StreamDelivery.REALTIME,
                            false)
                    .subscribe(receiver);
            receiver.subscription.request(1);
            assertTrue(source.parked.await(5, TimeUnit.SECONDS));
            receiver.subscription.cancel();
            int produced = source.produced.get();
            receiver.subscription.request(200);
            source.downstream.onNext("late");
            assertTrue(source.cancelled.get());
            assertEquals(produced, source.produced.get());
            assertEquals(1, receiver.values.size());
        }
    }

    @ParameterizedTest
    @EnumSource(StreamDelivery.class)
    void finalOutputStillChecksPatternsAcrossFragmentBoundaries(StreamDelivery mode)
            throws Exception {
        Flow.Publisher<Object> source =
                downstream ->
                        downstream.onSubscribe(
                                new Flow.Subscription() {
                                    boolean sent;

                                    public void request(long n) {
                                        if (sent) return;
                                        sent = true;
                                        downstream.onNext("sec");
                                        downstream.onNext("ret");
                                        downstream.onComplete();
                                    }

                                    public void cancel() {}
                                });
        try (var control =
                        AgentControl.builder(
                                        NativeIntegrationTest.policy()
                                                .content(
                                                        new ContentRule(
                                                                "deny",
                                                                Set.of(InterventionPoint.OUTPUT),
                                                                List.of("secret"),
                                                                Map.of()))
                                                .build())
                                .audit(r -> {})
                                .build();
                var session = control.openSession(NativeIntegrationTest.CTX)) {
            var receiver = new Receiver<Object>();
            new GovernedPublisher(session, "hello", input -> source, String.class, mode, false)
                    .subscribe(receiver);
            receiver.subscription.request(Long.MAX_VALUE);
            assertThrows(ExecutionException.class, () -> receiver.done.get(5, TimeUnit.SECONDS));
            assertEquals(
                    mode == StreamDelivery.STRICT ? List.of() : List.of("sec", "ret"),
                    receiver.values);
        }
    }

    @Test
    void mutablePreviewCannotRewriteHistoricalOutputInspection() throws Exception {
        var raw = new LinkedHashMap<String, Object>(Map.of("text", "secret"));
        var event =
                new AiServiceStreamingEvent.RawEvent(
                        RawStreamingEvent.of(raw),
                        dev.langchain4j.invocation.InvocationContext.builder()
                                .interfaceName("probe")
                                .methodName("chat")
                                .chatMemoryId("probe")
                                .build());
        Flow.Publisher<Object> source =
                downstream ->
                        downstream.onSubscribe(
                                new Flow.Subscription() {
                                    boolean sent;

                                    public void request(long n) {
                                        if (sent) return;
                                        sent = true;
                                        downstream.onNext(event);
                                        downstream.onComplete();
                                    }

                                    public void cancel() {}
                                });
        try (var control =
                        AgentControl.builder(
                                        NativeIntegrationTest.policy()
                                                .content(
                                                        new ContentRule(
                                                                "deny",
                                                                Set.of(InterventionPoint.OUTPUT),
                                                                List.of("secret"),
                                                                Map.of()))
                                                .build())
                                .audit(r -> {})
                                .build();
                var session = control.openSession(NativeIntegrationTest.CTX)) {
            var receiver =
                    new Receiver<Object>() {
                        public void onNext(Object value) {
                            super.onNext(value);
                            raw.put("text", "changed-by-consumer");
                        }
                    };
            new GovernedPublisher(
                            session,
                            "hello",
                            input -> source,
                            AiServiceStreamingEvent.class,
                            StreamDelivery.REALTIME,
                            false)
                    .subscribe(receiver);
            receiver.subscription.request(Long.MAX_VALUE);
            assertThrows(ExecutionException.class, () -> receiver.done.get(5, TimeUnit.SECONDS));
            assertEquals(1, receiver.values.size());
            assertEquals("changed-by-consumer", raw.get("text"));
        }
    }

    @Test
    void sourceErrorIsDeliveredWithoutWaitingForMoreDownstreamDemand() throws Exception {
        var source = new Source(200);
        try (var control =
                        AgentControl.builder(NativeIntegrationTest.policy().build())
                                .audit(r -> {})
                                .build();
                var session = control.openSession(NativeIntegrationTest.CTX)) {
            var receiver = new Receiver<Object>();
            new GovernedPublisher(
                            session,
                            "hello",
                            input -> source,
                            String.class,
                            StreamDelivery.REALTIME,
                            false)
                    .subscribe(receiver);
            receiver.subscription.request(1);
            assertTrue(source.parked.await(5, TimeUnit.SECONDS));
            source.downstream.onError(new IllegalStateException("private provider message"));
            var error =
                    assertThrows(
                            ExecutionException.class, () -> receiver.done.get(5, TimeUnit.SECONDS));
            assertInstanceOf(GovernanceException.class, error.getCause());
            assertFalse(error.getCause().getMessage().contains("private provider message"));
            assertEquals(1, receiver.values.size());
            assertTrue(source.cancelled.get());
        }
    }

    @Test
    void upstreamCannotBypassFiniteCredit() throws Exception {
        var cancelled = new AtomicBoolean();
        Flow.Publisher<Object> source =
                downstream ->
                        downstream.onSubscribe(
                                new Flow.Subscription() {
                                    public void request(long n) {
                                        for (int i = 0; i < n + 1 && !cancelled.get(); i++)
                                            downstream.onNext("x");
                                    }

                                    public void cancel() {
                                        cancelled.set(true);
                                    }
                                });
        try (var control =
                        AgentControl.builder(NativeIntegrationTest.policy().build())
                                .audit(r -> {})
                                .build();
                var session = control.openSession(NativeIntegrationTest.CTX)) {
            var receiver = new Receiver<Object>();
            new GovernedPublisher(
                            session,
                            "hello",
                            input -> source,
                            String.class,
                            StreamDelivery.REALTIME,
                            false)
                    .subscribe(receiver);
            receiver.subscription.request(1);
            var error =
                    assertThrows(
                            ExecutionException.class, () -> receiver.done.get(5, TimeUnit.SECONDS));
            assertEquals(
                    "publisher_without_demand", ((GovernanceException) error.getCause()).code());
            assertTrue(cancelled.get());
            assertEquals(1, receiver.values.size());
        }
    }
}
