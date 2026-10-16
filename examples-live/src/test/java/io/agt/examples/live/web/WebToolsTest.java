package io.agt.examples.live.web;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.agt.core.*;
import io.agt.core.approval.ApprovalResponse;
import io.agt.examples.live.config.AgentPolicy;
import io.agt.examples.live.tools.BashTool;
import io.agt.langchain4j.LangChain4jGovernance;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 审批成功后的工具错误必须保留具体治理代码，不能被反射包装丢失。 */
class WebToolsTest {
    @TempDir Path workspace;

    public static class UnavailableTool {
        @Tool(name = "Bash")
        public String execute(String command) {
            throw new GovernanceException("sandbox_unavailable");
        }
    }

    public static class ExitTool {
        @Tool(name = "Bash")
        public String execute(String command) {
            var error = new GovernanceException("sandbox_exit_denied");
            error.addSuppressed(new BashTool.CommandFailed(6));
            throw error;
        }
    }

    @Test
    void capturesExitDiagnosticBeforeGovernanceReplacesException() throws Exception {
        try (var control = control();
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "test",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions("agent", true)))) {
            var first = new java.util.concurrent.atomic.AtomicReference<ToolFailure>();
            var registered =
                    WebTools.register(
                            new ExitTool(),
                            new AgentEvents(new SseEmitter()),
                            new LangChain4jGovernance(control),
                            first);
            var error =
                    assertThrows(
                            GovernanceException.class,
                            () ->
                                    registered
                                            .values()
                                            .iterator()
                                            .next()
                                            .execute(
                                                    ToolExecutionRequest.builder()
                                                            .id("exit-test")
                                                            .name("Bash")
                                                            .arguments(
                                                                    "{\"command\":\"curl example.invalid\"}")
                                                            .build(),
                                                    null));
            assertEquals("sandbox_exit_denied", error.code());
            assertEquals("sandbox_exit_denied", session.failureCode());
            assertEquals("bash_command_failed", first.get().code());
            assertTrue(first.get().message().contains("退出码：6"));
            assertTrue(first.get().hint().contains("DENY"));
        }
    }

    @Test
    void preservesReflectedGovernanceFailureAfterApproval() throws Exception {
        try (var control = control();
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "test",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions("agent", true)))) {
            var error =
                    assertThrows(
                            GovernanceException.class,
                            () -> execute(new UnavailableTool(), control));
            assertEquals("sandbox_unavailable", error.code());
            assertEquals("sandbox_unavailable", session.failureCode());
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void nativeStrictBashUsesSameWebRegistration() throws Exception {
        try (var control = control();
                var bash = new BashTool(control, workspace, false);
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "test",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions("agent", true)))) {
            try {
                assertEquals("ok", WebTools.checkedReport(execute(bash, control)).get("stdout"));
                System.out.println("web/Bash strict result=ok");
            } catch (GovernanceException error) {
                assertEquals("sandbox_unavailable", error.code());
                assertFalse(Files.exists(workspace.resolve("out")));
                System.out.println("web/Bash approved=true result=" + error.code());
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"allow", "redact", "deny", "redirect"})
    @EnabledOnOs(OS.MAC)
    void publishesRealStderrOnlyAfterToolResultCheck(String mode) throws Exception {
        var frames = new java.util.ArrayList<java.util.Map<?, ?>>();
        var emitter =
                new SseEmitter() {
                    @Override
                    public void send(SseEventBuilder event) {
                        for (var part : event.build()) {
                            if (part.getData() instanceof java.util.Map<?, ?> frame)
                                frames.add(frame);
                        }
                    }
                };
        var base = AgentPolicy.create("agent");
        PolicyEvaluator policy =
                new PolicyEvaluator() {
                    public String version() {
                        return base.version();
                    }

                    public java.util.Set<InterventionPoint> interventionPoints() {
                        return base.interventionPoints();
                    }

                    public boolean requiresApproval(PolicyRequest r) {
                        return base.requiresApproval(r);
                    }

                    public void validateEffective(PolicyRequest r) {
                        base.validateEffective(r);
                    }

                    public PolicyDecision evaluate(PolicyRequest r) {
                        if (r.point() == InterventionPoint.POST_TOOL_CALL) {
                            if (mode.equals("deny")) return PolicyDecision.deny("output_blocked");
                            if (mode.equals("redact"))
                                return PolicyDecision.transform(
                                        "redacted",
                                        JsonValues.parseObject(
                                                JsonValues.write(r.target())
                                                        .replace(
                                                                "agt-definitely-missing-file",
                                                                "masked-file")));
                        }
                        return base.evaluate(r);
                    }
                };
        try (var control =
                        AgentControl.builder(policy)
                                .audit(r -> {})
                                .approvalResolver(
                                        r ->
                                                CompletableFuture.completedFuture(
                                                        ApprovalResponse.allow(r)))
                                .build();
                var bash = new BashTool(control, workspace, true);
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "test",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions("agent", true)))) {
            var failure = new java.util.concurrent.atomic.AtomicReference<ToolFailure>();
            var executor =
                    WebTools.register(
                                    bash,
                                    new AgentEvents(emitter),
                                    new LangChain4jGovernance(control),
                                    failure)
                            .values()
                            .iterator()
                            .next();
            var request =
                    ToolExecutionRequest.builder()
                            .id("stderr-test")
                            .name("Bash")
                            .arguments(
                                    JsonValues.write(
                                            java.util.Map.of(
                                                    "command",
                                                    "printf before; cat agt-definitely-missing-file"
                                                            + (mode.equals("redirect")
                                                                    ? " 2>/dev/null"
                                                                    : ""))))
                            .build();
            if (mode.equals("deny")) {
                assertEquals(
                        "output_blocked",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> executor.execute(request, null))
                                .code());
            } else {
                String returned = executor.execute(request, null);
                assertNotEquals(
                        0, ((Number) WebTools.checkedReport(returned).get("exitCode")).intValue());
                session.checkActive();
                assertNull(failure.get());
            }
            var error =
                    frames.stream()
                            .filter(f -> f.get("type").equals("tool_error"))
                            .findFirst()
                            .orElseThrow();
            var data = (java.util.Map<?, ?>) error.get("data");
            if (mode.equals("deny")) {
                assertFalse(frames.toString().contains("No such file or directory"));
                assertFalse(data.containsKey("stderr"));
                return;
            }
            if (mode.equals("redirect")) {
                assertEquals("", data.get("stderr"));
                assertTrue(String.valueOf(data.get("message")).contains("2>/dev/null"));
            } else {
                assertTrue(
                        String.valueOf(data.get("stderr"))
                                .contains(
                                        mode.equals("redact")
                                                ? "masked-file"
                                                : "agt-definitely-missing-file"));
                assertTrue(
                        String.valueOf(data.get("stderr")).contains("No such file or directory"));
                if (mode.equals("redact")) {
                    assertFalse(
                            String.valueOf(data.get("stderr"))
                                    .contains("agt-definitely-missing-file"));
                    assertFalse(
                            String.valueOf(data.get("message"))
                                    .contains("agt-definitely-missing-file"));
                }
            }
            assertEquals("before", data.get("stdout"));
            assertEquals(true, data.get("checked"));
            assertEquals(
                    1, frames.stream().filter(f -> f.get("type").equals("tool_error")).count());
            assertEquals(true, data.get("recoverable"));
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void agentReceivesFailedCommandThenRequestsFreshApprovalAndCompletes() throws Exception {
        var approvals = new java.util.concurrent.atomic.AtomicInteger();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var failure = new java.util.concurrent.atomic.AtomicReference<ToolFailure>();
        try (var control =
                        AgentControl.builder(AgentPolicy.create("agent"))
                                .audit(r -> {})
                                .approvalResolver(
                                        r -> {
                                            approvals.incrementAndGet();
                                            return CompletableFuture.completedFuture(
                                                    ApprovalResponse.allow(r));
                                        })
                                .build();
                var bash = new BashTool(control, workspace, true);
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "test",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions("agent", true)))) {
            var governance = new LangChain4jGovernance(control);
            dev.langchain4j.model.chat.ChatModel model =
                    new dev.langchain4j.model.chat.ChatModel() {
                        @Override
                        public dev.langchain4j.model.chat.response.ChatResponse doChat(
                                dev.langchain4j.model.chat.request.ChatRequest request) {
                            int round = calls.incrementAndGet();
                            if (round > 1) {
                                var last = request.messages().get(request.messages().size() - 1);
                                assertInstanceOf(
                                        dev.langchain4j.data.message.ToolExecutionResultMessage
                                                .class,
                                        last);
                                var report =
                                        WebTools.checkedReport(
                                                ((dev.langchain4j.data.message
                                                                        .ToolExecutionResultMessage)
                                                                last)
                                                        .text());
                                if (round == 2) {
                                    assertNotEquals(
                                            0, ((Number) report.get("exitCode")).intValue());
                                    assertTrue(
                                            String.valueOf(report.get("stderr"))
                                                    .contains("No such file or directory"));
                                } else {
                                    assertEquals(0, ((Number) report.get("exitCode")).intValue());
                                    assertEquals("recovered", report.get("stdout"));
                                }
                            }
                            var message =
                                    round < 3
                                            ? dev.langchain4j.data.message.AiMessage.from(
                                                    ToolExecutionRequest.builder()
                                                            .id("round-" + round)
                                                            .name("Bash")
                                                            .arguments(
                                                                    JsonValues.write(
                                                                            java.util.Map.of(
                                                                                    "command",
                                                                                    round == 1
                                                                                            ? "cat agt-missing-input"
                                                                                            : "printf recovered")))
                                                            .build())
                                            : dev.langchain4j.data.message.AiMessage.from(
                                                    "已根据错误继续处理并完成。");
                            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                                    .aiMessage(message)
                                    .build();
                        }
                    };
            var assistant =
                    dev.langchain4j.service.AiServices.builder(
                                    io.agt.examples.live.GeneralAgentDemo.Assistant.class)
                            .chatModel(governance.guardModel(model))
                            .tools(
                                    WebTools.register(
                                            bash,
                                            new AgentEvents(new SseEmitter()),
                                            governance,
                                            failure))
                            .toolExecutionErrorHandler(
                                    (e, c) -> {
                                        throw new GovernanceException("unexpected_tool_failure");
                                    })
                            .build();
            String answer =
                    session.run(
                            "完成任务",
                            input -> assistant.chat((String) input),
                            text -> text,
                            (original, effective) -> (String) effective);
            assertEquals("已根据错误继续处理并完成。", answer);
            assertEquals(3, calls.get());
            assertEquals(2, approvals.get());
            assertNull(failure.get());
            assertFalse(session.budgetSnapshot().failed());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"allow", "redact", "deny"})
    void recoverableFileErrorsStillPassOutputGovernance(String mode) throws Exception {
        var frames = new java.util.ArrayList<java.util.Map<?, ?>>();
        var emitter =
                new SseEmitter() {
                    @Override
                    public void send(SseEventBuilder event) {
                        for (var part : event.build()) {
                            if (part.getData() instanceof java.util.Map<?, ?> frame)
                                frames.add(frame);
                        }
                    }
                };
        var base = AgentPolicy.create("agent");
        PolicyEvaluator policy =
                new PolicyEvaluator() {
                    public String version() {
                        return base.version();
                    }

                    public java.util.Set<InterventionPoint> interventionPoints() {
                        return base.interventionPoints();
                    }

                    public boolean requiresApproval(PolicyRequest r) {
                        return base.requiresApproval(r);
                    }

                    public void validateEffective(PolicyRequest r) {
                        base.validateEffective(r);
                    }

                    public PolicyDecision evaluate(PolicyRequest r) {
                        if (r.point() == InterventionPoint.POST_TOOL_CALL) {
                            if (mode.equals("deny")) return PolicyDecision.deny("output_blocked");
                            if (mode.equals("redact"))
                                return PolicyDecision.transform(
                                        "redacted",
                                        JsonValues.parseObject(
                                                JsonValues.write(r.target())
                                                        .replace(
                                                                "file_not_found", "masked_error")));
                        }
                        return base.evaluate(r);
                    }
                };
        try (var control = AgentControl.builder(policy).audit(r -> {}).build();
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "test",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions("agent", false)))) {
            var failure = new java.util.concurrent.atomic.AtomicReference<ToolFailure>();
            var registered =
                    WebTools.register(
                            new io.agt.examples.live.tools.WorkspaceTools(workspace),
                            new AgentEvents(emitter),
                            new LangChain4jGovernance(control),
                            failure);
            var executor =
                    registered.entrySet().stream()
                            .filter(e -> e.getKey().name().equals("listFiles"))
                            .findFirst()
                            .orElseThrow()
                            .getValue();
            var request =
                    ToolExecutionRequest.builder()
                            .id("missing-dir")
                            .name("listFiles")
                            .arguments("{\"path\":\"workspace\"}")
                            .build();
            if (mode.equals("deny")) {
                assertEquals(
                        "output_blocked",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> executor.execute(request, null))
                                .code());
                assertEquals("output_blocked", failure.get().code());
                assertFalse(frames.toString().contains("file_not_found"));
            } else {
                String result = executor.execute(request, null);
                assertTrue(
                        result.contains(mode.equals("redact") ? "masked_error" : "file_not_found"));
                session.checkActive();
                assertNull(failure.get());
                if (mode.equals("redact")) {
                    assertFalse(frames.toString().contains("file_not_found"));
                    assertTrue(frames.stream().anyMatch(f -> f.get("type").equals("tool_result")));
                } else {
                    var error =
                            frames.stream()
                                    .filter(f -> f.get("type").equals("tool_error"))
                                    .findFirst()
                                    .orElseThrow();
                    assertEquals(
                            true, ((java.util.Map<?, ?>) error.get("data")).get("recoverable"));
                }
                executor.execute(
                        ToolExecutionRequest.builder()
                                .id("root-dir")
                                .name("listFiles")
                                .arguments("{\"path\":\".\"}")
                                .build(),
                        null);
                session.checkActive();
            }
        }
    }

    AgentControl control() {
        return AgentControl.builder(AgentPolicy.create("agent"))
                .audit(record -> {})
                .approvalResolver(
                        request ->
                                CompletableFuture.completedFuture(ApprovalResponse.allow(request)))
                .build();
    }

    String execute(Object object, AgentControl control) {
        var registered =
                WebTools.register(
                        object,
                        new AgentEvents(new SseEmitter()),
                        new LangChain4jGovernance(control),
                        new java.util.concurrent.atomic.AtomicReference<>());
        var executor = registered.values().iterator().next();
        return executor.execute(
                ToolExecutionRequest.builder()
                        .id("test-bash")
                        .name("Bash")
                        .arguments("{\"command\":\"printf ok > out; printf ok\"}")
                        .build(),
                null);
    }
}
