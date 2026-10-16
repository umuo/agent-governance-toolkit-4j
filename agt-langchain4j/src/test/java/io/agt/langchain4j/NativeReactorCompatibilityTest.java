package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;

/** 验证宿主选装的原生 Reactor 适配器，无新增生产运行时依赖。 */
class NativeReactorCompatibilityTest {
    interface FluxService {
        Flux<String> chat(String input);
    }

    interface MonoService {
        Mono<StructuredCompatibilityTest.Order> chat(String input);
    }

    @Test
    void fluxUsesNativeStreamingAdapterAndClosesSession() {
        var records = NativeIntegrationTest.audit();
        var model =
                new StreamingGovernanceTest.Model(
                        (r, h) -> {
                            h.onPartialResponse("a");
                            h.onPartialResponse("b");
                            h.onCompleteResponse(LangChain4jGovernanceTest.text("ab"));
                        });
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(records::add)
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    FluxService.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.streamingChatModel(model));
            var flux = service.chat("hello");
            assertEquals(0, model.calls.get());
            assertEquals(List.of("a", "b"), flux.collectList().block(Duration.ofSeconds(5)));
            assertEquals(1, NativeIntegrationTest.shutdowns(records));
        }
    }

    @Test
    void monoChecksStructuredValueAndClosesBeforeCompletion() {
        var records = NativeIntegrationTest.audit();
        var model =
                new NativeIntegrationTest.NativeModel(
                        r -> LangChain4jGovernanceTest.text("{\"name\":\"secret\",\"count\":1}"));
        try (var control =
                AgentControl.builder(
                                NativeIntegrationTest.policy()
                                        .content(
                                                NativeIntegrationTest.redact(
                                                        InterventionPoint.OUTPUT))
                                        .build())
                        .audit(records::add)
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    MonoService.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.chatModel(model));
            var mono = service.chat("hello");
            assertEquals(0, model.calls.get());
            assertEquals(
                    new StructuredCompatibilityTest.Order("masked", 1),
                    mono.block(Duration.ofSeconds(5)));
            assertEquals(1, NativeIntegrationTest.shutdowns(records));
        }
    }

    @Test
    void monoDoesNotExposeValueWhenOwnedSessionShutdownAuditFails() {
        var seen = new AtomicInteger();
        var model =
                new NativeIntegrationTest.NativeModel(
                        r -> LangChain4jGovernanceTest.text("{\"name\":\"value\",\"count\":1}"));
        try (var control =
                AgentControl.builder(NativeIntegrationTest.policy().build())
                        .audit(
                                r -> {
                                    if (r.point() == InterventionPoint.AGENT_SHUTDOWN)
                                        throw new IllegalStateException("audit unavailable");
                                })
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    MonoService.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.chatModel(model));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            service.chat("hello")
                                    .doOnNext(v -> seen.incrementAndGet())
                                    .block(Duration.ofSeconds(5)));
            assertEquals(0, seen.get());
        }
    }
}
