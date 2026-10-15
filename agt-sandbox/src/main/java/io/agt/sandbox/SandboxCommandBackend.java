package io.agt.sandbox;

import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.execution.CommandBackend;
import io.agt.core.execution.CommandExecution;
import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 已发布 sandbox SDK 的有界固定命令后端。
 *
 * <p>不暴露原文回调，不实施普通进程回退。取消和 SDK 超时后隔离此实例，不能假定回收已确认。
 */
public final class SandboxCommandBackend implements CommandBackend, AutoCloseable {
    private final SandboxClient client;
    private final SandboxPolicy policy;
    private final Semaphore capacity;
    private final ThreadPoolExecutor workers;
    private final Set<Job> jobs = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile boolean quarantined;

    private SandboxCommandBackend(
            SandboxClient client, SandboxPolicy policy, boolean hostReads, int maxConcurrent) {
        this.client = Objects.requireNonNull(client);
        this.policy = Objects.requireNonNull(policy);
        if (policy.timeout().toMillis() < 1) {
            throw new GovernanceException("sandbox_limits_invalid");
        }
        if (maxConcurrent < 1 || maxConcurrent > 32) {
            throw new IllegalArgumentException("Invalid sandbox capacity");
        }
        if (!hostReads && policy.readPolicy() != ReadPolicy.DECLARED_ONLY) {
            throw new GovernanceException("sandbox_host_read_requires_opt_in");
        }
        if (policy.allowPathSearch()) {
            throw new GovernanceException("sandbox_path_search_denied");
        }
        checkCapabilities();
        capacity = new Semaphore(maxConcurrent);
        workers =
                new ThreadPoolExecutor(
                        0,
                        maxConcurrent,
                        30,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        task -> {
                            var thread = new Thread(task, "agt-sandbox-worker");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    /** 严格读取入口；SDK 实际 profile 不可用时拒绝，没有读取模式回退。 */
    public static SandboxCommandBackend strict(SandboxClient client, SandboxPolicy policy) {
        return strict(client, policy, 2);
    }

    public static SandboxCommandBackend strict(
            SandboxClient client, SandboxPolicy policy, int maxConcurrent) {
        return new SandboxCommandBackend(client, policy, false, maxConcurrent);
    }

    /**
     * 宿主显式允许 HOST 读取的入口。
     *
     * <p>只适用于经审核且不要求读取机密隔离的固定工具；网络权限仍严格按提供的策略执行。
     */
    public static SandboxCommandBackend allowHostReads(SandboxClient client, SandboxPolicy policy) {
        return allowHostReads(client, policy, 2);
    }

    public static SandboxCommandBackend allowHostReads(
            SandboxClient client, SandboxPolicy policy, int maxConcurrent) {
        return new SandboxCommandBackend(client, policy, true, maxConcurrent);
    }

    private void checkCapabilities() {
        var capabilities = client.capabilities();
        if (!capabilities.supports(policy.readPolicy())
                || !capabilities.supports(policy.networkPolicy())
                || !capabilities.supports(policy.deletionPolicy())) {
            throw new GovernanceException("sandbox_policy_unsupported");
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new GovernanceException("sandbox_backend_closed");
        }
        if (quarantined) {
            throw new GovernanceException("sandbox_cleanup_unconfirmed");
        }
    }

    @Override
    public void prepare(CommandExecution execution) {
        checkOpen();
        checkCapabilities();
        Path directory;
        try {
            directory = policy.workingDirectory().toRealPath();
        } catch (Exception error) {
            throw new GovernanceException("sandbox_binding_invalid");
        }
        if (!directory.equals(execution.directory())) {
            throw new GovernanceException("sandbox_binding_mismatch");
        }
        request(execution);
    }

    private SandboxPolicy effectivePolicy(CommandExecution execution) {
        var builder =
                SandboxPolicy.builder(execution.directory())
                        .readPolicy(policy.readPolicy())
                        .network(policy.networkPolicy())
                        .deletion(policy.deletionPolicy())
                        .allowPathSearch(false)
                        .timeout(
                                Duration.ofMillis(
                                        Math.min(
                                                policy.timeout().toMillis(),
                                                execution.limits().timeoutMillis())))
                        .maxOutputBytes(
                                Math.min(
                                        policy.maxOutputBytes(),
                                        execution.limits().maxOutputBytes()));
        if (!policy.writableRoots().contains(policy.workingDirectory())) {
            builder.readOnlyWorkingDirectory();
        }
        policy.readableRoots().stream()
                .filter(
                        root ->
                                !root.equals(policy.workingDirectory())
                                        && !policy.writableRoots().contains(root))
                .distinct()
                .forEach(builder::readableRoot);
        policy.writableRoots().stream()
                .filter(root -> !root.equals(policy.workingDirectory()))
                .distinct()
                .forEach(builder::writableRoot);
        policy.protectedPaths().forEach(builder::protect);
        return builder.build();
    }

    private SandboxRequest request(CommandExecution execution) {
        try {
            return SandboxRequest.of(
                    effectivePolicy(execution),
                    new CommandSpec(execution.executable().toString(), execution.arguments()));
        } catch (RuntimeException error) {
            throw new GovernanceException("sandbox_request_invalid");
        }
    }

    @Override
    public String execute(CommandExecution execution, HostSession session) {
        return execute(execution, session, ignored -> {});
    }

    /** 单次执行的退出码诊断；只在非零退出时回调，不发布未检查的 stdout/stderr。 */
    public String execute(
            CommandExecution execution,
            HostSession session,
            java.util.function.IntConsumer nonZeroExit) {
        return execute(execution, session, nonZeroExit, false);
    }

    /** 返回结构化退出码与 stdout/stderr；调用方必须通过工具结果治理后才能交付这些内容。 */
    public String executeReport(CommandExecution execution, HostSession session) {
        return execute(execution, session, ignored -> {}, true);
    }

    private String execute(
            CommandExecution execution,
            HostSession session,
            java.util.function.IntConsumer nonZeroExit,
            boolean report) {
        Objects.requireNonNull(nonZeroExit);
        prepare(execution);
        session.checkActive();
        var request = request(execution);
        var job = new Job(request, execution, session, nonZeroExit, report);
        long timeout = Math.min(policy.timeout().toMillis(), execution.limits().timeoutMillis());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        synchronized (this) {
            checkOpen();
            if (!capacity.tryAcquire()) {
                throw new GovernanceException("sandbox_capacity_exceeded");
            }
            jobs.add(job);
            try {
                workers.execute(job);
            } catch (RejectedExecutionException error) {
                jobs.remove(job);
                capacity.release();
                throw new GovernanceException("sandbox_capacity_exceeded");
            }
        }
        try {
            while (true) {
                if (closed) {
                    throw new GovernanceException("sandbox_backend_closed");
                }
                session.checkActive();
                if (Thread.currentThread().isInterrupted()) {
                    throw new GovernanceException("sandbox_cancelled");
                }
                if (System.nanoTime() >= deadline) {
                    throw new GovernanceException("sandbox_timeout");
                }
                try {
                    String output = job.result.get(10, TimeUnit.MILLISECONDS);
                    checkOpen();
                    session.checkActive();
                    return output;
                } catch (TimeoutException pending) {
                    // 有限轮询期间仍检查会话/后端状态，不依赖 SDK 响应中断。
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            job.cancel();
            throw new GovernanceException("sandbox_cancelled");
        } catch (ExecutionException error) {
            throw error.getCause() instanceof GovernanceException governance
                    ? governance
                    : new GovernanceException("sandbox_execution_failed");
        } catch (RuntimeException error) {
            job.cancel();
            throw error;
        }
    }

    private String text(
            SandboxResult result,
            CommandExecution execution,
            java.util.function.IntConsumer nonZeroExit,
            boolean report) {
        if (result == null) {
            throw new GovernanceException("sandbox_invalid_result");
        }
        if (result.timedOut()) {
            quarantined = true;
            throw new GovernanceException("sandbox_timeout");
        }
        if (result.stdoutTruncated() || result.stderrTruncated()) {
            throw new GovernanceException("sandbox_output_limit");
        }
        if (result.exitCode() != 0 && !report) {
            nonZeroExit.accept(result.exitCode());
            throw new GovernanceException("sandbox_exit_denied");
        }
        byte[] stdout = result.stdout();
        byte[] stderr = result.stderr();
        int separator = stdout.length > 0 && stderr.length > 0 ? 1 : 0;
        long size = (long) stdout.length + stderr.length + separator;
        if (size > Math.min(policy.maxOutputBytes(), execution.limits().maxOutputBytes())) {
            throw new GovernanceException("sandbox_output_limit");
        }
        try {
            String first =
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(stdout)).toString();
            String second =
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(stderr)).toString();
            if (report) {
                return io.agt.core.JsonValues.write(
                        java.util.Map.of(
                                "exitCode", result.exitCode(), "stdout", first, "stderr", second));
            }
            return first + (separator == 0 ? "" : "\n") + second;
        } catch (CharacterCodingException error) {
            throw new GovernanceException("sandbox_output_encoding_invalid");
        }
    }

    private final class Job implements Runnable {
        private final boolean report;
        private final java.util.function.IntConsumer nonZeroExit;
        private final SandboxRequest request;
        private final CommandExecution execution;
        private final HostSession session;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private boolean cancelled;
        private Thread thread;
        private boolean started;

        private Job(
                SandboxRequest request,
                CommandExecution execution,
                HostSession session,
                java.util.function.IntConsumer nonZeroExit,
                boolean report) {
            this.report = report;
            this.request = request;
            this.execution = execution;
            this.session = session;
            this.nonZeroExit = nonZeroExit;
        }

        synchronized void cancel() {
            cancelled = true;
            if (started) {
                quarantined = true;
            }
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            String output = null;
            GovernanceException failure = null;
            try {
                synchronized (this) {
                    thread = Thread.currentThread();
                    if (cancelled || closed || quarantined) {
                        throw new GovernanceException("sandbox_cancelled");
                    }
                    session.checkActive();
                    started = true;
                }
                output = text(client.execute(request), execution, nonZeroExit, report);
            } catch (SandboxBackendUnavailableException error) {
                failure = new GovernanceException("sandbox_unavailable");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                quarantined = true;
                failure = new GovernanceException("sandbox_cancelled");
            } catch (GovernanceException error) {
                failure = error;
            } catch (Throwable error) {
                quarantined = true;
                failure = new GovernanceException("sandbox_execution_failed");
            } finally {
                synchronized (this) {
                    // 池线程可能立即用于另一任务；晚到取消不能中断下一次执行。
                    thread = null;
                }
                jobs.remove(this);
                capacity.release();
            }
            if (failure != null) {
                result.completeExceptionally(failure);
            } else {
                result.complete(output);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            jobs.forEach(Job::cancel);
            workers.shutdownNow();
        }
    }
}
