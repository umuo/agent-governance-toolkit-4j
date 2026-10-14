package io.agt.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.TrustedContext;
import io.agt.core.approval.ApprovalResponse;
import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandExecution;
import io.agt.core.execution.CommandLimits;
import io.agt.core.execution.FixedCommand;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.langchain4j.LangChain4jGovernance;
import io.github.sandboxdemo.api.DeletionPolicy;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxCapabilities;
import io.github.sandboxdemo.api.SandboxEnforcement;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxPlatform;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.api.SandboxRunner;
import io.github.sandboxdemo.api.SandboxRuntimeStatus;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Central SDK 的治理接入契约测试。
 *
 * <p>Fake runner 只用于可控故障/竞态；不将这些测试称为内核隔离测试。
 */
class SandboxCommandBackendTest {
    @TempDir Path directory;

    public interface Assistant {
        String chat(String input);
    }

    @FunctionalInterface
    interface Execute {
        SandboxResult run(SandboxRequest request) throws SandboxException, InterruptedException;
    }

    static SandboxClient client(Execute execute) {
        var capabilities =
                new SandboxCapabilities(
                        SandboxPlatform.MACOS,
                        "fake",
                        Set.of(ReadPolicy.HOST, ReadPolicy.DECLARED_ONLY),
                        Set.of(NetworkPolicy.DENY, NetworkPolicy.ALLOW),
                        Set.of(DeletionPolicy.ALLOW),
                        false,
                        SandboxEnforcement.FULL);
        return SandboxClient.builder()
                .backend(
                        new SandboxRunner() {
                            public String backendName() {
                                return "fake";
                            }

                            public SandboxResult execute(SandboxRequest request)
                                    throws SandboxException, InterruptedException {
                                return execute.run(request);
                            }
                        },
                        capabilities,
                        () -> new SandboxRuntimeStatus(capabilities, true, "fake"))
                .build();
    }

    SandboxPolicy policy() {
        return SandboxPolicy.builder(directory)
                .readPolicy(ReadPolicy.DECLARED_ONLY)
                .network(NetworkPolicy.DENY)
                .timeout(Duration.ofSeconds(2))
                .maxOutputBytes(1024)
                .build();
    }

