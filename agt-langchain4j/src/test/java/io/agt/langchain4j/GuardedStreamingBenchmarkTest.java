package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.audit.AuditRecord;
import io.agt.core.policy.*;
import io.agt.core.streaming.ChunkBufferOptions;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Reproducible local workload, explicitly enabled; timings are observations, never CI thresholds.
 */
@EnabledIfSystemProperty(named = "agt.streaming.benchmark", matches = "true")
class GuardedStreamingBenchmarkTest {
    @Test
    void compareDeliveryModes() throws Exception {
        var rows = new ArrayList<Object>();
        var words =
                java.util.stream.IntStream.range(0, 16)
                        .mapToObj(i -> String.format("DENY%04d", i))
                        .toList();
        var policy =
                StructuredPolicy.builder("benchmark")
                        .content(
                                new ContentRule(
                                        "local",
                                        Set.of(InterventionPoint.POST_MODEL_CALL),
                                        words,
                                        Map.of()))
                        .build();
        String text = "safe text 中文 output ".repeat(64);
        for (int slowMs : List.of(0, 1))
            for (var mode : StreamDelivery.values()) {
                var audit = new CopyOnWriteArrayList<AuditRecord>();
                try (var control = AgentControl.builder(policy).audit(audit::add).build()) {
                    var options =
                            new AiServiceOptions(
                                    mode,
                                    StreamingLimits.DEFAULT,
                                    32,
                                    mode == StreamDelivery.GUARDED
                                            ? ChunkBufferOptions.defaults(Set.of("local"))
                                            : null);
                    var service =
                            new LangChain4jGovernance(control, options)
                                    .create(
                                            GuardedStreamingTest.Stream.class,
                                            () -> NativeIntegrationTest.CTX,
                                            b ->
                                                    b.streamingChatModel(
                                                            new StreamingGovernanceTest.Model(
                                                                    (r, h) -> {
                                                                        for (int i = 0;
                                                                                i < text.length();
                                                                                i += 16) {
                                                                            java.util.concurrent
                                                                                    .locks
                                                                                    .LockSupport
                                                                                    .parkNanos(
                                                                                            250_000);
                                                                            h.onPartialResponse(
                                                                                    text.substring(
                                                                                            i,
                                                                                            Math
                                                                                                    .min(
                                                                                                            i
                                                                                                                    + 16,
                                                                                                            text
                                                                                                                    .length())));
                                                                        }
                                                                        h.onCompleteResponse(
                                                                                LangChain4jGovernanceTest
                                                                                        .text(
                                                                                                text));
                                                                    })));
                    long started = System.nanoTime();
                    var first = new AtomicLong();
                    var delivered = new AtomicInteger();
                    var futures = new ArrayList<CompletableFuture<?>>();
                    for (int stream = 0; stream < 2; stream++) {
                        var value = LangChain4jGovernance.buffered(service.chat("hello"));
                        value.onPartialResponse(
                                        t -> {
                                            first.compareAndSet(0, System.nanoTime() - started);
                                            delivered.addAndGet(t.length());
                                            if (slowMs > 0)
                                                java.util.concurrent.locks.LockSupport.parkNanos(
                                                        TimeUnit.MILLISECONDS.toNanos(slowMs));
                                        })
                                .ignoreErrors()
                                .start();
                        futures.add(value.completion());
                    }
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .get(10, TimeUnit.SECONDS);
                    long elapsed = System.nanoTime() - started;
                    assertEquals(text.length() * 2, delivered.get());
                    var checks =
                            audit.stream()
                                    .filter(r -> r.executionOutcome().equals("stream_local"))
                                    .toList();
                    long detectionNanos =
                            checks.stream().mapToLong(AuditRecord::durationNanos).sum();
                    long maxWindow =
                            checks.stream()
                                    .mapToLong(
                                            r -> {
                                                long a =
                                                        Long.parseLong(
                                                                r.warnings()
                                                                        .get(1)
                                                                        .substring(
                                                                                "offset="
                                                                                        .length()));
                                                long b =
                                                        Long.parseLong(
                                                                r.warnings()
                                                                        .get(2)
                                                                        .substring(
                                                                                "endOffset="
                                                                                        .length()));
                                                return b - a;
                                            })
                                    .max()
                                    .orElse(0);
                    rows.add(
                            Map.of(
                                    "mode",
                                    mode.name(),
                                    "consumerDelayMs",
                                    slowMs,
                                    "firstChunkMs",
                                    first.get() / 1_000_000.0,
                                    "elapsedMs",
                                    elapsed / 1_000_000.0,
                                    "charactersPerSecond",
                                    text.length() * 2 * 1_000_000_000.0 / elapsed,
                                    "localChecks",
                                    checks.size(),
                                    "localDetectionMs",
                                    detectionNanos / 1_000_000.0,
                                    "maxLocalWindowCharacters",
                                    maxWindow));
                }
            }
        var result =
                Map.of(
                        "jdk",
                        System.getProperty("java.version"),
                        "concurrentStreams",
                        2,
                        "providerChunkCharacters",
                        16,
                        "providerDelayNanos",
                        250000,
                        "charactersPerStream",
                        text.length(),
                        "vocabularyWords",
                        16,
                        "maxWordLength",
                        8,
                        "results",
                        rows,
                        "limitations",
                        "Single local smoke run; includes scheduler, audit and framework overhead. Not a production SLA or a load test.");
        Files.createDirectories(Path.of("target"));
        Files.writeString(
                Path.of("target/guarded-streaming-benchmark.json"), JsonValues.write(result));
    }
}
