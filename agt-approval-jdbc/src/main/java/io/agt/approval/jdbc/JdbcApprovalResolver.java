package io.agt.approval.jdbc;

import io.agt.core.*;
import io.agt.core.approval.*;
import io.agt.identity.AuthenticatedActor;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 对接核心有界审批等待；关闭/取消仅清理原请求，不恢复或自动重跑原业务。 */
public final class JdbcApprovalResolver implements ApprovalResolver, AutoCloseable {
    private final JdbcApprovalStore store;
    private final AuthenticatedActor requester;
    private final ThreadPoolExecutor workers;
    private final Semaphore slots;
    private final Set<FutureTask<?>> tasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public JdbcApprovalResolver(JdbcApprovalStore store, AuthenticatedActor requester) {
        this.store = Objects.requireNonNull(store);
        this.requester = Objects.requireNonNull(requester);
        int capacity = store.policy().maxWaiters();
        slots = new Semaphore(capacity);
        workers =
                new ThreadPoolExecutor(
                        capacity,
                        capacity,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(capacity),
                        job -> {
                            var thread = new Thread(job, "agt-approval-wait");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public CompletionStage<ApprovalResponse> resolve(ApprovalRequest request) {
        var result = new CompletableFuture<ApprovalResponse>();
        if (closed.get() || !slots.tryAcquire()) {
            result.completeExceptionally(new GovernanceException("approval_resolver_capacity"));
            return result;
        }
        var task =
                new FutureTask<Void>(
                        () -> {
                            ApprovalClaim claim = null;
                            try {
                                if (result.isDone()) return null;
                                claim = store.submit(requester, request);
                                while (!result.isDone() && !closed.get()) {
                                    var response = store.consume(requester, claim);
                                    if (response.isPresent()) {
                                        result.complete(response.get());
                                        return null;
                                    }
                                    Thread.sleep(store.policy().pollMillis());
                                }
                                if (!result.isDone())
                                    result.completeExceptionally(
                                            new GovernanceException("approval_resolver_closed"));
                            } catch (InterruptedException stopped) {
                                Thread.currentThread().interrupt();
                                result.completeExceptionally(
                                        new GovernanceException("approval_wait_cancelled"));
                            } catch (Exception failure) {
                                result.completeExceptionally(
                                        failure instanceof GovernanceException
                                                ? failure
                                                : new GovernanceException(
                                                        "approval_resolver_failed"));
                            } finally {
                                if (claim != null) {
                                    try {
                                        store.cancel(claim);
                                    } catch (RuntimeException unavailable) {
                                        /* 不能恢复旧任务；持久请求仍受原期限约束。 */
                                    }
                                }
                            }
                            return null;
                        }) {
                    @Override
                    protected void done() {
                        tasks.remove(this);
                        workers.remove(this);
                        slots.release();
                        if (isCancelled() && !result.isDone())
                            result.completeExceptionally(
                                    new GovernanceException("approval_resolver_closed"));
                    }
                };
        tasks.add(task);
        result.whenComplete(
                (response, error) -> {
                    if (result.isCancelled()) task.cancel(true);
                });
        try {
            if (closed.get()) throw new RejectedExecutionException();
            workers.execute(task);
        } catch (RejectedExecutionException full) {
            result.completeExceptionally(new GovernanceException("approval_resolver_capacity"));
            task.cancel(true);
        }
        return result;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        List.copyOf(tasks).forEach(task -> task.cancel(true));
        workers.shutdownNow();
    }
}
