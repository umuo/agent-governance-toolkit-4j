package io.agt.examples;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.JsonValues;
import io.agt.core.TrustedContext;
import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandLimits;
import io.agt.core.execution.FixedCommand;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.langchain4j.LangChain4jGovernance;
import io.agt.sandbox.SandboxCommandBackend;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 当前 Central 沙箱 SDK 的离线治理演示。
 *
 * <p>HOST 读取由演示显式选择；严格路径失败不降级。只使用合成工作区和固定工具。
 */
public final class SandboxGovernanceDemo {
    public static void main(String[] args) {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            System.out.println(
                    "sandbox demo skipped: native scenarios currently verified on macOS");
            return;
        }
        var scenarios =
                List.of(
                        "host-allowed",
                        "async-allowed",
                        "permission-denied",
                        "strict",
                        "outside-write",
                        "output-limit",
                        "timeout");
        String requested = args.length == 0 ? "all" : args[0];
        if (!requested.equals("all") && !scenarios.contains(requested)) {
            throw new IllegalArgumentException("Unknown sandbox scenario");
        }
        for (String scenario : requested.equals("all") ? scenarios : List.of(requested)) {
            try {
                run(scenario);
            } catch (Exception error) {
                throw new IllegalStateException("Sandbox demo failed");
            }
        }
    }

    private static void run(String scenario) throws Exception {
        Path root = Files.createTempDirectory("agt-sandbox-demo-").toRealPath();
        Path work = Files.createDirectory(root.resolve("work"));
        Path outside = root.resolve("canary");
        Files.writeString(outside, "canary-only");
        boolean strict = scenario.equals("strict");
        var policy =
                SandboxPolicy.builder(work)
                        .readPolicy(strict ? ReadPolicy.DECLARED_ONLY : ReadPolicy.HOST)
                        .network(NetworkPolicy.DENY)
                        .build();
        try (var backend =
                strict
                        ? SandboxCommandBackend.strict(SandboxClient.create(), policy)
                        : SandboxCommandBackend.allowHostReads(SandboxClient.create(), policy)) {
            String binary = "/bin/sh";
            List<CommandArgument> arguments =
                    List.of(
                            CommandArgument.literal("-c"),
                            CommandArgument.literal("printf '%s' \"$1\" > out; printf '%s' \"$1\""),
                            CommandArgument.literal("report"),
                            CommandArgument.choice("mode", "old", "modified"));
            Map<String, Object> values = Map.of("mode", "old");
            if (scenario.equals("outside-write")) {
                arguments =
                        List.of(
                                CommandArgument.literal("-c"),
                                CommandArgument.literal("printf changed > \"$1\""),
                                CommandArgument.literal("report"),
                                CommandArgument.literal(outside.toString()));
                values = Map.of();
            } else if (scenario.equals("output-limit")) {
                binary = "/usr/bin/printf";
                arguments = List.of(CommandArgument.literal("x".repeat(1000)));
                values = Map.of();
            } else if (scenario.equals("timeout")) {
                binary = "/bin/sleep";
                arguments = List.of(CommandArgument.literal("2"));
                values = Map.of();
            }
            Path executable = Path.of(binary).toRealPath();
            var command =
                    new FixedCommand(
                            "report",
                            executable,
                            HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(Files.readAllBytes(executable))),
                            work,
                            arguments,
                            new CommandLimits(
                                    scenario.equals("timeout") ? 100 : 5000,
                                    scenario.equals("output-limit") ? 16 : 65536,
                                    1),
                            backend);
            var rules =
                    StructuredPolicy.builder("sandbox-demo-v1")
                            .tool(
                                    "report",
                                    new ToolRule(Set.of("execute"), command.parameters(), false))
                            .content(
                                    new ContentRule(
                                            "args",
                                            Set.of(InterventionPoint.PRE_TOOL_CALL),
                                            List.of(),
                                            Map.of("old", "modified")))
                            .content(
                                    new ContentRule(
                                            "result",
                                            Set.of(InterventionPoint.POST_TOOL_CALL),
                                            List.of(),
                                            Map.of("modified", "masked")))
                            .build();
            try (var control = AgentControl.builder(rules).audit(r -> {}).build();
                    var session =
                            control.openSession(
                                    new TrustedContext(
                                            "agent",
                                            "tenant",
                                            "user",
                                            scenario.equals("permission-denied")
                                                    ? Set.of()
                                                    : Set.of("execute")))) {
                var tool = new LangChain4jGovernance(control).guardCommand(command);
                var request =
                        ToolExecutionRequest.builder()
                                .id("sandbox-call")
                                .name("report")
                                .arguments(JsonValues.write(values))
                                .build();
                String result;
                try {
                    result =
                            scenario.equals("async-allowed")
                                    ? tool.executeAsync(
                                                    request, InvocationContext.builder().build())
                                            .get(10, TimeUnit.SECONDS)
                                            .resultText()
                                    : tool.execute(request, null);
                } catch (GovernanceException error) {
                    result = error.code();
                }
                boolean wrote = Files.exists(work.resolve("out"));
                String expected =
                        switch (scenario) {
                            case "permission-denied" -> "permission_denied";
                            case "outside-write" -> "sandbox_exit_denied";
                            case "output-limit" -> "sandbox_output_limit";
                            case "timeout" -> "sandbox_timeout";
                            default -> "masked";
                        };
                if (strict) {
                    if (!(result.equals("masked") && wrote)
                            && !(result.equals("sandbox_unavailable") && !wrote)) {
                        throw new IllegalStateException("Strict sandbox did not fail closed");
                    }
                } else if (!expected.equals(result)) {
                    throw new IllegalStateException("Unexpected sandbox result");
                }
                if (!Files.readString(outside).equals("canary-only")) {
                    throw new IllegalStateException("Outside write escaped sandbox");
                }
                System.out.printf(
                        "sandbox/%s result=%s workspaceWrite=%s toolCalls=%d%n",
                        scenario, result, wrote, session.budgetSnapshot().toolCalls());
            }
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(
                                path -> {
                                    try {
                                        Files.delete(path);
                                    } catch (Exception ignored) {
                                        /* 临时演示文件清理。 */
                                    }
                                });
            }
        }
    }
}
