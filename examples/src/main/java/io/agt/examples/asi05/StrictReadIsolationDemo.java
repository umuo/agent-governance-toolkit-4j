package io.agt.examples.asi05;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandLimits;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.examples.support.CommandScenario;
import io.agt.examples.support.ToolScenario;
import io.agt.langchain4j.LangChain4jGovernance;
import io.agt.sandbox.SandboxCommandBackend;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/**
 * 严格读取 profile 不可用时拒绝，不能自动退回 HOST。
 *
 * <p>直接运行 main；只使用经审核的固定程序与合成数据，不运行模型生成代码。
 */
public final class StrictReadIsolationDemo {
    public static void main(String[] args) throws Exception {
        if (!CommandScenario.nativeMac()) {
            return;
        }
        try (var demo = new CommandScenario();
                var backend =
                        SandboxCommandBackend.strict(
                                SandboxClient.create(),
                                SandboxPolicy.builder(demo.work)
                                        .readPolicy(ReadPolicy.DECLARED_ONLY)
                                        .network(NetworkPolicy.DENY)
                                        .build())) {
            var command =
                    demo.command(
                            "/bin/sh",
                            List.of(
                                    CommandArgument.literal("-c"),
                                    CommandArgument.literal("printf started > started; cat \"$1\""),
                                    CommandArgument.literal("reader"),
                                    CommandArgument.literal(demo.canary.toString())),
                            CommandLimits.DEFAULT,
                            backend);
            var policy =
                    StructuredPolicy.builder("strict-read-v1")
                            .tool(
                                    "report",
                                    new ToolRule(Set.of("execute"), command.parameters(), false))
                            .build();
            try (var control = AgentControl.builder(policy).audit(r -> {}).build();
                    var session = control.openSession(ToolScenario.identity(Set.of("execute")))) {
                try {
                    new LangChain4jGovernance(control)
                            .guardCommand(command)
                            .execute(
                                    ToolExecutionRequest.builder()
                                            .id("strict-call")
                                            .name("report")
                                            .arguments("{}")
                                            .build(),
                                    null);
                    throw new IllegalStateException("未声明的文件读取却被允许");
                } catch (GovernanceException error) {
                    if (!Set.of("sandbox_unavailable", "sandbox_exit_denied")
                            .contains(error.code())) {
                        throw error;
                    }
                    if (error.code().equals("sandbox_unavailable")
                            && Files.exists(demo.work.resolve("started"))) {
                        throw new IllegalStateException("profile 不可用时仍启动了目标");
                    }
                    System.out.println("严格读取结果=" + error.code() + "，无 HOST 回退。");
                }
            }
        }
    }
}
