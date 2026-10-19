package io.agt.core.streaming;

import io.agt.core.GovernanceException;
import io.agt.core.policy.ContentRule;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Per-model-round bounded literal window. All callbacks are serialized with state transitions. A
 * timer only checks pending text; it never forces an undecided suffix out.
 */
public final class ChunkBufferEngine implements AutoCloseable {
    @FunctionalInterface
    public interface Audit {
        /** Called before release. No text is supplied. Null rule means an allowed batch. */
        void checked(String ruleId, long offset, long endOffset, long durationNanos);
    }

    private final ChunkBufferOptions options;
    private final List<ContentRule> rules;
    private final ScheduledExecutorService scheduler;
    private final Executor timerExecutor;
    private final Consumer<String> output;
    private final Consumer<Throwable> failure;
    private final Audit audit;
    private final boolean enforce;
    private final Runnable onClose;
    private final int overlap;
    private final MessageDigest digest = digest();
    private StringBuilder pending = new StringBuilder();
    private ScheduledFuture<?> timer;
    private long timerGeneration;
    private long received;
    private long released;
    private long checkedThrough;
    private int unchecked;
    private boolean sealed;
    private boolean closed;
    private byte[] expectedDigest;

    public ChunkBufferEngine(
            ChunkBufferOptions options,
            List<ContentRule> rules,
            ScheduledExecutorService scheduler,
            boolean enforce,
            Audit audit,
            Consumer<String> output,
            Consumer<Throwable> failure) {
        this(options, rules, scheduler, enforce, audit, output, failure, Runnable::run);
    }

    public ChunkBufferEngine(
            ChunkBufferOptions options,
            List<ContentRule> rules,
            ScheduledExecutorService scheduler,
            boolean enforce,
            Audit audit,
            Consumer<String> output,
            Consumer<Throwable> failure,
            Executor timerExecutor) {
        this(options, rules, scheduler, enforce, audit, output, failure, timerExecutor, () -> {});
    }

