package io.agt.examples.live.tools;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.execution.CommandExecution;
import io.agt.core.execution.CommandLimits;
import io.agt.sandbox.SandboxCommandBackend;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 显式启用的通用 Bash；AGT 审批参数，原生沙箱限制脚本副作用。
 *
 * <p>这是示例级任意脚本工具，不属于核心 FixedCommand 的固定命令契约。
 */
public final class BashTool implements AutoCloseable {
    private final AgentControl control;
    private final SandboxCommandBackend backend;
    private final Path root;
    private final Path executable;

    public BashTool(AgentControl control, Path workspace) throws IOException {
        this(control, workspace, false);
    }

    public BashTool(AgentControl control, Path workspace, boolean hostReads) throws IOException {
        this.control = control;
        root = workspace.toRealPath();
        executable = Path.of("/bin/bash").toRealPath();
        if (!Files.isExecutable(executable)) {
            throw new IOException("Bash unavailable");
        }
        var policy =
                SandboxPolicy.builder(root)
                        .readPolicy(hostReads ? ReadPolicy.HOST : ReadPolicy.DECLARED_ONLY)
                        .readableRoot(Path.of("/bin").toRealPath())
                        .readableRoot(Path.of("/usr/bin").toRealPath())
                        .network(NetworkPolicy.DENY)
                        .build();
        backend =
                hostReads
                        ? SandboxCommandBackend.allowHostReads(SandboxClient.create(), policy)
                        : SandboxCommandBackend.strict(SandboxClient.create(), policy);
    }

    @Tool(
            name = "Bash",
            value =
                    "Execute a Bash script in workspace, network denied, 10s/32768-byte limits; requires human approval. Starts in workspace; HOME is workspace, not the host user home. File read scope depends on host configuration; writes remain sandbox restricted.")
    public String executeForAgent(String command) {
        return backend.executeReport(execution(command), control.currentSession());
    }

    private CommandExecution execution(String command) {
        return new CommandExecution(
                executable,
                root,
                List.of("--noprofile", "--norc", "-c", command),
                new CommandLimits(10_000, 32768, 1));
    }

    public String execute(String command) {
        var exitCode = new java.util.concurrent.atomic.AtomicReference<Integer>();
        try {
            return backend.execute(
                    new CommandExecution(
                            executable,
                            root,
                            List.of("--noprofile", "--norc", "-c", command),
                            new CommandLimits(10_000, 32768, 1)),
                    control.currentSession(),
                    exitCode::set);
        } catch (GovernanceException error) {
            if (error.code().equals("sandbox_exit_denied") && exitCode.get() != null) {
                error.addSuppressed(new CommandFailed(exitCode.get()));
            }
            throw error;
        }
    }

    /** 仅携带数字退出码，原始进程输出不绕过结果检查。 */
    public static final class CommandFailed extends RuntimeException {
        private final int exitCode;

        public CommandFailed(int exitCode) {
            super("Bash exited with code " + exitCode);
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    @Override
    public void close() {
        backend.close();
    }
}
