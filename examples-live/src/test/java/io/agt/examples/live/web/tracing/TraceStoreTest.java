package io.agt.examples.live.web.tracing;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.observability.AgentTracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceStoreTest {
    @TempDir Path directory;

    @Test
    void interruptedSnapshotsRemainInterruptedAfterReload() throws Exception {
        var store = new TraceStore(directory);
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(store).build()) {
            var tracer =
                    new AgentTracer(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            tracer.invokeAgent(
                    "agent",
                    () -> {
                        var id = tracer.currentExecution().traceId();
                        try {
                            var restored = new TraceStore(directory).get(id);
                            assertEquals("interrupted", restored.status());
                            assertEquals("interrupted", restored.spans().get(0).status());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        return "done";
                    });
        }
    }

    @Test
    void oldSnapshotWithoutPayloadsLoadsWithoutInventingBodies() throws Exception {
        var store = new TraceStore(directory);
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(store).build()) {
            var tracer =
                    new AgentTracer(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            String id = tracer.invokeAgent("legacy", () -> tracer.currentExecution().traceId());
            store.finish(id, "completed");
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var snapshot = mapper.valueToTree(store.get(id));
            snapshot.path("spans")
                    .forEach(
                            node ->
                                    ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                                            .remove("payloads"));
            mapper.writeValue(directory.resolve(id + ".json").toFile(), snapshot);
            var loaded = new TraceStore(directory).get(id);
            assertEquals("completed", loaded.status());
            assertTrue(loaded.spans().get(0).payloads().isEmpty());
        }
    }

    @Test
    void refusesCorruptSnapshotRatherThanSilentlyLosingHistory() throws Exception {
        Files.writeString(directory.resolve("1".repeat(32) + ".json"), "invalid");
        assertThrows(java.io.IOException.class, () -> new TraceStore(directory));
    }
}
