package io.agt.core.execution;

import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.Identifiers;
import io.agt.core.policy.ParameterRule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 固定受信任程序的有界执行入口。
 *
 * <p>不是操作系统沙箱。宿主负责程序、固定参数与目录的可信性及不可变部署。 必须经治理工具入口使用；原始调用不自动具备策略授权。
 */
public final class FixedCommand {
    private final String name;
    private final Path executable;
    private final String sha256;
    private final Path directory;
    private final List<CommandArgument> arguments;
    private final Map<String, List<String>> choices;
    private final CommandLimits limits;
    private final Semaphore capacity;
    private final CommandBackend backend;
    private volatile boolean quarantined;

    public FixedCommand(
            String name,
            Path executable,
            String sha256,
            Path directory,
            List<CommandArgument> arguments,
            CommandLimits limits) {
        this(name, executable, sha256, directory, arguments, limits, null, true);
    }

    /** 可选受隔离后端；未指定时保留原有受信任本机进程执行语义。 */
    public FixedCommand(
            String name,
            Path executable,
            String sha256,
            Path directory,
            List<CommandArgument> arguments,
            CommandLimits limits,
            CommandBackend backend) {
        this(name, executable, sha256, directory, arguments, limits, backend, false);
    }

    private FixedCommand(
            String name,
            Path executable,
            String sha256,
            Path directory,
            List<CommandArgument> arguments,
            CommandLimits limits,
            CommandBackend backend,
            boolean localExecution) {
        if (!localExecution && backend == null) {
            throw new GovernanceException("command_backend_required");
        }
        this.name = Identifiers.require(name);
        this.executable = canonical(executable, false);
        this.directory = canonical(directory, true);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected trusted SHA-256");
        }
        this.sha256 = sha256;
        this.arguments = List.copyOf(arguments);
        if (arguments.size() > 128) {
            throw new IllegalArgumentException("Too many command arguments");
        }
        var values = new LinkedHashMap<String, List<String>>();
        for (var argument : arguments) {
            if (argument.parameter() != null
                    && values.putIfAbsent(argument.parameter(), argument.choices()) != null) {
                throw new IllegalArgumentException("Duplicate command parameter");
            }
        }
        choices = Map.copyOf(values);
        this.limits = Objects.requireNonNull(limits);
        capacity = new Semaphore(limits.maxConcurrent());
        this.backend = backend;
        verify();
    }

    private CommandExecution plan(Map<String, Object> values) {
        var argv = new ArrayList<String>();
        for (var argument : arguments) {
            argv.add(
                    argument.literal() != null
                            ? argument.literal()
                            : (String) values.get(argument.parameter()));
        }
        return new CommandExecution(executable, directory, argv, limits);
    }

    public String name() {
        return name;
    }

    public Map<String, List<String>> choices() {
        return choices;
    }

    /** 返回可复用的闭合工具参数规则；策略仍需显式声明权限与审批。 */
    public ParameterRule parameters() {
        var properties = new LinkedHashMap<String, ParameterRule>();
        choices.forEach(
                (key, values) ->
                        properties.put(
                                key,
                                new ParameterRule(
                                        ParameterRule.Type.STRING,
                                        null,
                                        null,
                                        new ArrayList<Object>(values),
                                        null,
                                        null,
                                        null,
                                        4096,
                                        null,
                                        null)));
        return ParameterRule.object(properties, choices.keySet().toArray(String[]::new));
    }

    /** 在实际执行之前检查改写后的参数和程序摘要，不产生进程副作用。 */
    public void prepare(Map<String, Object> values) {
        if (!values.keySet().equals(choices.keySet())) {
            throw new GovernanceException("command_arguments_denied");
        }
        choices.forEach(
                (key, allowed) -> {
                    if (!(values.get(key) instanceof String text) || !allowed.contains(text)) {
                        throw new GovernanceException("command_arguments_denied");
                    }
                });
        verify();
        if (backend != null) {
            backend.prepare(plan(values));
        }
    }

    /**
     * 执行已通过策略与模板校验的参数。
     *
     * <p>不隐式插入 shell 或提供 stdin；可选后端的环境与清理保证由该后端声明。
     */
    public String execute(Map<String, Object> values, HostSession session) {
        prepare(values);
        session.checkActive();
        if (quarantined) throw new GovernanceException("command_cleanup_unconfirmed");
        if (!capacity.tryAcquire()) {
            throw new GovernanceException("command_capacity_exceeded");
        }
        Process process = null;
        Thread reader = null;
        var lifecycle = new LocalProcessLifecycle();
        ExternalExecution registered = null;
        long start = System.nanoTime();
        try {
            if (backend != null) {
                return backend.execute(plan(values), session);
            }
            registered = session.registerExternalExecution("DIRECT_PROCESS", lifecycle::cancel);
            var argv = new ArrayList<String>();
            argv.add(executable.toString());
            for (var argument : arguments) {
                argv.add(
                        argument.literal() != null
                                ? argument.literal()
                                : (String) values.get(argument.parameter()));
            }
            var builder =
                    new ProcessBuilder(argv)
                            .directory(directory.toFile())
                            .redirectErrorStream(true);
            builder.environment().clear();
            session.checkActive();
            registered.started();
            lifecycle.attempted = true;
            process = builder.start();
            lifecycle.attach(process);
            process.getOutputStream().close();
            Process started = process;
            var output = new CompletableFuture<String>();
            lifecycle.readerStarted();
            reader =
                    new Thread(
                            () -> {
                                try {
                                    collect(started, output);
                                } finally {
                                    lifecycle.readerStopped();
                                }
                            },
                            "agt-command-output");
            reader.setDaemon(true);
            reader.start();
            while (!output.isDone() || process.isAlive()) {
                session.checkActive();
                if (Thread.currentThread().isInterrupted()) {
                    throw new GovernanceException("command_cancelled");
                }
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                        >= limits.timeoutMillis()) {
                    throw new GovernanceException("command_timeout");
                }
                if (output.isCompletedExceptionally()) {
                    return output.join();
                }
                Thread.sleep(10);
            }
            session.checkActive();
            String text = output.join();
            if (process.exitValue() != 0) {
                throw new GovernanceException("command_exit_denied");
            }
            session.checkActive();
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GovernanceException("command_cancelled");
        } catch (IOException e) {
            throw new GovernanceException("command_start_failed");
        } catch (java.util.concurrent.CompletionException e) {
            session.checkActive();
            throw e.getCause() instanceof GovernanceException g
                    ? g
                    : new GovernanceException("command_output_failed");
        } finally {
            if (process != null) {
                if (registered != null && process.isAlive()) registered.requestCancellation();
                terminate(process, reader == null);
            }
            if (reader != null) {
                reader.interrupt();
            } else if (process != null) {
                lifecycle.readerStopped();
            }
            if (registered != null) {
                var receipt = registered;
                lifecycle.tryConfirm();
                if (process == null)
                    lifecycle.stopped.complete(
                            lifecycle.attempted
                                    ? TerminationEvidence.UNKNOWN
                                    : TerminationEvidence.NOT_STARTED);
                lifecycle.stopped.whenComplete(
                        (proof, error) -> {
                            try {
                                receipt.finished(proof);
                            } finally {
                                if (proof == TerminationEvidence.CONFIRMED
                                        || proof == TerminationEvidence.NOT_STARTED)
                                    capacity.release();
                                else quarantined = true;
                            }
                        });
            } else {
                capacity.release();
            }
        }
    }

    /** 只确认直接进程及其输出工作线程；不能证明所有不可观察后代已被内核回收。 */
    private static final class LocalProcessLifecycle {
        final CompletableFuture<TerminationEvidence> stopped = new CompletableFuture<>();
        volatile boolean attempted;
        private Process process;
        private boolean cancelled, readerFinished;

        void attach(Process started) {
            boolean stop;
            synchronized (this) {
                process = started;
                stop = cancelled;
            }
            started.onExit().whenComplete((value, error) -> tryConfirm());
            if (stop) terminate(started, false);
        }

        java.util.concurrent.CompletionStage<TerminationEvidence> cancel() {
            Process current;
            synchronized (this) {
                cancelled = true;
                current = process;
            }
            if (current != null) terminate(current, false);
            tryConfirm();
            return stopped;
        }

        synchronized void readerStarted() {
            readerFinished = false;
        }

        void readerStopped() {
            synchronized (this) {
                readerFinished = true;
            }
            tryConfirm();
        }

        synchronized void tryConfirm() {
            if (process != null && !process.isAlive() && readerFinished)
                stopped.complete(TerminationEvidence.CONFIRMED);
        }
    }

    private void collect(Process process, CompletableFuture<String> output) {
        try (var input = process.getInputStream();
                var bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[4096];
            int read;
            while ((read = input.read(block)) != -1) {
                if (read > limits.maxOutputBytes() - bytes.size()) {
                    output.completeExceptionally(new GovernanceException("command_output_limit"));
                    return;
                }
                bytes.write(block, 0, read);
            }
            // 严格拒绝非 UTF-8，避免替换字符绕过后置内容检查。
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                            .toString();
            output.complete(text);
        } catch (IOException e) {
            output.completeExceptionally(new GovernanceException("command_output_failed"));
        }
    }

    private void verify() {
        if (!canonical(executable, false).equals(executable)
                || !canonical(directory, true).equals(directory)) {
            throw new GovernanceException("command_binding_changed");
        }
        try (var input = Files.newInputStream(executable)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[8192];
            int read;
            while ((read = input.read(block)) != -1) {
                digest.update(block, 0, read);
            }
            if (!sha256.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new GovernanceException("command_integrity_denied");
            }
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new GovernanceException("command_integrity_denied");
        }
    }

    private static Path canonical(Path path, boolean directory) {
        try {
            if (path == null
                    || !path.isAbsolute()
                    || Files.isSymbolicLink(path)
                    || (directory
                            ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                    || !Files.isExecutable(path))) {
                throw new GovernanceException("command_binding_invalid");
            }
            return path.toRealPath();
        } catch (IOException e) {
            throw new GovernanceException("command_binding_invalid");
        }
    }

    private static void terminate(Process process, boolean closeInput) {
        // ProcessHandle 只能尽力处理可观察的后代，不能提供进程组/cgroup 隔离保证。
        try (var descendants = process.descendants()) {
            descendants.forEach(
                    handle -> {
                        try {
                            handle.destroyForcibly();
                        } catch (RuntimeException ignored) {
                            // 单个后代失败不能阻止清理主进程。
                        }
                    });
        } catch (RuntimeException ignored) {
            // 主进程仍需独立清理。
        }
        try {
            process.destroyForcibly();
        } catch (RuntimeException ignored) {
            // 不能覆盖执行时已经确定的安全拒绝原因。
        }
        try {
            // 读取线程独占其流；另一线程 close 可能等待阻塞 read 的锁，破坏超时交付。
            if (closeInput) {
                process.getInputStream().close();
            }
            process.getOutputStream().close();
            process.getErrorStream().close();
        } catch (IOException ignored) {
            // 关闭失败不改变原始拒绝原因。
        }
    }
}
