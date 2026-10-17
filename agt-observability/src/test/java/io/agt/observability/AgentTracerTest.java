package io.agt.observability;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.audit.*;
import io.agt.core.policy.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTracerTest {
    @TempDir Path directory;

    static final class Capture implements SpanProcessor {
        final List<SpanData> spans = new CopyOnWriteArrayList<>();

        public void onStart(Context parent, ReadWriteSpan span) {}

        public boolean isStartRequired() {
            return false;
        }

        public void onEnd(ReadableSpan span) {
            spans.add(span.toSpanData());
        }

        public boolean isEndRequired() {
            return true;
        }
    }

    static OpenTelemetrySdk telemetry(SdkTracerProvider provider) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @Test
    void parallelChildrenKeepParentAndIndependentRunsWithoutThreadLeaks() throws Exception {
        var capture = new Capture();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(capture).build()) {
            var tracer = new AgentTracer(telemetry(provider));
            var pool = Executors.newFixedThreadPool(2);
            var gate = new CyclicBarrier(2);
            try {
                var children =
                        tracer.invokeAgent(
                                "parent",
                                () -> {
                                    var parent = tracer.currentExecution();
                                    var a =
                                            pool.submit(
                                                    tracer.wrap(
                                                            (Callable<ExecutionContext>)
                                                                    () ->
                                                                            tracer.invokeAgent(
                                                                                    "worker",
                                                                                    "shared-instance",
                                                                                    () -> {
                                                                                        try {
                                                                                            gate
                                                                                                    .await(
                                                                                                            3,
                                                                                                            TimeUnit
                                                                                                                    .SECONDS);
                                                                                        } catch (
                                                                                                Exception
                                                                                                        error) {
                                                                                            throw new RuntimeException(
                                                                                                    error);
                                                                                        }
                                                                                        assertEquals(
                                                                                                parent
                                                                                                        .runId(),
                                                                                                tracer.currentExecution()
                                                                                                        .parentRunId());
                                                                                        return tracer
                                                                                                .currentExecution();
                                                                                    })));
                                    var b =
                                            pool.submit(
                                                    tracer.wrap(
                                                            (Callable<ExecutionContext>)
                                                                    () ->
                                                                            tracer.invokeAgent(
                                                                                    "worker",
                                                                                    "shared-instance",
                                                                                    () -> {
                                                                                        try {
                                                                                            gate
                                                                                                    .await(
                                                                                                            3,
                                                                                                            TimeUnit
                                                                                                                    .SECONDS);
                                                                                        } catch (
                                                                                                Exception
                                                                                                        error) {
                                                                                            throw new RuntimeException(
                                                                                                    error);
                                                                                        }
                                                                                        return tracer
                                                                                                .currentExecution();
                                                                                    })));
                                    try {
                                        return List.of(parent, a.get(), b.get());
                                    } catch (Exception error) {
                                        throw new RuntimeException(error);
                                    }
                                });
                assertEquals(children.get(0).traceId(), children.get(1).traceId());
                assertEquals(children.get(0).traceId(), children.get(2).traceId());
                assertNotEquals(children.get(1).runId(), children.get(2).runId());
                assertEquals(children.get(0).spanId(), children.get(2).parentSpanId());
                assertEquals(children.get(1).agentInstanceId(), children.get(2).agentInstanceId());
                assertTrue(pool.submit(() -> !Span.current().getSpanContext().isValid()).get());
                assertThrows(IllegalStateException.class, tracer::currentExecution);
            } finally {
                pool.shutdownNow();
            }
            assertEquals(3, capture.spans.size());
        }
    }

    @Test
    void toolDenialHasExactSpanAndDoesNotInheritParentPermissions() throws Exception {
        var capture = new Capture();
        var audit = new ArrayList<AuditRecord>();
        var effects = new AtomicInteger();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(capture).build()) {
            var tracer = new AgentTracer(telemetry(provider));
            var policy =
                    StructuredPolicy.builder("v1")
                            .tool(
                                    "send",
                                    new ToolRule(
                                            Set.of("admin"), ParameterRule.object(Map.of()), false))
                            .build();
            try (var control =
                    AgentControl.builder(policy).audit(tracer.correlate(audit::add)).build()) {
                var deniedError =
                        assertThrows(
                                GovernanceException.class,
                                () ->
                                        tracer.invokeAgent(
                                                "parent",
                                                () ->
                                                        tracer.invokeAgent(
                                                                "child",
                                                                () ->
                                                                        tracer.governed(
                                                                                control,
                                                                                new TrustedContext(
                                                                                        "child",
                                                                                        "t",
                                                                                        "u",
                                                                                        Set.of(
                                                                                                "read")),
                                                                                session ->
                                                                                        tracer
                                                                                                .operation(
                                                                                                        "tool",
                                                                                                        "send",
                                                                                                        () ->
                                                                                                                control
                                                                                                                        .runTool(
                                                                                                                                "send",
                                                                                                                                Map
                                                                                                                                        .of(),
                                                                                                                                args -> {
                                                                                                                                    effects
                                                                                                                                            .incrementAndGet();
                                                                                                                                    return "sent";
                                                                                                                                }))))));
                assertEquals("permission_denied", deniedError.code());
            }
            assertEquals(0, effects.get());
            var denied =
                    audit.stream()
                            .filter(
                                    a ->
                                            a.reasonCode() != null
                                                    && a.reasonCode().equals("permission_denied"))
                            .findFirst()
                            .orElseThrow();
            var span =
                    capture.spans.stream()
                            .filter(s -> s.getSpanId().equals(denied.execution().spanId()))
                            .findFirst()
                            .orElseThrow();
            assertEquals("tool send", span.getName());
            assertEquals(3, denied.schemaVersion());
            try (var sink = new JsonlAuditSink(directory.resolve("audit.jsonl"))) {
                sink.write(denied);
            }
            var json =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(Files.readString(directory.resolve("audit.jsonl")));
            assertEquals(
                    denied.execution().traceId(), json.path("execution").path("traceId").asText());
        }
    }

    @Test
    void w3cPropagationJoinsTraceAndRemoteParentWithoutIdentityPropagation() {
        var capture = new Capture();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(capture).build()) {
            var tracer = new AgentTracer(telemetry(provider));
            var carrier = new HashMap<String, String>();
            var root =
                    tracer.invokeAgent(
                            "sender",
                            () -> {
                                tracer.inject(carrier);
                                return tracer.currentExecution();
                            });
            assertTrue(carrier.containsKey("traceparent"));
            assertFalse(carrier.containsKey("permissions"));
            try (var scope = tracer.extract(carrier).makeCurrent()) {
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new AgentTracer(OpenTelemetry.noop())
                                        .invokeAgent("unconfigured", () -> "no span"));
                var remote = tracer.invokeAgent("receiver", tracer::currentExecution);
                assertEquals(root.traceId(), remote.traceId());
                assertEquals(root.runId(), remote.parentRunId());
                assertEquals(root.spanId(), remote.parentSpanId());
            }
            carrier.put("traceparent", "invalid");

            try (var scope = tracer.extract(carrier).makeCurrent()) {
                assertNull(tracer.invokeAgent("new-root", tracer::currentExecution).parentRunId());
                assertNotEquals(
                        root.traceId(),
                        tracer.invokeAgent("new-root", tracer::currentExecution).traceId());
            }
        }
    }

    @Test
    void invalidIdsAndMissingSdkFailExplicitly() {
        var tracer = new AgentTracer(OpenTelemetry.noop());
        assertThrows(
                IllegalStateException.class, () -> tracer.invokeAgent("agent", () -> "result"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ExecutionContext(
                                "0".repeat(32), "1".repeat(16), null, "run", null, "instance"));
    }
}
