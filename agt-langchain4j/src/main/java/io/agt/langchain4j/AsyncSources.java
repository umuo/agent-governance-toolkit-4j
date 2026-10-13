package io.agt.langchain4j;

import dev.langchain4j.exception.AsyncNotSupportedException;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 异步数据源适配与工具类。
 *
 * <p>异步组合显式传播取消，避免 thenApply/thenCompose 的衍生 future 丢失源取消。
 */
final class AsyncSources {
    private AsyncSources() {}

    static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    static <T> CompletableFuture<T> normalize(CompletableFuture<T> source, String code) {
        var result =
                new CompletableFuture<T>() {
                    @Override
                    public boolean cancel(boolean interrupt) {
                        boolean changed = super.cancel(interrupt);
                        if (changed) {
                            source.cancel(interrupt);
                        }
                        return changed;
                    }
                };
        source.whenComplete(
                (value, error) -> {
                    if (error == null) {
                        result.complete(value);
                    } else {
                        Throwable cause = unwrap(error);
                        result.completeExceptionally(
                                cause instanceof GovernanceException
                                        ? cause
                                        : new GovernanceException(
                                                cause instanceof AsyncNotSupportedException
                                                        ? code + "_async_unsupported"
                                                        : code + "_failed"));
                    }
                });
        return result;
    }

    static <T> CompletableFuture<T> fallback(
            HostSession session, CompletableFuture<T> source, Supplier<T> blocking) {
        var fallback = new AtomicReference<CompletableFuture<T>>();
        var result =
                new CompletableFuture<T>() {
                    @Override
                    public boolean cancel(boolean interrupt) {
                        boolean changed = super.cancel(interrupt);
                        if (changed) {
                            source.cancel(interrupt);
                            var next = fallback.get();
                            if (next != null) {
                                next.cancel(interrupt);
                            }
                        }
                        return changed;
                    }
                };
        source.whenComplete(
                (value, error) -> {
                    if (result.isDone()) {
                        return;
                    }
                    if (error == null) {
                        result.complete(value);
                        return;
                    }
                    if (!(unwrap(error) instanceof AsyncNotSupportedException)) {
                        result.completeExceptionally(unwrap(error));
                        return;
                    }
                    CompletableFuture<T> next =
                            session.supplyAsync(
                                    () -> CompletableFuture.completedFuture(blocking.get()));
                    fallback.set(next);
                    if (result.isCancelled()) {
                        next.cancel(true);
                        return;
                    }
                    next.whenComplete(
                            (v, e) -> {
                                if (e == null) {
                                    result.complete(v);
                                } else {
                                    result.completeExceptionally(unwrap(e));
                                }
                            });
                });
        return result;
    }
}
