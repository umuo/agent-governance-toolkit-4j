package io.agt.examples.live;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.JsonValues;
import io.agt.core.TrustedContext;
import io.agt.core.approval.ApprovalResponse;
import io.agt.examples.live.config.AgentPolicy;
import io.agt.examples.live.tools.BashTool;
import io.agt.langchain4j.LangChain4jGovernance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 原生严格沙箱探测：后端不支持时只接受明确拒绝，禁止宿主进程回退。 */
@EnabledOnOs(OS.MAC)
class BashToolTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void approvedScriptAndOutsideWrite(boolean hostReads) throws Exception {
        var work = Files.createDirectory(root.resolve("work"));
        var outside = root.resolve("canary");
        Files.writeString(outside, "unchanged");
        try (var control =
                        AgentControl.builder(AgentPolicy.create("agent"))
                                .audit(r -> {})
                                .approvalResolver(
                                        r ->
                                                CompletableFuture.completedFuture(
                                                        ApprovalResponse.allow(r)))
                                .build();
                var bash = new BashTool(control, work, hostReads);
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "agent",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions("agent", true)))) {
            var tool =
                    new LangChain4jGovernance(control)
                            .guardTool(
                                    "Bash",
                                    (request, memory) ->
                                            bash.execute(
                                                    (String)
                                                            JsonValues.parseObject(
                                                                            request.arguments())
                                                                    .get("command")));
            try {
                assertEquals(
                        "ok", tool.execute(request("printf ok > output; /bin/cat output"), null));
                assertEquals("ok", Files.readString(work.resolve("output")));
                assertTrue(tool.execute(request("ls -la"), null).contains("output"));
                // alpha.2 严格环境将 HOME 指向每次执行的私有临时目录，结束后已回收。
                Path home = Path.of(tool.execute(request("printf '%s' \"$HOME\""), null));
                assertFalse(home.equals(work.toRealPath()));
                assertFalse(home.toString().equals(System.getenv("HOME")));
                assertFalse(Files.exists(home));
                System.out.println("live-agent Bash native: executed, hostReads=" + hostReads);
                assertEquals(
                        "unset",
                        tool.execute(request("printf '%s' \"${LLM_API_KEY-unset}\""), null));
                assertEquals(
                        "sandbox_exit_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                tool.execute(
                                                        request(
                                                                "printf changed > '"
                                                                        + outside
                                                                        + "'"),
                                                        null))
                                .code());
            } catch (GovernanceException error) {
                assertFalse(hostReads, "HOST native execution must succeed on verified macOS");
                assertTrue(
                        java.util.Set.of("sandbox_unavailable", "sandbox_policy_unsupported")
                                .contains(error.code()),
                        error.code());
                assertFalse(Files.exists(work.resolve("output")));
                System.out.println("live-agent Bash native: " + error.code());
            }
            assertEquals("unchanged", Files.readString(outside));
        }
    }

    @org.junit.jupiter.api.Test
    void hostBashRetainsExitCodeWithoutPublishingProcessOutput() throws Exception {
        var work = Files.createDirectory(root.resolve("diagnostics"));
        try (var control =
                        AgentControl.builder(AgentPolicy.create("agent")).audit(r -> {}).build();
                var bash = new BashTool(control, work, true);
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "agent",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions("agent", true)))) {
            var failure =
                    assertThrows(
                            GovernanceException.class,
                            () -> bash.execute("printf private-detail >&2; exit 6"));
            assertEquals("sandbox_exit_denied", failure.code());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(6, ((BashTool.CommandFailed) failure.getSuppressed()[0]).exitCode());
            assertFalse(failure.getSuppressed()[0].getMessage().contains("private-detail"));
        }
    }

    @org.junit.jupiter.api.Test
    void reportPreservesStderrAndDoesNotInventRedirectedErrors() throws Exception {
        var work = Files.createDirectory(root.resolve("reports"));
        try (var control =
                        AgentControl.builder(AgentPolicy.create("agent")).audit(r -> {}).build();
                var bash = new BashTool(control, work, true);
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "agent",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions("agent", true)))) {
            var visible =
                    JsonValues.parseObject(
                            bash.executeForAgent("printf before; cat agt-definitely-missing-file"));
            assertEquals("before", visible.get("stdout"));
            assertTrue(String.valueOf(visible.get("stderr")).contains("No such file or directory"));
            assertNotEquals(0, ((Number) visible.get("exitCode")).intValue());
            var hidden =
                    JsonValues.parseObject(
                            bash.executeForAgent("cat agt-definitely-missing-file 2>/dev/null"));
            assertEquals("", hidden.get("stderr"));
            assertNotEquals(0, ((Number) hidden.get("exitCode")).intValue());
        }
    }

    @org.junit.jupiter.api.Test
    void strictWorkspaceBoundaryOrExplicitUnavailableWithoutFallback() throws Exception {
        var work = Files.createDirectory(root.resolve("strict-work"));
        var canary = root.resolve("outside-canary");
        Files.writeString(canary, "outside-secret-canary");
        Files.createSymbolicLink(work.resolve("escape-link"), canary);
        try (var control =
                        AgentControl.builder(AgentPolicy.create("agent")).audit(r -> {}).build();
                var bash = new BashTool(control, work, false);
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "agent",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions("agent", true)))) {
            try {
                var probe =
                        JsonValues.parseObject(
                                bash.executeForAgent(
                                        "printf ok > inside; /bin/cat inside; /bin/ls -la"));
                assertEquals(0, ((Number) probe.get("exitCode")).intValue());
                assertEquals("ok", Files.readString(work.resolve("inside")));
                for (String command :
                        java.util.List.of(
                                "/bin/cat ../outside-canary",
                                "/bin/cat '" + canary.toRealPath() + "'",
                                "/bin/cat escape-link",
                                "printf changed > ../outside-canary",
                                "printf changed > escape-link")) {
                    var report = JsonValues.parseObject(bash.executeForAgent(command));
                    assertNotEquals(0, ((Number) report.get("exitCode")).intValue());
                    assertFalse(
                            String.valueOf(report.get("stdout")).contains("outside-secret-canary"));
                    assertEquals("outside-secret-canary", Files.readString(canary));
                }
                System.out.println(
                        "strict workspace boundary: native read/write and escape cases verified");
            } catch (GovernanceException error) {
                assertTrue(
                        java.util.Set.of("sandbox_unavailable", "sandbox_policy_unsupported")
                                .contains(error.code()));
                assertFalse(Files.exists(work.resolve("inside")));
                assertEquals("outside-secret-canary", Files.readString(canary));
                System.out.println(
                        "strict workspace boundary: "
                                + error.code()
                                + ", no target execution or HOST fallback");
            }
        }
    }

    private ToolExecutionRequest request(String command) {
        return ToolExecutionRequest.builder()
                .id("bash-call")
                .name("Bash")
                .arguments(JsonValues.write(Map.of("command", command)))
                .build();
    }
}
