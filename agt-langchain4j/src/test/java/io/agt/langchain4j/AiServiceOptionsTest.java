package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.policy.ContentRule;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 服务配置隔离、提前诊断和缓存后的逐次治理验收。 */
class AiServiceOptionsTest {
    @Test
    void invalidOptionsFailBeforeCreatingAnyService() {
        assertThrows(
                NullPointerException.class,
                () -> new AiServiceOptions(null, StreamingLimits.DEFAULT, 32));
        assertThrows(
                NullPointerException.class,
                () -> new AiServiceOptions(StreamDelivery.REALTIME, null, 32));
        for (int n : new int[] {0, -1, 1025, Integer.MAX_VALUE}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new AiServiceOptions(
                                    StreamDelivery.REALTIME, StreamingLimits.DEFAULT, n));
        }
        assertEquals(32, AiServiceOptions.DEFAULT.publisherPrefetch());
    }

    @Test
    void eachServiceOverridesDeliveryAndModelLimitsIndependently() throws Exception {
        var model =
                new StreamingGovernanceTest.Model(
                        (r, h) -> {
                            h.onPartialResponse("hello");
                            h.onCompleteResponse(LangChain4jGovernanceTest.text("hello"));
                        });
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(r -> {})
                        .build()) {
            var governance = new LangChain4jGovernance(control);
            var small =
                    governance.create(
                            NativeIntegrationTest.StreamAssistant.class,
                            () -> NativeIntegrationTest.CTX,
                            new AiServiceOptions(
                                    StreamDelivery.STRICT, new StreamingLimits(2, 10), 1),
                            b -> b.streamingChatModel(model));
            var normal =
                    governance.create(
                            NativeIntegrationTest.StreamAssistant.class,
                            () -> NativeIntegrationTest.CTX,
                            b -> b.streamingChatModel(model));
            var rejected = LangChain4jGovernance.buffered(small.chat("input"));
            assertEquals(StreamDelivery.STRICT, rejected.deliveryMode());
            rejected.ignoreErrors().start();
            assertThrows(
                    ExecutionException.class, () -> rejected.completion().get(5, TimeUnit.SECONDS));
            var accepted = LangChain4jGovernance.buffered(normal.chat("input"));
            assertEquals(StreamDelivery.REALTIME, accepted.deliveryMode());
            accepted.ignoreErrors().start();
            assertEquals(
                    "hello", accepted.completion().get(5, TimeUnit.SECONDS).aiMessage().text());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = StreamDelivery.class,
            names = {"REALTIME", "STRICT"})
    void aggregateLimitsAndSelectedPrefetchApplyToPublisher(StreamDelivery mode) throws Exception {
        var source = new PublisherResourceTest.Source(20);
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
                            mode,
                            false,
                            new StreamingLimits(100, 3),
                            2);
            var receiver = new PublisherResourceTest.Receiver<Object>();
            publisher.subscribe(receiver);
            receiver.subscription.request(Long.MAX_VALUE);
            assertThrows(ExecutionException.class, () -> receiver.done.get(5, TimeUnit.SECONDS));
            assertTrue(source.maxRequest.get() <= 2);
            assertTrue(source.cancelled.get());
            if (mode == StreamDelivery.STRICT) assertTrue(receiver.values.isEmpty());
        }
    }

    @Test
    void missingChatModelFailsDuringBuildEvenWhenStreamingModelExists() {
        var model = new StreamingGovernanceTest.Model((r, h) -> fail("must not run"));
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(r -> {})
                        .build()) {
            var error =
                    assertThrows(
                            GovernanceException.class,
                            () ->
                                    new LangChain4jGovernance(control)
                                            .create(
                                                    NativeIntegrationTest.Assistant.class,
                                                    () -> NativeIntegrationTest.CTX,
                                                    b -> b.streamingChatModel(model)));
            assertEquals("missing_model", error.code());
            assertEquals(0, model.calls.get());
        }
    }

    @Test
    void methodAndSpiCachesDoNotCacheValuesOrGovernanceDecisions() throws Exception {
        var discoveries = new AtomicInteger();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader loader =
                new ClassLoader(original) {
                    @Override
                    public java.util.Enumeration<java.net.URL> getResources(String name)
                            throws java.io.IOException {
                        if (name.equals(
                                        "META-INF/services/dev.langchain4j.spi.services.PublisherAdapter")
                                || name.equals(
                                        "META-INF/services/dev.langchain4j.spi.services.TokenStreamAdapter"))
                            discoveries.incrementAndGet();
                        return super.getResources(name);
                    }
                };
        Thread.currentThread().setContextClassLoader(loader);
        var records = NativeIntegrationTest.audit();
        try (var control =
                AgentControl.builder(
                                NativeIntegrationTest.policy()
                                        .content(
                                                new ContentRule(
                                                        "deny",
                                                        Set.of(InterventionPoint.INPUT),
                                                        List.of("forbidden"),
                                                        Map.of()))
                                        .build())
                        .audit(records::add)
                        .build()) {
            var model =
                    new NativeIntegrationTest.NativeModel(
                            r -> LangChain4jGovernanceTest.text("ok"));
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    NativeIntegrationTest.Assistant.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.chatModel(model));
            int count = discoveries.get();
            assertTrue(count > 0);
            assertEquals("ok", service.chat("first"));
            assertEquals("ok", service.chat("second"));
            assertThrows(GovernanceException.class, () -> service.chat("forbidden"));
            assertEquals(count, discoveries.get());
            assertEquals(2, model.calls.get());
            assertEquals(3, NativeIntegrationTest.shutdowns(records));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
