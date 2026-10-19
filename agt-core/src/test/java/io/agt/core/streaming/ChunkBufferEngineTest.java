package io.agt.core.streaming;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.policy.ContentRule;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ChunkBufferEngineTest {
    // Deterministic clock: tasks run only when the test advances it.
    static class Clock extends ScheduledThreadPoolExecutor implements AutoCloseable {
        final List<Runnable> tasks = new ArrayList<>();

        Clock() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            var future = new FutureTask<Void>(task, null);
            tasks.add(future);
            return new ScheduledFuture<Object>() {
                public long getDelay(TimeUnit u) {
                    return 0;
                }

                public int compareTo(Delayed d) {
                    return 0;
                }

                public boolean cancel(boolean b) {
                    return future.cancel(b);
                }

                public boolean isCancelled() {
                    return future.isCancelled();
                }

                public boolean isDone() {
                    return future.isDone();
                }

                public Object get() throws InterruptedException, ExecutionException {
                    return future.get();
                }

                public Object get(long n, TimeUnit u)
                        throws InterruptedException, ExecutionException, TimeoutException {
                    return future.get(n, u);
                }
            };
        }

        @Override
        public void close() {
            shutdownNow();
            tasks.clear();
        }

        void tick() {
            var due = List.copyOf(tasks);
            tasks.clear();
            due.forEach(Runnable::run);
        }
    }

    static ContentRule rule(List<String> words) {
        return new ContentRule("local", Set.of(InterventionPoint.POST_MODEL_CALL), words, Map.of());
    }

    static ChunkBufferEngine engine(
            Clock clock,
            List<String> words,
            boolean enforce,
            StringBuilder out,
            List<Throwable> errors,
            List<String> audits) {
        return new ChunkBufferEngine(
                new ChunkBufferOptions(3, "，。！？\n", Duration.ofMillis(50), 4096, Set.of("local")),
                List.of(rule(words)),
                clock,
                enforce,
                (id, start, end, ns) -> {
                    if (id != null) audits.add(id + ":" + start + ":" + end);
                },
                out::append,
                errors::add);
    }

    @Test
    void largeCallbacksRemainBoundedAndConcurrentTerminationIsIdempotent() throws Exception {
        var clock = new ScheduledThreadPoolExecutor(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 40; round++) {
                var errors = new java.util.concurrent.atomic.AtomicInteger();
                var closes = new java.util.concurrent.atomic.AtomicInteger();
                var emitted = new java.util.concurrent.atomic.AtomicInteger();
                var gate =
                        new ChunkBufferEngine(
                                new ChunkBufferOptions(
                                        20, "", Duration.ofMillis(1), 64, Set.of("local")),
                                List.of(rule(List.of("ABCDEF"))),
                                clock,
                                true,
                                (r, a, b, n) -> {
                                    assertTrue(b - a <= 26);
                                },
                                t -> emitted.addAndGet(t.length()),
                                e -> errors.incrementAndGet(),
                                workers,
                                closes::incrementAndGet);
                var start = new CountDownLatch(1);
                var first =
                        workers.submit(
                                () -> {
                                    try {
                                        start.await();
                                        gate.accept("safe ".repeat(1000));
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                var second =
                        workers.submit(
                                () -> {
                                    try {
                                        start.await();
                                        gate.close();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                start.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                int before = emitted.get();
                gate.accept("late");
                gate.close();
                assertEquals(before, emitted.get());
                assertEquals(1, closes.get());
                assertTrue(errors.get() <= 1);
                assertEquals(0, gate.bufferedCharacters());
                assertFalse(gate.hasScheduledCheck());
            }
        } finally {
            workers.shutdownNow();
            clock.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidConfigurationAndVocabularyBounds() {
        assertThrows(IllegalArgumentException.class, () -> ChunkBufferOptions.defaults(Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkBufferOptions(0, "", Duration.ofMillis(1), 64, Set.of("local")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkBufferOptions(2, "", Duration.ZERO, 64, Set.of("local")));
        try (var clock = new Clock()) {
            for (var words :
                    List.of(
                            List.of("x".repeat(2049)),
                            Collections.nCopies(257, "x"),
                            Collections.nCopies(33, "x".repeat(2048)))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                engine(
                                        clock,
                                        words,
                                        true,
                                        new StringBuilder(),
                                        new ArrayList<>(),
                                        new ArrayList<>()));
            }
        }
    }

    @Test
    void everySplitAndTimeoutKeepsWholeMatchPrivate() {
        for (String word :
                List.of("ABCDEF", "A，B。C！？\nDEF", "abcdefghijklmnopqrstuvwxyz", "😀秘密")) {
            for (int split = 0; split <= word.length(); split++) {
                try (var clock = new Clock()) {
                    var out = new StringBuilder();
                    var errors = new ArrayList<Throwable>();
                    try (var gate =
                            engine(
                                    clock,
                                    List.of(word, "BC", "ABC"),
                                    true,
                                    out,
                                    errors,
                                    new ArrayList<>())) {
                        gate.accept("安全文本足够长安全文本足够长" + word.substring(0, split));
                        clock.tick();
                        gate.accept(word.substring(split) + "后续");
                        clock.tick();
                        assertEquals(1, errors.size(), word + "/" + split);
                        assertTrue("安全文本足够长安全文本足够长".startsWith(out.toString()));
                        assertEquals(
                                "stream_content_denied",
                                ((GovernanceException) errors.get(0)).code());
                        gate.accept("late");
                        assertEquals(0, gate.bufferedCharacters());
                        assertFalse(gate.hasScheduledCheck());
                    }
                }
            }
        }
    }

    @Test
    void safeStreamIsIncrementalAndSurrogatePairsStayTogether() {
        try (var clock = new Clock()) {
            var out = new StringBuilder();
            var errors = new ArrayList<Throwable>();
            var parts = new ArrayList<String>();
            try (var gate =
                    new ChunkBufferEngine(
                            ChunkBufferOptions.defaults(Set.of("local")),
                            List.of(rule(List.of("ABCDEF"))),
                            clock,
                            true,
                            (r, a, b, n) -> {},
                            parts::add,
                            errors::add)) {
                String text = "你好，abcdefghijk😀结束";
                for (int i = 0; i < text.length(); i++) {
                    gate.accept(text.substring(i, i + 1));
                    clock.tick();
                }
                assertFalse(parts.isEmpty());
                assertTrue(
                        parts.stream()
                                .noneMatch(
                                        p -> Character.isHighSurrogate(p.charAt(p.length() - 1))));
                gate.seal(text);
                gate.complete(text);
                parts.forEach(out::append);
                assertEquals(text, out.toString());
                assertTrue(errors.isEmpty());
                assertEquals(0, gate.bufferedCharacters());
            }
        }
    }

    @Test
    void timeoutChecksOnceAndNeverReleasesPendingPrefix() {
        try (var clock = new Clock()) {
            var out = new StringBuilder();
            var errors = new ArrayList<Throwable>();
            try (var gate =
                    engine(clock, List.of("ABCDEF"), true, out, errors, new ArrayList<>())) {
                gate.accept("ABC");
                clock.tick();
                clock.tick();
                assertEquals("", out.toString());
                assertFalse(gate.hasScheduledCheck());
                gate.close();
                gate.accept("DEF");
                clock.tick();
                assertEquals("", out.toString());
                assertEquals(0, gate.bufferedCharacters());
            }
        }
    }

    @Test
    void evaluateOnlyRecordsEachOccurrenceOnceAcrossOverlapRescans() {
        try (var clock = new Clock()) {
            var out = new StringBuilder();
            var errors = new ArrayList<Throwable>();
            var audits = new ArrayList<String>();
            try (var gate = engine(clock, List.of("ABCDEF", "BC"), false, out, errors, audits)) {
                String text = "ABCDEFabcdefghijkl";
                for (char c : text.toCharArray()) {
                    gate.accept(String.valueOf(c));
                    clock.tick();
                }
                gate.seal(text);
                gate.complete(text);
                assertEquals(text, out.toString());
                assertEquals(2, audits.size());
                assertTrue(errors.isEmpty());
            }
        }
    }

    @Test
    void shortResponseMismatchAuditFailureAndCapacityFailClosed() {
        try (var clock = new Clock()) {
            var out = new StringBuilder();
            var errors = new ArrayList<Throwable>();
            try (var gate =
                    engine(clock, List.of("ABCDEF"), true, out, errors, new ArrayList<>())) {
                gate.accept("hi");
                gate.seal("hi");
                gate.complete("hi");
                assertEquals("hi", out.toString());
            }
            out.setLength(0);
            try (var gate =
                    engine(clock, List.of("ABCDEF"), true, out, errors, new ArrayList<>())) {
                gate.accept("ABC");
                assertThrows(GovernanceException.class, () -> gate.seal("changed"));
                assertEquals("", out.toString());
                assertEquals(0, gate.bufferedCharacters());
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new ChunkBufferEngine(
                                    new ChunkBufferOptions(
                                            3, "", Duration.ofMillis(1), 4, Set.of("local")),
                                    List.of(rule(List.of("ABCDEF"))),
                                    clock,
                                    true,
                                    (r, a, b, n) -> {},
                                    out::append,
                                    errors::add));
            try (var gate =
                    new ChunkBufferEngine(
                            ChunkBufferOptions.defaults(Set.of("local")),
                            List.of(rule(List.of("X"))),
                            clock,
                            true,
                            (r, a, b, n) -> {
                                throw new GovernanceException("audit_failed");
                            },
                            out::append,
                            errors::add)) {
                gate.accept("safe。");
                assertEquals("", out.toString());
                assertEquals(
                        "audit_failed",
                        ((GovernanceException) errors.get(errors.size() - 1)).code());
            }
        }
    }
}
