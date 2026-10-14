package io.agt.examples.asi05;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.policy.StructuredPolicy;
import io.agt.examples.support.ToolScenario;
import io.agt.langchain4j.LangChain4jGovernance;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型请求未获策略许可的执行工具时，执行器根本不会进入。
 *
 * <p>仅用计数器表示危险执行器，没有真的启动模型生成的命令。
 */
public final class UnapprovedExecutionToolDemo {
    public static void main(String[] args) {
        var effects = new AtomicInteger();
        var policy = StructuredPolicy.builder("no-exec-v1").build();
        try (var control = AgentControl.builder(policy).audit(r -> {}).build();
                var session = control.openSession(ToolScenario.identity(Set.of("execute")))) {
            var executor =
                    new LangChain4jGovernance(control)
                            .guardTool(
                                    "exec",
                                    (request, memory) -> {
                                        effects.incrementAndGet();
                                        return "不会执行";
                                    });
            try {
                executor.execute(
                        ToolExecutionRequest.builder()
                                .id("unapproved-exec")
                                .name("exec")
                                .arguments("{\"command\":\"synthetic-code\"}")
                                .build(),
                        null);
                throw new IllegalStateException("未授权执行工具被放行");
            } catch (GovernanceException error) {
                if (!"tool_not_allowed".equals(error.code())
                        || effects.get() != 0
                        || session.budgetSnapshot().toolCalls() != 0) {
                    throw new IllegalStateException("阻断验收失败");
                }
                System.out.println("tool_not_allowed：执行器进入=0，工具预算=0。");
            }
        }
    }
}
