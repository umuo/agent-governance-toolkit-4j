package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.TrustedContext;
import io.agt.core.approval.ApprovalResponse;
import io.agt.core.audit.AuditRecord;
import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandLimits;
import io.agt.core.execution.FixedCommand;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 命令工具的真实治理链测试。
 *
 * <p>验证同步重载、异步、AI Services、审批、改写与审计，执行标记证明进程副作用。
 */
class CommandGovernanceTest {
    @TempDir Path directory;

    public interface Assistant {
        String chat(String input);
    }

    /** 固定受信任子进程，参数被宿主模板限制。 */
    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            Path marker = Path.of(args[1]);
            Path pending = marker.resolveSibling(marker.getFileName() + ".pending");
            Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
            Files.move(
                    pending,
                    marker,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (args[0].equals("wait")) {
                Thread.sleep(60000);
            }
            System.out.print(args[0]);
        }
    }

    FixedCommand command() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        Path classes =
                Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                        .toRealPath();
        return new FixedCommand(
                "report",
                java,
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(java))),
                directory,
                List.of(
                        CommandArgument.literal("-cp"),
                        CommandArgument.literal(classes.toString()),
                        CommandArgument.literal(Fixture.class.getName()),
                        CommandArgument.choice("mode", "old", "secret-result", "wait"),
                        CommandArgument.literal(directory.resolve("effect").toString())),
                CommandLimits.DEFAULT);
    }

    static TrustedContext context(boolean allowed) {
        return new TrustedContext(
                "agent", "tenant", "user", allowed ? Set.of("execute") : Set.of());
    }

    static ToolExecutionRequest request(String args) {
        return ToolExecutionRequest.builder().id("call-1").name("report").arguments(args).build();
    }

    StructuredPolicy.Builder policy(FixedCommand command, boolean approval) {
        return StructuredPolicy.builder("command-v1")
                .tool("report", new ToolRule(Set.of("execute"), command.parameters(), approval));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "context", "async"})
    void allEntryPointsExecuteOnceAndRedactActualOutput(String mode) throws Exception {
        var command = command();
        var policy =
                policy(command, false)
                        .content(
                                new ContentRule(
                                        "args",
                                        Set.of(InterventionPoint.PRE_TOOL_CALL),
                                        List.of(),
                                        Map.of("old", "secret-result")))
                        .content(
                                new ContentRule(
                                        "output",
                                        Set.of(InterventionPoint.POST_TOOL_CALL),
                                        List.of(),
                                        Map.of("secret-result", "masked")))
                        .build();
        var records = new ArrayList<AuditRecord>();
        try (var control = AgentControl.builder(policy).audit(records::add).build();
                var session = control.openSession(context(true))) {
            var executor = new LangChain4jGovernance(control).guardCommand(command);
            var request = request("{\"mode\":\"old\"}");
            var invocation = InvocationContext.builder().chatMemoryId("host-memory").build();
            String result =
                    switch (mode) {
                        case "context" ->
                                executor.executeWithContext(request, invocation).resultText();
                        case "async" ->
                                executor.executeAsync(request, invocation)
                                        .get(5, TimeUnit.SECONDS)
                                        .resultText();
                        default -> executor.execute(request, null);
                    };
            assertEquals("masked", result);
            assertEquals(1, session.budgetSnapshot().toolCalls());
            assertTrue(Files.exists(directory.resolve("effect")));
        }
        assertFalse(records.toString().contains("secret-result"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"mode\":\"; touch injected\"}",
                "{\"mode\":\"--exec\"}",
                "{\"mode\":\"old\",\"command\":\"sh\"}",
                "{\"mode\":\"old\",\"mode\":\"old\"}",
                "not-json"
            })
    void malformedAndInjectedArgumentsHaveZeroProcessEffects(String input) throws Exception {
        var command = command();
        try (var control =
                        AgentControl.builder(policy(command, false).build())
                                .audit(record -> {})
                                .build();
                var session = control.openSession(context(true))) {
            var executor = new LangChain4jGovernance(control).guardCommand(command);
            assertThrows(GovernanceException.class, () -> executor.execute(request(input), null));
            assertEquals(0, session.budgetSnapshot().toolCalls());
            assertTrue(session.budgetSnapshot().failed());
        }
        assertFalse(Files.exists(directory.resolve("effect")));
    }

    @Test
    void permissionsAndMissingApprovalRefuseWithoutStarting() throws Exception {
        var command = command();
        for (boolean allowed : List.of(false, true)) {
            try (var control =
                            AgentControl.builder(policy(command, true).build())
                                    .audit(record -> {})
                                    .build();
                    var session = control.openSession(context(allowed))) {
                assertThrows(
                        GovernanceException.class,
                        () ->
                                new LangChain4jGovernance(control)
                                        .guardCommand(command)
                                        .execute(request("{\"mode\":\"old\"}"), null));
                assertEquals(0, session.budgetSnapshot().toolCalls());
            }
        }
        assertFalse(Files.exists(directory.resolve("effect")));
    }

    @Test
    void approvalDenialAndAllowanceBindToEffectiveArguments() throws Exception {
        var command = command();
        for (boolean allowed : List.of(false, true)) {
            var policy =
                    policy(command, true)
                            .content(
                                    new ContentRule(
                                            "change",
                                            Set.of(InterventionPoint.PRE_TOOL_CALL),
                                            List.of(),
                                            Map.of("old", "secret-result")))
                            .build();
            try (var control =
                            AgentControl.builder(policy)
                                    .audit(record -> {})
                                    .approvalResolver(
                                            r -> {
                                                assertEquals(
                                                        "secret-result",
                                                        ((Map<?, ?>) r.action().target())
                                                                .get("mode"));
                                                return java.util.concurrent.CompletableFuture
                                                        .completedFuture(
                                                                allowed
                                                                        ? ApprovalResponse.allow(r)
                                                                        : ApprovalResponse.deny(r));
                                            })
                                    .build();
                    var session = control.openSession(context(true))) {
                var executor = new LangChain4jGovernance(control).guardCommand(command);
                if (allowed) {
                    assertEquals(
                            "secret-result", executor.execute(request("{\"mode\":\"old\"}"), null));
                } else {
                    assertThrows(
                            GovernanceException.class,
                            () -> executor.execute(request("{\"mode\":\"old\"}"), null));
                    assertFalse(Files.exists(directory.resolve("effect")));
                }
            }
        }
    }

    @Test
    void permissivePolicyCannotBypassCommandTemplate() throws Exception {
        var command = command();
        var loose =
                StructuredPolicy.builder("loose")
                        .tool(
                                "report",
                                new ToolRule(
                                        Set.of(),
                                        ParameterRule.object(
                                                Map.of("mode", ParameterRule.string(null)), "mode"),
                                        false))
                        .build();
        try (var control = AgentControl.builder(loose).audit(record -> {}).build();
                var session = control.openSession(context(true))) {
            assertEquals(
                    "command_arguments_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            new LangChain4jGovernance(control)
                                                    .guardCommand(command)
                                                    .execute(
                                                            request(
                                                                    "{\"mode\":\"arbitrary-code\"}"),
                                                            null))
                            .code());
            assertEquals(0, session.budgetSnapshot().toolCalls());
        }
        assertFalse(Files.exists(directory.resolve("effect")));
    }

    @Test
    void realAiServiceReceivesOnlyRedactedProcessResult() throws Exception {
        var command = command();
        var calls = new AtomicInteger();
        ChatModel model = model(calls, "{\"mode\":\"secret-result\"}");
        var policy =
                policy(command, false)
                        .content(
                                new ContentRule(
                                        "redact",
                                        Set.of(InterventionPoint.POST_TOOL_CALL),
                                        List.of(),
                                        Map.of("secret-result", "masked")))
                        .build();
        try (var control = AgentControl.builder(policy).audit(record -> {}).build();
                var session = control.openSession(context(true))) {
            var assistant =
                    new LangChain4jGovernance(control)
                            .aiServices(Assistant.class)
                            .chatModel(model)
                            .commandTool(command)
                            .build();
            assertEquals("masked", assistant.chat("生成报告"));
            assertEquals(2, calls.get());
            assertEquals(1, session.budgetSnapshot().toolCalls());
        }
    }

    @Test
    void frameworkErrorHandlerCannotResumeAfterCommandRefusal() throws Exception {
        var command = command();
        var calls = new AtomicInteger();
        try (var control =
                        AgentControl.builder(policy(command, false).build())
                                .audit(record -> {})
                                .build();
                var session = control.openSession(context(true))) {
            var assistant =
                    new LangChain4jGovernance(control)
                            .aiServices(Assistant.class)
                            .chatModel(model(calls, "{\"mode\":\"arbitrary-code\"}"))
                            .commandTool(command)
                            .toolExecutionErrorHandler(
                                    (e, ctx) -> ToolErrorHandlerResult.text("ignored"))
                            .build();
            assertThrows(RuntimeException.class, () -> assistant.chat("运行"));
            assertEquals(1, calls.get());
            assertEquals(0, session.budgetSnapshot().toolCalls());
        }
        assertFalse(Files.exists(directory.resolve("effect")));
    }

    @Test
    void auditFailureBeforeCommandPreventsProcessStart() throws Exception {
        var command = command();
        try (var control =
                        AgentControl.builder(policy(command, false).build())
                                .audit(
                                        record -> {
                                            if (record.point() == InterventionPoint.PRE_TOOL_CALL) {
                                                throw new java.io.IOException(
                                                        "private audit detail");
                                            }
                                        })
                                .build();
                var session = control.openSession(context(true))) {
            assertEquals(
                    "audit_failed",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            new LangChain4jGovernance(control)
                                                    .guardCommand(command)
                                                    .execute(request("{\"mode\":\"old\"}"), null))
                            .code());
            assertEquals(0, session.budgetSnapshot().toolCalls());
            assertThrows(GovernanceException.class, session::close);
        }
        assertFalse(Files.exists(directory.resolve("effect")));
    }

    @Test
    void changingExecutableDuringApprovalIsDetectedBeforeExecutionCount() throws Exception {
        Path program = directory.resolve("program");
        Files.writeString(program, "trusted");
        assertTrue(program.toFile().setExecutable(true));
        var command =
                new FixedCommand(
                        "report",
                        program,
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(Files.readAllBytes(program))),
                        directory,
                        List.of(CommandArgument.choice("mode", "old")),
                        CommandLimits.DEFAULT);
        try (var control =
                        AgentControl.builder(policy(command, true).build())
                                .audit(record -> {})
                                .approvalResolver(
                                        r -> {
                                            try {
                                                Files.writeString(program, "changed");
                                            } catch (Exception e) {
                                                return java.util.concurrent.CompletableFuture
                                                        .failedFuture(e);
                                            }
                                            return java.util.concurrent.CompletableFuture
                                                    .completedFuture(ApprovalResponse.allow(r));
                                        })
                                .build();
                var session = control.openSession(context(true))) {
            assertEquals(
                    "command_integrity_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            new LangChain4jGovernance(control)
                                                    .guardCommand(command)
                                                    .execute(request("{\"mode\":\"old\"}"), null))
                            .code());
            assertEquals(0, session.budgetSnapshot().toolCalls());
        }
    }

    @Test
    void observationModeStillCannotRunUnregisteredArguments() throws Exception {
        var command = command();
        try (var control =
                        AgentControl.builder(policy(command, false).build())
                                .audit(record -> {})
                                .mode(io.agt.core.EnforcementMode.EVALUATE_ONLY)
                                .build();
                var session = control.openSession(context(false))) {
            assertThrows(
                    GovernanceException.class,
                    () ->
                            new LangChain4jGovernance(control)
                                    .guardCommand(command)
                                    .execute(request("{\"mode\":\"arbitrary-script\"}"), null));
            assertEquals(0, session.budgetSnapshot().toolCalls());
        }
        assertFalse(Files.exists(directory.resolve("effect")));
    }

    @Test
    void asyncCancellationStopsOwnedProcessAndKeepsExecutionCount() throws Exception {
        var command = command();
        try (var control =
                        AgentControl.builder(policy(command, false).build())
                                .audit(record -> {})
                                .build();
                var session = control.openSession(context(true))) {
            var result =
                    new LangChain4jGovernance(control)
                            .guardCommand(command)
                            .executeAsync(
                                    request("{\"mode\":\"wait\"}"),
                                    InvocationContext.builder().build());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(directory.resolve("effect")) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(directory.resolve("effect")));
            long pid = Long.parseLong(Files.readString(directory.resolve("effect")));
            result.cancel(true);
            long cleanup = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                    && System.nanoTime() < cleanup) {
                Thread.sleep(10);
            }
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertEquals(1, session.budgetSnapshot().toolCalls());
            assertTrue(session.budgetSnapshot().failed());
        }
    }

    @Test
    void postExecutionAuditFailureWithholdsOutputButCannotUndoProcessEffect() throws Exception {
        var command = command();
        try (var control =
                        AgentControl.builder(policy(command, false).build())
                                .audit(
                                        record -> {
                                            if (record.point()
                                                    == InterventionPoint.POST_TOOL_CALL) {
                                                throw new java.io.IOException(
                                                        "private audit detail");
                                            }
                                        })
                                .build();
                var session = control.openSession(context(true))) {
            assertEquals(
                    "audit_failed",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            new LangChain4jGovernance(control)
                                                    .guardCommand(command)
                                                    .execute(request("{\"mode\":\"old\"}"), null))
                            .code());
            assertEquals(1, session.budgetSnapshot().toolCalls());
            assertTrue(Files.exists(directory.resolve("effect")));
            assertThrows(GovernanceException.class, session::close);
        }
    }

    private static ChatModel model(AtomicInteger calls, String args) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
                calls.incrementAndGet();
                var last = request.messages().get(request.messages().size() - 1);
                return ChatResponse.builder()
                        .aiMessage(
                                last instanceof ToolExecutionResultMessage result
                                        ? AiMessage.from(result.text())
                                        : AiMessage.from(request(args)))
                        .build();
            }
        };
    }
}