    public ChunkBufferEngine(
            ChunkBufferOptions options,
            List<ContentRule> rules,
            ScheduledExecutorService scheduler,
            boolean enforce,
            Audit audit,
            Consumer<String> output,
            Consumer<Throwable> failure,
            Executor timerExecutor,
            Runnable onClose) {
        this.onClose = Objects.requireNonNull(onClose);
        this.timerExecutor = Objects.requireNonNull(timerExecutor);
        this.options = Objects.requireNonNull(options);
        this.rules = List.copyOf(rules);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.enforce = enforce;
        this.audit = Objects.requireNonNull(audit);
        this.output = Objects.requireNonNull(output);
        this.failure = Objects.requireNonNull(failure);
        int count = 0;
        int total = 0;
        int longest = 0;
        if (rules.isEmpty() || rules.size() > 256)
            throw new IllegalArgumentException("empty local rules");
        for (ContentRule rule : rules) {
            if (!options.localRules().contains(rule.id())
                    || rule.denyContains().isEmpty()
                    || !rule.replacements().isEmpty())
                throw new IllegalArgumentException("non-local rule");
            for (String word : rule.denyContains()) {
                count++;
                total += word.length();
                longest = Math.max(longest, word.length());
                if (word.isEmpty() || word.length() > 2048 || count > 256 || total > 65536)
                    throw new IllegalArgumentException("local vocabulary capacity exceeded");
            }
        }
        if (rules.stream().map(ContentRule::id).distinct().count() != options.localRules().size()
                || longest + options.chunkCharacters() > options.maxBufferedCharacters())
            throw new IllegalArgumentException("insufficient guarded buffer or rule binding");
        overlap = longest - 1;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    // Hash raw UTF-16 units, including surrogate pairs split across callbacks.
    private static void update(MessageDigest hash, char c) {
        hash.update((byte) (c >>> 8));
        hash.update((byte) c);
    }

    public synchronized void accept(String text) {
        if (closed || sealed) return;
        try {
            Objects.requireNonNull(text);
            for (int i = 0; i < text.length() && !closed; i++) {
                char c = text.charAt(i);
                if (pending.length() >= options.maxBufferedCharacters())
                    throw new GovernanceException("stream_capacity_exceeded");
                pending.append(c);
                update(digest, c);
                received++;
                unchecked++;
                if (unchecked >= options.chunkCharacters() || options.boundaries().indexOf(c) >= 0)
                    check(false);
                if (!closed && unchecked > 0 && timer == null) {
                    long generation = ++timerGeneration;
                    timer =
                            scheduler.schedule(
                                    () -> dispatchTimeout(generation),
                                    options.maxWait().toNanos(),
                                    TimeUnit.NANOSECONDS);
                }
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void dispatchTimeout(long generation) {
        try {
            timerExecutor.execute(() -> timeout(generation));
        } catch (RejectedExecutionException error) {
            synchronized (this) {
                if (generation == timerGeneration && !closed)
                    fail(new GovernanceException("async_capacity_exceeded"));
            }
        }
    }

    private synchronized void timeout(long generation) {
        if (generation != timerGeneration) return;
        timer = null;
        if (closed || sealed || unchecked == 0) return;
        try {
            check(false);
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void cancelTimer() {
        timerGeneration++;
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    private void check(boolean finishing) {
        cancelTimer();
        long started = System.nanoTime();
        String window = pending.toString();
        for (ContentRule rule : rules) {
            for (String word : rule.denyContains()) {
                int at = window.indexOf(word);
                while (at >= 0) {
                    long end = released + at + word.length();
                    if (end > checkedThrough) {
                        audit.checked(rule.id(), released + at, end, System.nanoTime() - started);
                        if (closed) return;
                        if (enforce) throw new GovernanceException("stream_content_denied");
                    }
                    at = window.indexOf(word, at + 1);
                }
            }
        }
        checkedThrough = received;
        unchecked = 0;
        audit.checked(null, released, received, System.nanoTime() - started);
        if (closed) return;
        int available = finishing ? pending.length() : Math.max(0, pending.length() - overlap);
        if (available > 0
                && available < pending.length()
                && Character.isHighSurrogate(pending.charAt(available - 1))
                && Character.isLowSurrogate(pending.charAt(available))) available--;
        if (available > 0 && Character.isHighSurrogate(pending.charAt(available - 1))) available--;
        if (finishing && available != pending.length())
            throw new GovernanceException("stream_invalid_text");
        if (available > 0) {
            String safe = pending.substring(0, available);
            pending.delete(0, available);
            released += available;
            output.accept(safe);
        }
    }

    /** Freeze provider input, verify authoritative body without retaining a transcript. */
    public synchronized void seal(String text) {
        if (closed) throw new GovernanceException("stream_terminated");
        if (!sealed) {
            sealed = true;
            cancelTimer();
            expectedDigest = digest.digest();
        }
        MessageDigest actual = digest();
        String body = text == null ? "" : text;
        for (int i = 0; i < body.length(); i++) update(actual, body.charAt(i));
        if (body.length() != received || !MessageDigest.isEqual(expectedDigest, actual.digest())) {
            close();
            throw new GovernanceException("stream_text_mismatch");
        }
        if (unchecked > 0) check(false);
    }

    /** Only after the model's full checks; verifies that policy did not rewrite the body. */
    public synchronized void complete(String checkedText) {
        if (closed) throw new GovernanceException("stream_terminated");
        try {
            seal(checkedText);
            check(true);
            close();
        } catch (Throwable error) {
            fail(error);
            throw error;
        }
    }

    private void fail(Throwable error) {
        if (closed) return;
        close();
        failure.accept(
                error instanceof GovernanceException
                        ? error
                        : new GovernanceException("stream_callback_failed"));
    }

    public synchronized int bufferedCharacters() {
        return pending.length();
    }

    public synchronized boolean hasScheduledCheck() {
        return timer != null;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        cancelTimer();
        pending = new StringBuilder();
        expectedDigest = null;
        digest.reset();
        onClose.run();
    }
}
