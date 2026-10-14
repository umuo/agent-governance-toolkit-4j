package io.agt.examples.support;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.JsonValues;
import io.agt.core.TrustedContext;
import io.agt.core.approval.ApprovalResolver;
import io.agt.core.audit.AuditRecord;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.langchain4j.LangChain4jGovernance;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具演示的公共接线：真实 guardTool、可信会话、审计与验收。
 *
 * <p>场景类负责策略和工具业务；这里不模拟治理裁决。高级装饰器覆盖工具前后，不代表整个模型循环。
 */
public final class ToolScenario {
    private ToolScenario() {}

    /** 模拟模型送来的参数，工具必须只使用治理后的有效参数。 */
    public static final String VALID = "{\"tenant\":\"tenant-a\",\"query\":\"original\"}";

    /** 宿主认证入口提供身份；模型 JSON 中的 tenant 只用于与此身份比较。 */
    public static TrustedContext identity(Set<String> permissions) {
        return new TrustedContext("learning-agent", "tenant-a", "user-a", permissions);
    }

    public static ParameterRule parameters() {
        return ParameterRule.object(
                Map.of(
                        "tenant",
                        ParameterRule.string("tenantId"),
                        "query",
                        new ParameterRule(
                                ParameterRule.Type.STRING,
                                null,
                                null,
                                List.of("original", "modified"),
                                null,
                                null,
                                1,
                                32,
                                null,
                                null)),
                "tenant",
                "query");
    }

    public static StructuredPolicy.Builder allowed(boolean approval) {
        return StructuredPolicy.builder("asi02-learning-v1")
                .tool("lookup", new ToolRule(Set.of("read"), parameters(), approval));
    }

    /** 工具体收到有效参数，effects 在工具体入口计数，不等同于审计记录数。 */
    @FunctionalInterface
    public interface Body {
        String execute(Map<String, Object> arguments) throws Exception;
    }

    public static void run(
            String scenario,
            StructuredPolicy policy,
            TrustedContext identity,
            String arguments,
            ApprovalResolver approval,
            Body body,
            String expectedCode,
            String expectedResult,
            int expectedEffects) {
        var records = new ArrayList<AuditRecord>();
        var effects = new AtomicInteger();
        var builder =
                AgentControl.builder(policy)
                        .audit(records::add)
                        .approvalTimeout(Duration.ofMillis(500));
        if (approval != null) {
            builder.approvalResolver(approval);
        }
        try (var control = builder.build();
                var session = control.openSession(identity)) {
            var tool =
                    new LangChain4jGovernance(control)
                            .guardTool(
                                    "lookup",
                                    (request, memoryId) -> {
                                        effects.incrementAndGet();
                                        try {
                                            return body.execute(
                                                    JsonValues.parseObject(request.arguments()));
                                        } catch (RuntimeException error) {
                                            throw error;
                                        } catch (Exception error) {
                                            throw new IllegalStateException("演示工具执行失败", error);
                                        }
                                    });
            String result = null;
            String code = null;
            var request =
                    ToolExecutionRequest.builder()
                            .id("learning-call")
                            .name("lookup")
                            .arguments(arguments)
                            .build();
            try {
                result = tool.execute(request, null);
            } catch (GovernanceException error) {
                code = error.code();
            }
            if (!Objects.equals(code, expectedCode)
                    || !Objects.equals(result, expectedResult)
                    || effects.get() != expectedEffects) {
                throw new IllegalStateException("场景验收失败：" + scenario + "，实际错误码：" + code);
            }
            // 输出默认不含参数和敏感工具结果；审计只展示关联字段，方便下断点观察。
            System.out.printf(
                    "%s 错误码=%s 工具体进入次数=%d 工具预算=%d 会话失败=%s%n",
                    scenario,
                    code,
                    effects.get(),
                    session.budgetSnapshot().toolCalls(),
                    session.failureCode());
            records.stream()
                    .filter(
                            r ->
                                    r.point() == InterventionPoint.PRE_TOOL_CALL
                                            || r.point() == InterventionPoint.POST_TOOL_CALL)
                    .forEach(
                            r ->
                                    System.out.printf(
                                            "  干预点=%s 裁决=%s 改写生效=%s 审批=%s 执行=%s 策略=%s 动作=%s%n",
                                            r.point(),
                                            r.verdict(),
                                            r.transformApplied(),
                                            r.approvalOutcome(),
                                            r.executionOutcome(),
                                            r.policyVersion(),
                                            r.actionId()));
        }
    }
}
