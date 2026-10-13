package io.agt.core;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 一个异步执行的单次终止状态机。
 *
 * <p>检查在 SDK worker 上执行，完成信号携带创建时的宿主会话。
 */
final class AsyncInvocation<E, T> {
    private final HostSession session;
    private final boolean root;
    private final boolean tool;
    private boolean bridge;
    private boolean data;
    private final String name;
    private final String id = UUID.randomUUID().toString();
    private final Object input;
    private final Object lock = new Object();
    private final Function<Object, E> prepare;
    private final Function<E, ? extends CompletionStage<T>> execute;
    private final Function<T, Object> project;
    private final BiFunction<T, Object, T> replace;
    private final long start = System.nanoTime();
    private final Promise result = new Promise();
    private InterventionPoint pre;
    private InterventionPoint post;
    private boolean done;
    private boolean reserved;
    private boolean started;
    private boolean ownsRun;
    private volatile FutureTask<Void> startTask;
    private volatile Thread startThread;
    private volatile CompletableFuture<T> source;

    AsyncInvocation(
            HostSession session,
            boolean root,
            boolean tool,
            String name,
            Object input,
            Function<Object, E> prepare,
            Function<E, ? extends CompletionStage<T>> execute,
            Function<T, Object> project,
            BiFunction<T, Object, T> replace) {
        this.session = session;
        this.root = root;
        this.tool = tool;
        this.name = name;
        this.input = JsonValues.freeze(input);
        this.prepare = prepare;
        this.execute = execute;
        this.project = project;
        this.replace = replace;
        pre =
                root
                        ? InterventionPoint.INPUT
                        : tool ? InterventionPoint.PRE_TOOL_CALL : InterventionPoint.PRE_MODEL_CALL;
        post =
                root
                        ? InterventionPoint.OUTPUT
                        : tool
                                ? InterventionPoint.POST_TOOL_CALL
                                : InterventionPoint.POST_MODEL_CALL;
    }

    AsyncInvocation<E, T> dataAccess() {
        data = true;
        session.registerDataAudit(id, input);
        pre = InterventionPoint.PRE_DATA_ACCESS;
        post = InterventionPoint.POST_DATA_ACCESS;
        return this;
    }

    AsyncInvocation<E, T> bridge() {
        bridge = true;
        return this;
    }

    CompletableFuture<T> start() {
        session.registerAsync(id, this::abort);
        startTask =
                new FutureTask<>(
                        () -> {
                            startThread = Thread.currentThread();
                            try (var scope = session.bind()) {
                                initiate();
                            } finally {
                                startThread = null;
                            }
                            return null;
                        });
        try {
            session.control().workers.execute(startTask);
        } catch (RejectedExecutionException e) {
            failure(new GovernanceException("async_capacity_exceeded"), true);
        }
        return result;
    }

    private void initiate() {
        try {
            synchronized (lock) {
                if (done) {
                    return;
                }
                session.checkActive();
                if (root) {
                    session.enterRun(true);
                    ownsRun = true;
                } else if (!bridge) {
                    if (data) {
                        session.reserveData();
                    } else {
                        session.reserve(tool);
                    }
                    reserved = true;
                }
            }
            Object effective = bridge ? input : session.evaluate(pre, id, name, input);
            E prepared = prepare.apply(effective);
            synchronized (lock) {
                if (done) {
                    return;
                }
                session.checkActive();
                if (!root && !bridge) {
                    if (data) {
                        session.commitData();
                    } else {
                        session.commit(tool);
                    }
                }
                started = true;
            }
            CompletionStage<T> stage = execute.apply(prepared);
            if (stage == null) {
                throw new GovernanceException("missing_async_result");
            }
            CompletableFuture<T> future = stage.toCompletableFuture();
            source = future;
            synchronized (lock) {
                if (done) {
                    future.cancel(true);
                    return;
                }
            }
            session.track(future);
            future.whenComplete(
                    (value, error) -> {
                        session.untrack(future);
                        synchronized (lock) {
                            if (done) {
                                return;
                            }
                        }
                        try {
                            session.control()
                                    .workers
                                    .execute(
                                            () -> {
                                                try (var scope = session.bind()) {
                                                    if (error == null) {
                                                        success(value);
                                                    } else {
                                                        failure(error, true);
                                                    }
                                                }
                                            });
                        } catch (RejectedExecutionException e) {
                            failure(new GovernanceException("async_capacity_exceeded"), true);
                        }
                    });
        } catch (Throwable e) {
            failure(e, true);
        }
    }

    private void success(T value) {
        try {
            synchronized (lock) {
                if (done) {
                    return;
                }
            }
            session.checkActive();
            Object effective =
                    bridge ? null : session.evaluate(post, id, name, project.apply(value));
            T finalValue = replace.apply(value, effective);
            synchronized (lock) {
                if (done) {
                    return;
                }
                session.checkActive();
            }
            if (!bridge) {
                session.execution(
                        post, id, "succeeded", "execution_result", System.nanoTime() - start);
            }
            synchronized (lock) {
                if (done) {
                    return;
                }
                session.checkActive();
                done = true;
            }
            cleanup();
            try (var scope = session.bind()) {
                result.complete(finalValue);
            }
        } catch (Throwable e) {
            // 审计确认发生在 done 后，错误仍必须进入异常 future。
            if (result.isDone()) {
                return;
            }
            boolean settled;
            synchronized (lock) {
                settled = done;
            }
            if (settled) {
                cleanup();
                session.refuse(code(e));
                try (var scope = session.bind()) {
                    result.completeExceptionally(new GovernanceException(code(e)));
                }
                return;
            }
            failure(e, true);
        }
    }

    private void abort(String code) {
        failure(new GovernanceException(code), false);
    }

    private void failure(Throwable error, boolean terminateSession) {
        String code = code(error);
        boolean hadStarted;
        synchronized (lock) {
            if (done) {
                return;
            }
            done = true;
            hadStarted = started;
        }
        cleanup();
        if (terminateSession) {
            session.refuse(code);
        }
        try (var scope = session.bind()) {
            if (error instanceof CancellationException) {
                result.cancelDirect();
            } else {
                result.completeExceptionally(new GovernanceException(code));
            }
        }
        cancelWork();
        if (!bridge) {
            final String reason = code;
            session.control()
                    .auditFailureAsync(
                            () ->
                                    session.execution(
                                            post,
                                            id,
                                            hadStarted
                                                    ? "failed_after_execution"
                                                    : "refused_before_execution",
                                            reason,
                                            System.nanoTime() - start));
        }
    }

    private void cleanup() {
        session.unregisterAsync(id);
        boolean release;
        boolean leave;
        synchronized (lock) {
            release = reserved && !started;
            reserved = false;
            leave = ownsRun;
            ownsRun = false;
        }
        if (release) {
            if (data) {
                session.releaseData();
            } else {
                session.release(tool);
            }
        }
        if (leave) {
            session.leaveRun();
        }
    }

    private void cancelWork() {
        FutureTask<Void> task = startTask;
        if (task != null) {
            task.cancel(Thread.currentThread() != startThread);
            session.control().workers.remove(task);
        }
        CompletableFuture<T> future = source;
        if (future != null) {
            future.cancel(true);
        }
    }

    private static String code(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof GovernanceException g) {
            return g.code();
        }
        if (error instanceof CancellationException) {
            return "operation_cancelled";
        }
        return "async_execution_failed";
    }

    private final class Promise extends CompletableFuture<T> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) {
                return false;
            }
            failure(new CancellationException(), true);
            return isCancelled();
        }

        void cancelDirect() {
            super.cancel(true);
        }
    }
}
