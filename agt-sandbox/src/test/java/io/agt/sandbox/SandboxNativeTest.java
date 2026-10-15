package io.agt.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.TrustedContext;
import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandLimits;
import io.agt.core.execution.FixedCommand;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.langchain4j.LangChain4jGovernance;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * 当前 Central 制品的 macOS 原生测试。
 *
 * <p>仅使用合成文件和回环服务；其他 OS 明确跳过，不将 fake 测试替代内核证据。
 */
@EnabledOnOs(OS.MAC)
class SandboxNativeTest {
    @TempDir Path temporary;

    static final TrustedContext IDENTITY =
            new TrustedContext("agent", "tenant", "user", Set.of("execute"));

    FixedCommand command(
            Path work, String program, List<CommandArgument> args, SandboxCommandBackend backend)
            throws Exception {
        Path executable = Path.of(program).toRealPath();
        return new FixedCommand(
                "report",
                executable,
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(executable))),
                work,
                args,
                CommandLimits.DEFAULT,
                backend);
    }

    AgentControl control(FixedCommand command) {
        return AgentControl.builder(
                        StructuredPolicy.builder("native-v1")
                                .tool(
                                        "report",
                                        new ToolRule(
                                                Set.of("execute"), command.parameters(), false))
                                .content(
                                        new ContentRule(
                                                "mask",
                                                Set.of(InterventionPoint.POST_TOOL_CALL),
                                                List.of(),
                                                Map.of("secret", "masked")))
                                .build())
                .audit(r -> {})
                .build();
    }

    String execute(AgentControl control, String args) {
        return new LangChain4jGovernance(control)
                .guardCommand(activeCommand)
                .execute(
                        ToolExecutionRequest.builder()
                                .id("native-call")
                                .name("report")
                                .arguments(args)
                                .build(),
                        null);
    }

    private FixedCommand activeCommand;

    @Test
    void hostOptInAllowsWorkspaceWriteAndDeliversRedactedOutput() throws Exception {
        Path work = temporary.toRealPath();
        var policy =
                SandboxPolicy.builder(work)
                        .readPolicy(ReadPolicy.HOST)
                        .network(NetworkPolicy.DENY)
                        .build();
        try (var backend = SandboxCommandBackend.allowHostReads(SandboxClient.create(), policy)) {
            activeCommand =
                    command(
                            work,
                            "/bin/sh",
                            List.of(
                                    CommandArgument.literal("-c"),
                                    CommandArgument.literal(
                                            "printf secret > \"$1\"; printf secret"),
                                    CommandArgument.literal("report"),
                                    CommandArgument.choice("file", "out")),
                            backend);
            try (var control = control(activeCommand);
                    var session = control.openSession(IDENTITY)) {
                assertEquals("masked", execute(control, "{\"file\":\"out\"}"));
                assertEquals("secret", Files.readString(work.resolve("out")));
                assertEquals(1, session.budgetSnapshot().toolCalls());
            }
        }
    }

    @Test
    void readOnlyWorkspaceDoesNotRegainImplicitWriteGrant() throws Exception {
        Path work = temporary.toRealPath();
        var policy =
                SandboxPolicy.builder(work)
                        .readOnlyWorkingDirectory()
                        .readPolicy(ReadPolicy.HOST)
                        .network(NetworkPolicy.DENY)
                        .build();
        try (var backend = SandboxCommandBackend.allowHostReads(SandboxClient.create(), policy)) {
            activeCommand =
                    command(
                            work,
                            "/bin/sh",
                            List.of(
                                    CommandArgument.literal("-c"),
                                    CommandArgument.literal("printf changed > out")),
                            backend);
            try (var control = control(activeCommand);
                    var session = control.openSession(IDENTITY)) {
                assertEquals(
                        "sandbox_exit_denied",
                        assertThrows(GovernanceException.class, () -> execute(control, "{}"))
                                .code());
                assertFalse(Files.exists(work.resolve("out")));
            }
        }
    }

    @Test
    void outsideWriteIsDeniedAndOriginalCanaryUnchanged() throws Exception {
        Path root = temporary.toRealPath();
        Path work = Files.createDirectory(root.resolve("work"));
        Path outside = root.resolve("canary");
        Files.writeString(outside, "canary-only");
        try (var backend =
                SandboxCommandBackend.allowHostReads(
                        SandboxClient.create(),
                        SandboxPolicy.builder(work)
                                .readPolicy(ReadPolicy.HOST)
                                .network(NetworkPolicy.DENY)
                                .build())) {
            activeCommand =
                    command(
                            work,
                            "/bin/sh",
                            List.of(
                                    CommandArgument.literal("-c"),
                                    CommandArgument.literal("printf changed > \"$1\""),
                                    CommandArgument.literal("report"),
                                    CommandArgument.literal(outside.toString())),
                            backend);
            try (var control = control(activeCommand);
                    var session = control.openSession(IDENTITY)) {
                assertEquals(
                        "sandbox_exit_denied",
                        assertThrows(GovernanceException.class, () -> execute(control, "{}"))
                                .code());
                assertEquals("canary-only", Files.readString(outside));
            }
        }
    }

    @Test
    void strictReadEitherPreflightRefusesOrActuallyBlocksUndeclaredCanary() throws Exception {
        Path root = temporary.toRealPath();
        Path work = Files.createDirectory(root.resolve("work"));
        Path outside = root.resolve("canary");
        Files.writeString(outside, "synthetic-secret");
        try (var backend =
                SandboxCommandBackend.strict(
                        SandboxClient.create(),
                        SandboxPolicy.builder(work)
                                .readPolicy(ReadPolicy.DECLARED_ONLY)
                                .network(NetworkPolicy.DENY)
                                .build())) {
            activeCommand =
                    command(
                            work,
                            "/bin/sh",
                            List.of(
                                    CommandArgument.literal("-c"),
                                    CommandArgument.literal("printf started > started; cat \"$1\""),
                                    CommandArgument.literal("report"),
                                    CommandArgument.literal(outside.toString())),
                            backend);
            try (var control = control(activeCommand);
                    var session = control.openSession(IDENTITY)) {
                var failure = assertThrows(GovernanceException.class, () -> execute(control, "{}"));
                assertTrue(
                        Set.of("sandbox_unavailable", "sandbox_exit_denied")
                                .contains(failure.code()));
                if (failure.code().equals("sandbox_unavailable")) {
                    assertFalse(Files.exists(work.resolve("started")));
                }
                assertTrue(session.budgetSnapshot().failed());
            }
        }
    }

    @Test
    void denyNetworkPreventsLoopbackConnectionWithAllowedControl() throws Exception {
        Path work = temporary.toRealPath();
        var accepted = new AtomicInteger();
        try (var server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var thread =
                    new Thread(
                            () -> {
                                while (!server.isClosed()) {
                                    try (var socket = server.accept()) {
                                        accepted.incrementAndGet();
                                        socket.getOutputStream()
                                                .write(
                                                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                                                .getBytes());
                                    } catch (Exception ignored) {
                                        /* 本机测试服务在关闭后退出。 */
                                    }
                                }
                            });
            thread.setDaemon(true);
            thread.start();
            String url = "http://127.0.0.1:" + server.getLocalPort();
            for (var network : List.of(NetworkPolicy.ALLOW, NetworkPolicy.DENY)) {
                int before = accepted.get();
                try (var backend =
                        SandboxCommandBackend.allowHostReads(
                                SandboxClient.create(),
                                SandboxPolicy.builder(work)
                                        .readPolicy(ReadPolicy.HOST)
                                        .network(network)
                                        .build())) {
                    activeCommand =
                            command(
                                    work,
                                    "/usr/bin/curl",
                                    List.of(
                                            CommandArgument.literal("--silent"),
                                            CommandArgument.literal("--max-time"),
                                            CommandArgument.literal("1"),
                                            CommandArgument.literal(url)),
                                    backend);
                    try (var control = control(activeCommand);
                            var session = control.openSession(IDENTITY)) {
                        if (network == NetworkPolicy.ALLOW) {
                            assertEquals("OK", execute(control, "{}"));
                            assertTrue(accepted.get() > before);
                        } else {
                            assertEquals(
                                    "sandbox_exit_denied",
                                    assertThrows(
                                                    GovernanceException.class,
                                                    () -> execute(control, "{}"))
                                            .code());
                            assertEquals(before, accepted.get());
                        }
                    }
                }
            }
        }
    }

    @Test
    void strictReadRunsAllowedCommandAndRejectsUndeclaredCanary() throws Exception {
        Path work = Files.createDirectory(temporary.toRealPath().resolve("strict"));
        try (var backend =
                SandboxCommandBackend.strict(
                        SandboxClient.create(),
                        SandboxPolicy.builder(work)
                                .readPolicy(ReadPolicy.DECLARED_ONLY)
                                .network(NetworkPolicy.DENY)
                                .build())) {
            activeCommand =
                    command(
                            work,
                            "/usr/bin/printf",
                            List.of(CommandArgument.literal("ok")),
                            backend);
            try (var control = control(activeCommand);
                    var session = control.openSession(IDENTITY)) {
                assertEquals("ok", execute(control, "{}"));
                assertEquals(1, session.budgetSnapshot().toolCalls());
            }
            Path outside = temporary.toRealPath().resolve("undeclared");
            Files.writeString(outside, "synthetic-canary");
            activeCommand =
                    command(
                            work,
                            "/bin/cat",
                            List.of(CommandArgument.literal(outside.toString())),
                            backend);
            try (var control = control(activeCommand);
                    var session = control.openSession(IDENTITY)) {
                assertEquals(
                        "sandbox_exit_denied",
                        assertThrows(GovernanceException.class, () -> execute(control, "{}"))
                                .code());
            }
        }
    }

    @Test
    void terminateOnOutputLimitStopsContinuousOutputBeforeTimeout() throws Exception {
        Path work = temporary.toRealPath();
        try (var backend =
                SandboxCommandBackend.allowHostReads(
                        SandboxClient.create(),
                        SandboxPolicy.builder(work)
                                .readPolicy(ReadPolicy.HOST)
                                .network(NetworkPolicy.DENY)
                                .build())) {
            // 单进程持续输出，避免用 shell 子进程终止顺序冒充原子副作用阻断。
            Path executable = Path.of("/usr/bin/yes").toRealPath();
            activeCommand =
                    new FixedCommand(
                            "report",
                            executable,
                            HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(Files.readAllBytes(executable))),
                            work,
                            List.of(),
                            new CommandLimits(5000, 16, 1),
                            backend);
            long start = System.nanoTime();
            try (var control = control(activeCommand);
                    var session = control.openSession(IDENTITY)) {
                assertEquals(
                        "sandbox_output_limit",
                        assertThrows(GovernanceException.class, () -> execute(control, "{}"))
                                .code());
            }
            assertTrue(
                    System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(3));
        }
    }

    @Test
    void strictEnvironmentUsesOnlyTrustedExecutableDirectory() throws Exception {
        Path work = temporary.toRealPath();
        try (var backend =
                SandboxCommandBackend.allowHostReads(
                        SandboxClient.create(),
                        SandboxPolicy.builder(work)
                                .readPolicy(ReadPolicy.HOST)
                                .network(NetworkPolicy.DENY)
                                .build())) {
            activeCommand = command(work, "/usr/bin/env", List.of(), backend);
            try (var control = control(activeCommand);
                    var session = control.openSession(IDENTITY)) {
                String environment = execute(control, "{}");
                assertTrue(environment.lines().anyMatch(line -> line.equals("PATH=/usr/bin")));
                assertFalse(environment.contains("JAVA_TOOL_OPTIONS="));
                assertFalse(environment.contains("DYLD_"));
                assertFalse(environment.contains("LD_PRELOAD="));
            }
        }
    }

    @Test
    void strictReadCanRunExplicitlyDeclaredJdkRuntime() throws Exception {
        Path work = temporary.toRealPath();
        Path javaHome = Path.of(System.getProperty("java.home")).toRealPath();
        try (var backend =
                SandboxCommandBackend.strict(
                        SandboxClient.create(),
                        SandboxPolicy.builder(work)
                                .readableRoot(javaHome)
                                .readPolicy(ReadPolicy.DECLARED_ONLY)
                                .network(NetworkPolicy.DENY)
                                .build())) {
            activeCommand =
                    command(
                            work,
                            javaHome.resolve("bin/java").toString(),
                            List.of(CommandArgument.literal("-version")),
                            backend);
            try (var control = control(activeCommand);
                    var session = control.openSession(IDENTITY)) {
                assertTrue(execute(control, "{}").contains("version"));
            }
        }
    }
}
