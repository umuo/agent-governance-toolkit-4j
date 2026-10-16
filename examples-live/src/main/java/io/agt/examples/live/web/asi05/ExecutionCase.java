package io.agt.examples.live.web.asi05;

import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.TrustedContext;
import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandLimits;
import io.agt.core.execution.FixedCommand;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.examples.live.web.experiment.ExperimentCase;
import io.agt.sandbox.SandboxCommandBackend;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 宿主固定程序与参数，只操作新建的合成工作区；不解释模型提交的代码、路径或 URL。 */
public abstract class ExecutionCase implements ExperimentCase {
    protected final boolean guarded;
    protected final Path root;
    protected final Path work;
    protected final Path canary;
    protected AgentControl control;
    protected int entered;
    protected int attempts;
    protected int completed;
    protected String output = "";
    protected SandboxCommandBackend backend;

    protected ExecutionCase(boolean guarded) throws Exception {
        this.guarded = guarded;
        root = Files.createTempDirectory("agt-asi05-").toRealPath();
        work = Files.createDirectory(root.resolve("work"));
        canary = Files.writeString(root.resolve("outside.txt"), "before");
        Files.writeString(work.resolve("report.txt"), "before");
    }

    @Override
    public TrustedContext identity() {
        return new TrustedContext("asi05-demo", "tenant-a", "demo-user", Set.of("execute"));
    }

    @Override
    public void attach(AgentControl control) {
        this.control = control;
    }

    protected static ToolRule rule(boolean approval, Map<String, ParameterRule> fields) {
        return new ToolRule(
                Set.of("execute"),
                ParameterRule.object(fields, fields.keySet().toArray(String[]::new)),
                approval);
    }

    protected static ParameterRule choice(String... choices) {
        return new ParameterRule(
                ParameterRule.Type.STRING,
                null,
                null,
                List.of(choices),
                null,
                null,
                1,
                100,
                null,
                null);
    }

    protected StructuredPolicy allow(String name) {
        return StructuredPolicy.builder("asi05-" + name).tool(name, rule(false, Map.of())).build();
    }

    protected SandboxCommandBackend sandbox(boolean strictRead, boolean readOnly) {
        requirePlatform();
        if (backend == null) {
            var builder =
                    SandboxPolicy.builder(work)
                            .readPolicy(strictRead ? ReadPolicy.DECLARED_ONLY : ReadPolicy.HOST)
                            .network(NetworkPolicy.DENY);
            if (readOnly) builder.readOnlyWorkingDirectory();
            try {
                backend =
                        strictRead
                                ? SandboxCommandBackend.strict(
                                        SandboxClient.create(), builder.build())
                                : SandboxCommandBackend.allowHostReads(
                                        SandboxClient.create(), builder.build());
            } catch (LinkageError error) {
                throw new GovernanceException("sandbox_unavailable");
            }
        }
        return backend;
    }

    protected static void requirePlatform() {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                || !Files.isExecutable(Path.of("/bin/sh"))) {
            throw new GovernanceException("demo_platform_unavailable");
        }
    }

    protected static String digest(Path path) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    protected String shell(String script, boolean isolated, boolean strictRead, boolean readOnly)
            throws Exception {
        return execute(
                "/bin/sh",
                List.of("-c", script, "asi05-fixture", canary.toString()),
                isolated,
                strictRead,
                readOnly,
                3000);
    }

    protected String execute(
            String binary,
            List<String> args,
            boolean isolated,
            boolean strictRead,
            boolean readOnly,
            long timeout)
            throws Exception {
        requirePlatform();
        Path executable = Path.of(binary).toRealPath();
        var argv = args.stream().map(CommandArgument::literal).toList();
        var limits = new CommandLimits(timeout, 4096, 1);
        var command =
                isolated
                        ? new FixedCommand(
                                "fixture",
                                executable,
                                digest(executable),
                                work,
                                argv,
                                limits,
                                sandbox(strictRead, readOnly))
                        : new FixedCommand(
                                "fixture", executable, digest(executable), work, argv, limits);
        return execute(command);
    }

    protected String execute(FixedCommand command) {
        command.prepare(Map.of());
        attempts++;
        if (guarded) {
            output = command.execute(Map.of(), control.currentSession());
        } else {
            // 原生对照仅复用进程超时和输出上限，不接入工具权限、审批或 OS 隔离。
            try (var runtime =
                            AgentControl.builder(StructuredPolicy.builder("fixture-limits").build())
                                    .audit(record -> {})
                                    .build();
                    var session = runtime.openSession(identity())) {
                output = command.execute(Map.of(), session);
            }
        }
        completed++;
        return output;
    }

    @Override
    public Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("toolEntered", entered);
        result.put("executionAttempts", attempts);
        result.put("completedExecutions", completed);
        result.put("outsideContent", content(canary));
        result.put("workspaceContent", content(work.resolve("report.txt")));
        result.put("capturedOutput", output);
        return Map.copyOf(result);
    }

    private static String content(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "[deleted]";
        } catch (Exception error) {
            return "[unreadable]";
        }
    }

    @Override
    public void close() throws Exception {
        if (backend != null) backend.close();
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }
}
