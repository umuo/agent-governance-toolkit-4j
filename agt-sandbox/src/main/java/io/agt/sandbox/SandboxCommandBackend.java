package io.agt.sandbox;

import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.execution.CommandBackend;
import io.agt.core.execution.CommandExecution;
import io.agt.core.execution.ExternalExecution;
import io.agt.core.execution.TerminationEvidence;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxCleanupState;
import io.github.sandboxdemo.api.SandboxEnvironment;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxExecution;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.api.SandboxTerminationReason;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    private final SandboxEnvironment environment;
    private final ThreadPoolExecutor sdkWorkers;
    private final Semaphore capacity;
    private final ThreadPoolExecutor workers;
    private final Set<Job> jobs = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile boolean quarantined;

    private SandboxCommandBackend(
            SandboxClient client,
            SandboxPolicy policy,
            boolean hostReads,
            int maxConcurrent,
            SandboxEnvironment environment) {
        this.client = Objects.requireNonNull(client);
        this.policy = Objects.requireNonNull(policy);
        this.environment = environment;
        if (environment != null && !environment.isStrict()) {
            throw new GovernanceException("sandbox_environment_invalid");
        }
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
        workers = pool(maxConcurrent, "agt-sandbox-control");
        sdkWorkers = pool(maxConcurrent, "agt-sandbox-worker");
    }

    private static ThreadPoolExecutor pool(int maxConcurrent, String name) {
        return new ThreadPoolExecutor(
                0,
                maxConcurrent,
                30,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                task -> {
                    var thread = new Thread(task, name);
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
        return new SandboxCommandBackend(client, policy, false, maxConcurrent, null);
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
        return new SandboxCommandBackend(client, policy, true, maxConcurrent, null);
    }

    /** 宿主显式注册严格工具环境；不会继承父进程 PATH。 */
    public static SandboxCommandBackend strict(
            SandboxClient client,
            SandboxPolicy policy,
            SandboxEnvironment environment,
            int maxConcurrent) {
        return new SandboxCommandBackend(
                client, policy, false, maxConcurrent, Objects.requireNonNull(environment));
    }

    /** HOST 读取与严格工具环境分别由宿主选择。 */
    public static SandboxCommandBackend allowHostReads(
            SandboxClient client,
            SandboxPolicy policy,
            SandboxEnvironment environment,
            int maxConcurrent) {
        return new SandboxCommandBackend(
                client, policy, true, maxConcurrent, Objects.requireNonNull(environment));
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
        try {
            client.validate(request(execution));
        } catch (SandboxException error) {
            if (error.code() == io.github.sandboxdemo.api.SandboxErrorCode.CLEANUP_FAILED) {
                quarantined = true;
            }
            throw SandboxErrors.map(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new GovernanceException("sandbox_cancelled");
        }
    }

    private SandboxRequest request(CommandExecution execution) {
        return SandboxRequests.create(policy, execution, environment);
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
        job.external =
                session.registerExternalExecution(
                        "SANDBOX_TASK",
                        () -> {
                            job.signalCancellation();
                            return job.stopped;
                        });
        long timeout = Math.min(policy.timeout().toMillis(), execution.limits().timeoutMillis());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        try {
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
        } catch (RuntimeException refused) {
            job.external.finished(TerminationEvidence.NOT_STARTED);
            throw refused;
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
            session.noteFailureCause("sandbox_cancelled");
            job.cancel();
            throw new GovernanceException("sandbox_cancelled");
        } catch (ExecutionException error) {
            throw error.getCause() instanceof GovernanceException governance
                    ? governance
                    : new GovernanceException("sandbox_execution_failed");
        } catch (RuntimeException error) {
            session.noteFailureCause(
                    error instanceof GovernanceException denied
                            ? denied.code()
                            : "sandbox_execution_failed");
            job.cancel();
            throw error;
        }
    }

    private static boolean known(SandboxCleanupState state) {
        return state == SandboxCleanupState.CONFIRMED || state == SandboxCleanupState.NOT_REQUIRED;
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
        private volatile SandboxExecution handle;
        private ExternalExecution external;
        private final CompletableFuture<TerminationEvidence> stopped = new CompletableFuture<>();

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

        void cancel() {
            external.requestCancellation();
            signalCancellation();
        }

        synchronized void signalCancellation() {
            cancelled = true;
            if (started) {
                quarantined = true;
            }
            if (handle != null) {
                handle.cancel();
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
                    external.started();
                    started = true;
                }
                handle = client.start(request, sdkWorkers);
                synchronized (this) {
                    if (cancelled || closed) {
                        handle.cancel();
                    }
                }
                var completed = handle.await();
                var cleanup = handle.cleanup();
                if (cleanup.streams() == SandboxCleanupState.FAILED
                        || cleanup.temporaryFiles() == SandboxCleanupState.FAILED
                        || cleanup.hostResources() == SandboxCleanupState.FAILED
                        || cleanup.processes() == SandboxCleanupState.FAILED) {
                    quarantined = true;
                    throw new GovernanceException("sandbox_cleanup_unconfirmed");
                }
                if (completed.error().isPresent()) {
                    throw completed.error().get();
                }
                boolean processKnown = known(cleanup.processes());
                boolean macBestEffort =
                        cleanup.processes() == SandboxCleanupState.UNKNOWN
                                && client.capabilities().platform()
                                        == io.github.sandboxdemo.api.SandboxPlatform.MACOS
                                && client.capabilities()
                                                .support(
                                                        io.github.sandboxdemo.api.SandboxCapability
                                                                .PROCESS_TREE_TERMINATION)
                                                .guarantee()
                                        == io.github.sandboxdemo.api.SandboxGuarantee.BEST_EFFORT;
                if (completed.reason() != SandboxTerminationReason.EXITED
                        || !cleanup.workerFinished()
                        || !known(cleanup.streams())
                        || !known(cleanup.temporaryFiles())
                        || !known(cleanup.hostResources())
                        || !(processKnown || macBestEffort)) {
                    quarantined = true;
                    throw new GovernanceException("sandbox_cleanup_unconfirmed");
                }
                // macOS 的进程树回收仍可能 UNKNOWN；正常退出不宣称内核级后代回收。
                // 取消/超时仍保守隔离 backend，不因 completion 已交付自动复用。
                output = text(completed.result().orElse(null), execution, nonZeroExit, report);
            } catch (SandboxException error) {
                failure = SandboxErrors.map(error);
                // 保留超时/取消/未知执行状态的粘滞隔离，不因 SDK 已确认清理而自动解封。
                if (Set.of(
                                        io.github.sandboxdemo.api.SandboxErrorCode.TIMEOUT,
                                        io.github.sandboxdemo.api.SandboxErrorCode.CANCELLED,
                                        io.github.sandboxdemo.api.SandboxErrorCode.CLEANUP_FAILED,
                                        io.github.sandboxdemo.api.SandboxErrorCode.UNKNOWN)
                                .contains(error.code())
                        || handle == null
                        || !handle.cleanup().confirmed()) {
                    quarantined = true;
                }
            } catch (InterruptedException error) {
                if (handle != null) {
                    handle.cancel();
                }
                Thread.currentThread().interrupt();
                quarantined = true;
                failure = new GovernanceException("sandbox_cancelled");
            } catch (GovernanceException error) {
                failure = error;
            } catch (Throwable error) {
                quarantined = true;
                failure = new GovernanceException("sandbox_execution_failed");
            } finally {
                if (handle != null) {
                    // SDK 的 completion 与实际工作结束独立。占住本后端容量直到后台工作真正结束。
                    while (!handle.cleanup().workerFinished()) {
                        java.util.concurrent.locks.LockSupport.parkNanos(
                                TimeUnit.MILLISECONDS.toNanos(10));
                        Thread.interrupted();
                    }
                }
                synchronized (this) {
                    // 池线程可能立即用于另一任务；晚到取消不能中断下一次执行。
                    thread = null;
                }
                var proof =
                        handle == null
                                ? (started
                                        ? TerminationEvidence.UNKNOWN
                                        : TerminationEvidence.NOT_STARTED)
                                : handle.cleanup().confirmed()
                                        ? TerminationEvidence.CONFIRMED
                                        : TerminationEvidence.WORKER_ONLY;
                // 终止证据与业务错误独立；未知清理不能抢先覆盖已经确定的执行失败。
                if (failure != null) {
                    session.noteFailureCause(failure.code());
                }
                external.finished(proof);
                stopped.complete(proof);
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
            sdkWorkers.shutdownNow();
        }
    }
}
