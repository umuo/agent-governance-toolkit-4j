package io.agt.examples;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
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
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.langchain4j.LangChain4jGovernance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * ASI05 固定命令演示。
 *
 * <p>使用本机 JDK 启动宿主固定的演示类，不接受源码、命令或任意脚本。
 */
public final class CommandGovernanceDemo {
    /** 固定受信任的报告程序，演示子进程超时和实际启动标记。 */
    public static final class Program {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1]), "started");
            if (args[0].equals("wait")) {
                Thread.sleep(60000);
            }
            System.out.print("secret-report");
        }
    }

    public static void main(String[] args) {
        String requested = args.length == 0 ? "all" : args[0];
        var scenarios =
                List.of(
                        "allowed",
                        "denied",
                        "injection",
                        "approval",
                        "approval-denied",
                        "redact",
                        "timeout");
        if (!requested.equals("all") && !scenarios.contains(requested)) {
            throw new IllegalArgumentException("Unknown command scenario");
        }
        for (var scenario : requested.equals("all") ? scenarios : List.of(requested)) {
            try {
                run(scenario);
            } catch (Exception e) {
                throw new IllegalStateException("Command demo failed");
            }
        }
    }

    private static void run(String scenario) throws Exception {
        Path temporary = Files.createTempDirectory("agt-command-demo-");
        try {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
            Path classes =
                    Path.of(
                                    Program.class
                                            .getProtectionDomain()
                                            .getCodeSource()
                                            .getLocation()
                                            .toURI())
                            .toRealPath();
            var command =
                    new FixedCommand(
                            "report",
                            java,
                            HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(Files.readAllBytes(java))),
                            temporary,
                            List.of(
                                    CommandArgument.literal("-cp"),
                                    CommandArgument.literal(classes.toString()),
                                    CommandArgument.literal(Program.class.getName()),
                                    CommandArgument.choice("mode", "report", "wait"),
                                    CommandArgument.literal(
                                            temporary.resolve("effect").toString())),
                            new CommandLimits(scenario.equals("timeout") ? 800 : 5000, 65536, 1));
            var builder =
                    StructuredPolicy.builder("command-demo-v1")
                            .tool(
                                    "report",
                                    new ToolRule(
                                            Set.of("command:report"),
                                            command.parameters(),
                                            scenario.startsWith("approval")));
            if (scenario.equals("redact")) {
                builder.content(
                        new ContentRule(
                                "redact",
                                Set.of(InterventionPoint.POST_TOOL_CALL),
                                List.of(),
                                Map.of("secret-report", "masked-report")));
            }
            var records = new ArrayList<AuditRecord>();
            try (var control =
                            AgentControl.builder(builder.build())
                                    .audit(records::add)
                                    .approvalResolver(
                                            request ->
                                                    CompletableFuture.completedFuture(
                                                            scenario.equals("approval")
                                                                    ? ApprovalResponse.allow(
                                                                            request)
                                                                    : ApprovalResponse.deny(
                                                                            request)))
                                    .build();
                    var session =
                            control.openSession(
                                    new TrustedContext(
                                            "demo-agent",
                                            "demo-tenant",
                                            "demo-user",
                                            scenario.equals("denied")
                                                    ? Set.of()
                                                    : Set.of("command:report")))) {
                var executor = new LangChain4jGovernance(control).guardCommand(command);
                String mode =
                        scenario.equals("injection")
                                ? "; touch injected"
                                : scenario.equals("timeout") ? "wait" : "report";
                String result;
                try {
                    result =
                            executor.execute(
                                    ToolExecutionRequest.builder()
                                            .id("demo-call")
                                            .name("report")
                                            .arguments(
                                                    io.agt.core.JsonValues.write(
                                                            Map.of("mode", mode)))
                                            .build(),
                                    null);
                } catch (GovernanceException e) {
                    result = e.code();
                }
                boolean started = Files.exists(temporary.resolve("effect"));
                boolean shouldStart =
                        !List.of("denied", "injection", "approval-denied").contains(scenario);
                if (started != shouldStart
                        || (scenario.equals("redact") && !result.equals("masked-report"))
                        || (scenario.equals("timeout") && !result.equals("command_timeout"))) {
                    throw new IllegalStateException("Unexpected command behavior");
                }
                System.out.printf(
                        "command/%s result=%s started=%s toolCalls=%d%n",
                        scenario, result, started, session.budgetSnapshot().toolCalls());
            }
        } finally {
            Files.deleteIfExists(temporary.resolve("effect"));
            Files.deleteIfExists(temporary);
        }
    }
}