    FixedCommand command(SandboxCommandBackend backend, CommandLimits limits) throws Exception {
        Path binary = directory.resolve("tool");
        Files.writeString(binary, "trusted");
        assertTrue(binary.toFile().setExecutable(true));
        return new FixedCommand(
                "report",
                binary,
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(binary))),
                directory,
                List.of(CommandArgument.choice("mode", "old", "new")),
                limits,
                backend);
    }

    static TrustedContext identity(boolean permitted) {
        return new TrustedContext(
                "agent", "tenant", "user", permitted ? Set.of("execute") : Set.of());
    }

    static SandboxResult result(String stdout, String stderr) {
        return new SandboxResult(
                0,
                false,
                stdout.getBytes(StandardCharsets.UTF_8),
                stderr.getBytes(StandardCharsets.UTF_8),
                false,
                false,
                Duration.ofMillis(1));
    }

    static ToolExecutionRequest request() {
        return ToolExecutionRequest.builder()
                .id("call")
                .name("report")
                .arguments("{\"mode\":\"old\"}")
                .build();
    }

    static StructuredPolicy rules(FixedCommand command, boolean approval) {
        return StructuredPolicy.builder("sandbox-v1")
                .tool("report", new ToolRule(Set.of("execute"), command.parameters(), approval))
                .content(
                        new ContentRule(
                                "args",
                                Set.of(InterventionPoint.PRE_TOOL_CALL),
                                List.of(),
                                Map.of("old", "new")))
                .content(
                        new ContentRule(
                                "output",
                                Set.of(InterventionPoint.POST_TOOL_CALL),
                                List.of(),
                                Map.of("secret", "masked")))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"execute", "context", "async"})
    void effectiveArgumentsAndRedactedResultsReachRealToolEntryPoints(String entry)
            throws Exception {
        var effects = new AtomicInteger();
        try (var backend =
                SandboxCommandBackend.strict(
                        client(
                                r -> {
                                    effects.incrementAndGet();
                                    assertEquals(List.of("new"), r.command().arguments());
                                    assertEquals(NetworkPolicy.DENY, r.policy().networkPolicy());
                                    assertTrue(r.environment().isEmpty());
                                    assertEquals(0, r.standardInput().length);
                                    assertTrue(
                                            r.stdoutConsumer() == null
                                                    && r.stderrConsumer() == null
                                                    && r.stdoutTextConsumer() == null
                                                    && r.stderrTextConsumer() == null);
                                    assertTrue(r.policy().timeout().toMillis() <= 1000);
                                    return result("secret", "secret-error");
                                }),
                        policy())) {
            var command = command(backend, new CommandLimits(1000, 128, 1));
            var audit =
                    new java.util.concurrent.CopyOnWriteArrayList<io.agt.core.audit.AuditRecord>();
            try (var control =
                            AgentControl.builder(rules(command, false)).audit(audit::add).build();
                    var session = control.openSession(identity(true))) {
                var tool = new LangChain4jGovernance(control).guardCommand(command);
                String text =
                        switch (entry) {
                            case "context" ->
                                    tool.executeWithContext(
                                                    request(), InvocationContext.builder().build())
                                            .resultText();
                            case "async" ->
                                    tool.executeAsync(
                                                    request(), InvocationContext.builder().build())
                                            .get(3, TimeUnit.SECONDS)
                                            .resultText();
                            default -> tool.execute(request(), null);
                        };
                assertEquals("masked\nmasked-error", text);
                assertEquals(1, session.budgetSnapshot().toolCalls());
                assertFalse(audit.toString().contains("secret"));
            }
        }
        assertEquals(1, effects.get());
    }

    @Test
    void permissionsAndApprovalRefusalsNeverReachSdk() throws Exception {
        var effects = new AtomicInteger();
        try (var backend =
                SandboxCommandBackend.strict(
                        client(
                                r -> {
                                    effects.incrementAndGet();
                                    return result("ok", "");
                                }),
                        policy())) {
            var command = command(backend, CommandLimits.DEFAULT);
            for (boolean permissions : List.of(false, true)) {
                try (var control =
                                AgentControl.builder(rules(command, true))
                                        .audit(r -> {})
                                        .approvalResolver(
                                                r ->
                                                        CompletableFuture.completedFuture(
                                                                ApprovalResponse.deny(r)))
                                        .build();
                        var session = control.openSession(identity(permissions))) {
                    assertThrows(
                            GovernanceException.class,
                            () ->
                                    new LangChain4jGovernance(control)
                                            .guardCommand(command)
                                            .execute(request(), null));
                    assertEquals(0, session.budgetSnapshot().toolCalls());
                }
            }
        }
        assertEquals(0, effects.get());
    }

    @ParameterizedTest
    @CsvSource({
        "stdout,sandbox_output_limit",
        "stderr,sandbox_output_limit",
        "aggregate,sandbox_output_limit",
        "encoding,sandbox_output_encoding_invalid",
        "exit,sandbox_exit_denied",
        "timeout,sandbox_timeout",
        "unavailable,sandbox_unavailable",
        "failure,sandbox_execution_failed",
        "null,sandbox_invalid_result"
    })
    void unsafeSdkResultsAndErrorsNeverPropagateRawContent(String kind, String code)
            throws Exception {
        try (var backend =
                SandboxCommandBackend.strict(
                        client(
                                r ->
                                        switch (kind) {
                                            case "stdout" ->
                                                    new SandboxResult(
                                                            0,
                                                            false,
                                                            new byte[0],
                                                            new byte[0],
                                                            true,
                                                            false,
                                                            Duration.ZERO);
                                            case "stderr" ->
                                                    new SandboxResult(
                                                            0,
                                                            false,
                                                            new byte[0],
                                                            new byte[0],
                                                            false,
                                                            true,
                                                            Duration.ZERO);
                                            case "aggregate" ->
                                                    result("a".repeat(70), "b".repeat(70));
                                            case "encoding" ->
                                                    new SandboxResult(
                                                            0,
                                                            false,
                                                            new byte[] {(byte) 255},
                                                            new byte[0],
                                                            false,
                                                            false,
                                                            Duration.ZERO);
                                            case "exit" ->
                                                    new SandboxResult(
                                                            3,
                                                            false,
                                                            "private-output".getBytes(),
                                                            new byte[0],
                                                            false,
                                                            false,
                                                            Duration.ZERO);
                                            case "timeout" ->
                                                    new SandboxResult(
                                                            0,
                                                            true,
                                                            new byte[0],
                                                            new byte[0],
                                                            false,
                                                            false,
                                                            Duration.ZERO);
                                            case "unavailable" ->
                                                    throw new SandboxBackendUnavailableException(
                                                            "private-backend-details");
                                            case "failure" ->
                                                    throw new SandboxException(
                                                            "private-backend-details");
                                            default -> null;
                                        }),
                        policy())) {
            var command = command(backend, new CommandLimits(1000, 128, 1));
            try (var control = AgentControl.builder(rules(command, false)).audit(r -> {}).build();
                    var session = control.openSession(identity(true))) {
                var error =
                        assertThrows(
                                GovernanceException.class,
                                () ->
                                        new LangChain4jGovernance(control)
                                                .guardCommand(command)
                                                .execute(request(), null));
                assertEquals(code, error.code());
                assertFalse(error.getMessage().contains("private"));
                assertEquals(1, session.budgetSnapshot().toolCalls());
                assertTrue(session.budgetSnapshot().failed());
            }
        }
    }

    @Test
    void hostReadsRequireOptInAndBindingMustMatch() throws Exception {
        var host =
                SandboxPolicy.builder(directory)
                        .readPolicy(ReadPolicy.HOST)
                        .network(NetworkPolicy.DENY)
                        .build();
        var client = client(r -> result("ok", ""));
        assertEquals(
                "sandbox_host_read_requires_opt_in",
                assertThrows(
                                GovernanceException.class,
                                () -> SandboxCommandBackend.strict(client, host))
                        .code());
        try (var backend = SandboxCommandBackend.allowHostReads(client, host)) {
            assertEquals(
                    "sandbox_binding_mismatch",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            backend.prepare(
                                                    new CommandExecution(
                                                            directory.resolve("bin"),
                                                            directory.resolve("other"),
                                                            List.of(),
                                                            CommandLimits.DEFAULT)))
                            .code());
        }
        var pathSearch = SandboxPolicy.builder(directory).allowPathSearch(true).build();
        assertEquals(
                "sandbox_path_search_denied",
                assertThrows(
                                GovernanceException.class,
                                () -> SandboxCommandBackend.strict(client, pathSearch))
                        .code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "revoke", "close", "cancel"})
    void uncooperativeSdkCannotBlockCallerOrReuseBackend(String stop) throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var backend =
                SandboxCommandBackend.strict(
                        client(
                                r -> {
                                    calls.incrementAndGet();
                                    started.countDown();
                                    try {
                                        while (release.getCount() > 0) {
                                            try {
                                                release.await();
                                            } catch (InterruptedException ignored) {
                                                /* 故意模拟不响应取消。 */
                                            }
                                        }
                                        return result("late-secret", "");
                                    } finally {
                                        finished.countDown();
                                    }
                                }),
                        policy(),
                        1)) {
            var command =
                    command(
                            backend,
                            new CommandLimits(stop.equals("timeout") ? 100 : 2000, 128, 2));
            try (var control = AgentControl.builder(rules(command, false)).audit(r -> {}).build();
                    var session = control.openSession(identity(true))) {
                var future =
                        new LangChain4jGovernance(control)
                                .guardCommand(command)
                                .executeAsync(request(), InvocationContext.builder().build());
                assertTrue(started.await(1, TimeUnit.SECONDS));
                switch (stop) {
                    case "revoke" -> session.revoke();
                    case "close" -> session.close();
                    case "cancel" -> future.cancel(true);
                    default -> {}
                }
                assertThrows(Exception.class, () -> future.get(1, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (true) {
                    try {
                        backend.prepare(
                                new CommandExecution(
                                        directory.resolve("tool"),
                                        directory.toRealPath(),
                                        List.of(),
                                        CommandLimits.DEFAULT));
                    } catch (GovernanceException e) {
                        assertEquals("sandbox_cleanup_unconfirmed", e.code());
                        break;
                    }
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("Cancellation was not observed");
                    }
                    Thread.sleep(5);
                }
                assertEquals(1, calls.get());
                assertEquals(1, session.budgetSnapshot().toolCalls());
            }
        } finally {
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void noQueueAndClosedBackendRefuseWithoutAdditionalSdkCalls() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var backend =
                SandboxCommandBackend.strict(
                        client(
                                r -> {
                                    calls.incrementAndGet();
                                    started.countDown();
                                    release.await();
                                    return result("secret", "");
                                }),
                        policy(),
                        1)) {
            var command = command(backend, new CommandLimits(2000, 128, 2));
            try (var control = AgentControl.builder(rules(command, false)).audit(r -> {}).build();
                    var first = control.openSession(identity(true))) {
                var tool = new LangChain4jGovernance(control).guardCommand(command);
                var pending = tool.executeAsync(request(), InvocationContext.builder().build());
                assertTrue(started.await(1, TimeUnit.SECONDS));
                try (var second = control.openSession(identity(true))) {
                    assertEquals(
                            "sandbox_capacity_exceeded",
                            assertThrows(
                                            GovernanceException.class,
                                            () -> tool.execute(request(), null))
                                    .code());
                    assertEquals(1, second.budgetSnapshot().toolCalls());
                }
                assertEquals(1, calls.get());
                release.countDown();
                assertEquals("masked", pending.get(1, TimeUnit.SECONDS).resultText());
                backend.close();
                assertEquals(
                        "sandbox_backend_closed",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                backend.prepare(
                                                        new CommandExecution(
                                                                directory.resolve("tool"),
                                                                directory,
                                                                List.of(),
                                                                CommandLimits.DEFAULT)))
                                .code());
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void approvalAllowedExecutesOnceAndUnsupportedCapabilitiesRefuse() throws Exception {
        var calls = new AtomicInteger();
        try (var backend =
                SandboxCommandBackend.strict(
                        client(
                                r -> {
                                    calls.incrementAndGet();
                                    return result("secret", "");
                                }),
                        policy())) {
            var command = command(backend, CommandLimits.DEFAULT);
            try (var control =
                            AgentControl.builder(rules(command, true))
                                    .audit(r -> {})
                                    .approvalResolver(
                                            r -> {
                                                assertEquals(
                                                        "new",
                                                        ((Map<?, ?>) r.action().target())
                                                                .get("mode"));
                                                return CompletableFuture.completedFuture(
                                                        ApprovalResponse.allow(r));
                                            })
                                    .build();
                    var session = control.openSession(identity(true))) {
                assertEquals(
                        "masked",
                        new LangChain4jGovernance(control)
                                .guardCommand(command)
                                .execute(request(), null));
                assertEquals(1, calls.get());
            }
        }
        var caps =
                new SandboxCapabilities(
                        SandboxPlatform.WINDOWS,
                        "unsupported",
                        Set.of(ReadPolicy.HOST),
                        Set.of(NetworkPolicy.ALLOW),
                        false);
        var unsupported =
                SandboxClient.builder()
                        .backend(
                                new SandboxRunner() {
                                    public String backendName() {
                                        return "unsupported";
                                    }

                                    public SandboxResult execute(SandboxRequest request) {
                                        throw new AssertionError("No downgrade");
                                    }
                                },
                                caps,
                                () -> new SandboxRuntimeStatus(caps, true, "unused"))
                        .build();
        assertEquals(
                "sandbox_policy_unsupported",
                assertThrows(
                                GovernanceException.class,
                                () -> SandboxCommandBackend.strict(unsupported, policy()))
                        .code());
    }

    @Test
    void sdkRequestConstraintsAreCheckedBeforeExecutionCount() throws Exception {
        var calls = new AtomicInteger();
        try (var backend =
                SandboxCommandBackend.strict(
                        client(
                                r -> {
                                    calls.incrementAndGet();
                                    return result("ok", "");
                                }),
                        policy())) {
            command(backend, CommandLimits.DEFAULT);
            Path binary = directory.resolve("tool");
            var args =
                    java.util.stream.IntStream.range(0, 10)
                            .mapToObj(i -> CommandArgument.literal("x".repeat(4096)))
                            .toList();
            var command =
                    new FixedCommand(
                            "report",
                            binary,
                            HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(Files.readAllBytes(binary))),
                            directory,
                            args,
                            CommandLimits.DEFAULT,
                            backend);
            try (var control = AgentControl.builder(rules(command, false)).audit(r -> {}).build();
                    var session = control.openSession(identity(true))) {
                assertEquals(
                        "sandbox_request_invalid",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                new LangChain4jGovernance(control)
                                                        .guardCommand(command)
                                                        .execute(
                                                                ToolExecutionRequest.builder()
                                                                        .id("invalid")
                                                                        .name("report")
                                                                        .arguments("{}")
                                                                        .build(),
                                                                null))
                                .code());
                assertEquals(0, session.budgetSnapshot().toolCalls());
                assertEquals(0, calls.get());
            }
        }
    }

    @Test
    void lateCancellationDoesNotInterruptReusedSdkWorker() throws Exception {
        var firstStarted = new CountDownLatch(1);
        var firstRelease = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondRelease = new CountDownLatch(1);
        var secondInterrupted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var callers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (var backend =
                        SandboxCommandBackend.strict(
                                client(
                                        r -> {
                                            if (calls.incrementAndGet() == 1) {
                                                firstStarted.countDown();
                                                firstRelease.await();
                                            } else {
                                                secondStarted.countDown();
                                                while (secondRelease.getCount() > 0) {
                                                    try {
                                                        secondRelease.await();
                                                    } catch (InterruptedException ignored) {
                                                        secondInterrupted.countDown();
                                                    }
                                                }
                                            }
                                            return result("ok", "");
                                        }),
                                policy(),
                                1);
                var control =
                        AgentControl.builder(StructuredPolicy.builder("race-v1").build())
                                .audit(r -> {})
                                .build();
                var first = control.openDetachedSession(identity(true));
                var second = control.openDetachedSession(identity(true))) {
            var plan =
                    new CommandExecution(
                            directory.resolve("tool"),
                            directory.toRealPath(),
                            List.of(),
                            new CommandLimits(2000, 128, 1));
            var result = callers.submit(() -> backend.execute(plan, first));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            java.util.concurrent.Future<String> next;
            synchronized (first) {
                // 第一次 SDK 已返回，但调用方被冻结在会话检查；池线程可被第二次执行复用。
                firstRelease.countDown();
                next =
                        callers.submit(
                                () -> {
                                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                                    while (true) {
                                        try {
                                            return backend.execute(plan, second);
                                        } catch (GovernanceException error) {
                                            if (!error.code().equals("sandbox_capacity_exceeded")
                                                    || System.nanoTime() > deadline) {
                                                throw error;
                                            }
                                            Thread.sleep(5);
                                        }
                                    }
                                });
                assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
                first.revoke();
            }
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> result.get(1, TimeUnit.SECONDS));
            assertFalse(secondInterrupted.await(100, TimeUnit.MILLISECONDS));
            secondRelease.countDown();
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> next.get(1, TimeUnit.SECONDS));
        } finally {
            firstRelease.countDown();
            secondRelease.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void realAiServicesLoopUsesCheckedSandboxResult() throws Exception {
        try (var backend =
                SandboxCommandBackend.strict(client(r -> result("secret", "")), policy())) {
            var command = command(backend, CommandLimits.DEFAULT);
            var calls = new AtomicInteger();
            ChatModel model =
                    new ChatModel() {
                        public ChatResponse doChat(ChatRequest r) {
                            calls.incrementAndGet();
                            var last = r.messages().get(r.messages().size() - 1);
                            return ChatResponse.builder()
                                    .aiMessage(
                                            last instanceof ToolExecutionResultMessage output
                                                    ? AiMessage.from(output.text())
                                                    : AiMessage.from(request()))
                                    .build();
                        }
                    };
            try (var control = AgentControl.builder(rules(command, false)).audit(r -> {}).build();
                    var session = control.openSession(identity(true))) {
                var assistant =
                        new LangChain4jGovernance(control)
                                .aiServices(Assistant.class)
                                .chatModel(model)
                                .commandTool(command)
                                .build();
                assertEquals("masked", assistant.chat("report"));
                assertEquals(2, calls.get());
                assertEquals(1, session.budgetSnapshot().toolCalls());
            }
        }
    }
}
