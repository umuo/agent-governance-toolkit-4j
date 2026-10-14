package io.agt.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.TrustedContext;
import io.agt.core.policy.StructuredPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 固定程序边界测试。
 *
 * <p>实际启动 Java 子进程验证环境、输出、期限和会话终止；不执行模型提供的脚本。
 */
class FixedCommandTest {
    @TempDir Path directory;

    FixedCommand command(CommandLimits limits) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        Path classes =
                Path.of(
                                CommandFixture.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toRealPath();
        return new FixedCommand(
                "fixed",
                java,
                digest(java),
                directory,
                List.of(
                        CommandArgument.literal("-cp"),
                        CommandArgument.literal(classes.toString()),
                        CommandArgument.literal(CommandFixture.class.getName()),
                        CommandArgument.choice(
                                "mode",
                                "echo",
                                "environment",
                                "timeout",
                                "overflow",
                                "exit",
                                "invalid",
                                "literal;$(echo unsafe)"),
                        CommandArgument.literal(directory.resolve("effect").toString())),
                limits);
    }

    static String digest(Path file) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    AgentControl control() {
        return AgentControl.builder(StructuredPolicy.builder("command-test").build())
                .audit(record -> {})
                .build();
    }

    static <T> T withSession(
            AgentControl control,
            TrustedContext context,
            java.util.function.Function<io.agt.core.HostSession, T> action) {
        try (var session = control.openSession(context)) {
            return action.apply(session);
        }
    }

    static TrustedContext context() {
        return new TrustedContext("agent", "tenant", "user", Set.of());
    }

    @Test
    void fixedProcessHasNoInheritedEnvironment() throws Exception {
        var command = command(CommandLimits.DEFAULT);
        try (var control = control()) {
            withSession(
                    control,
                    context(),
                    session -> {
                        assertEquals(
                                "empty", command.execute(Map.of("mode", "environment"), session));
                        return null;
                    });
        }
        assertTrue(Files.exists(directory.resolve("effect")));
    }

    @Test
    void allowedMetacharactersRemainOneLiteralArgument() throws Exception {
        var command = command(CommandLimits.DEFAULT);
        try (var control = control();
                var session = control.openSession(context())) {
            assertEquals(
                    "literal;$(echo unsafe)",
                    command.execute(Map.of("mode", "literal;$(echo unsafe)"), session));
        }
    }

    @Test
    void sessionDeadlineRefusesProcessOutput() throws Exception {
        var command = command(new CommandLimits(10000, 1000, 1));
        try (var control =
                        AgentControl.builder(
                                        StructuredPolicy.builder("deadline-test")
                                                .budget(new io.agt.core.BudgetLimits(10, 10, 500))
                                                .build())
                                .audit(record -> {})
                                .build();
                var session = control.openSession(context())) {
            assertEquals(
                    "session_expired",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> command.execute(Map.of("mode", "timeout"), session))
                            .code());
            assertTrue(session.budgetSnapshot().failed());
        }
    }

    @Test
    void unknownMissingAndInjectedArgumentsNeverStartProcess() throws Exception {
        var command = command(CommandLimits.DEFAULT);
        for (var values :
                List.of(
                        Map.<String, Object>of(),
                        Map.<String, Object>of("mode", "echo", "command", "sh"),
                        Map.<String, Object>of("mode", "; touch injected"),
                        Map.<String, Object>of("mode", "--help"),
                        Map.<String, Object>of("mode", "../elsewhere"),
                        Map.<String, Object>of("mode", 1))) {
            assertEquals(
                    "command_arguments_denied",
                    assertThrows(GovernanceException.class, () -> command.prepare(values)).code());
        }
        assertFalse(Files.exists(directory.resolve("effect")));
    }

    @ParameterizedTest
    @CsvSource({
        "timeout,command_timeout",
        "overflow,command_output_limit",
        "exit,command_exit_denied",
        "invalid,command_output_failed"
    })
    void boundedProcessFailuresUseSanitizedCodes(String mode, String expected) throws Exception {
        var command = command(new CommandLimits(mode.equals("timeout") ? 800 : 5000, 1000, 1));
        try (var control = control()) {
            withSession(
                    control,
                    context(),
                    session -> {
                        assertEquals(
                                expected,
                                assertThrows(
                                                GovernanceException.class,
                                                () ->
                                                        command.execute(
                                                                Map.of("mode", mode), session))
                                        .code());
                        return null;
                    });
        }
        assertTrue(Files.exists(directory.resolve("effect")));
    }

    @Test
    void changedExecutableIsRefusedBeforeStarting() throws Exception {
        Path executable = directory.resolve("program");
        Files.writeString(executable, "trusted");
        assertTrue(executable.toFile().setExecutable(true));
        var command =
                new FixedCommand(
                        "fixed",
                        executable,
                        digest(executable),
                        directory,
                        List.of(),
                        CommandLimits.DEFAULT);
        Files.writeString(executable, "modified");
        assertEquals(
                "command_integrity_denied",
                assertThrows(GovernanceException.class, () -> command.prepare(Map.of())).code());
    }

    @Test
    void invalidProfilesAndWrongDigestFailAtRegistration() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        assertThrows(
                GovernanceException.class,
                () ->
                        new FixedCommand(
                                "fixed",
                                java,
                                "0".repeat(64),
                                directory,
                                List.of(),
                                CommandLimits.DEFAULT));
        assertThrows(
                GovernanceException.class,
                () ->
                        new FixedCommand(
                                "fixed",
                                Path.of("java"),
                                digest(java),
                                directory,
                                List.of(),
                                CommandLimits.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new CommandLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> CommandArgument.choice("mode"));
        assertThrows(IllegalArgumentException.class, () -> CommandArgument.literal("bad\0arg"));
        Path link = directory.resolve("link");
        Files.createSymbolicLink(link, java);
        assertThrows(
                GovernanceException.class,
                () ->
                        new FixedCommand(
                                "fixed",
                                link,
                                digest(java),
                                directory,
                                List.of(),
                                CommandLimits.DEFAULT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoke", "close", "interrupt"})
    void terminationStopsWaitAndCapacityIsReleased(String stop) throws Exception {
        var command = command(new CommandLimits(10000, 1000, 1));
        var worker = Executors.newSingleThreadExecutor();
        try (var control = control();
                var session = control.openSession(context())) {
            var result = worker.submit(() -> command.execute(Map.of("mode", "timeout"), session));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(directory.resolve("effect")) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(directory.resolve("effect")));
            assertEquals(
                    "command_capacity_exceeded",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> command.execute(Map.of("mode", "echo"), session))
                            .code());
            long pid = Long.parseLong(Files.readString(directory.resolve("effect")));
            if (stop.equals("revoke")) {
                session.revoke();
            } else if (stop.equals("close")) {
                session.close();
            } else {
                result.cancel(true);
            }
            if (!stop.equals("interrupt")) {
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> result.get(2, TimeUnit.SECONDS));
            }
            long cleanup = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                    && System.nanoTime() < cleanup) {
                Thread.sleep(10);
            }
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            withSession(
                    control,
                    context(),
                    next -> {
                        assertEquals(
                                "secret-result", command.execute(Map.of("mode", "echo"), next));
                        return null;
                    });
        } finally {
            worker.shutdownNow();
        }
    }
}
