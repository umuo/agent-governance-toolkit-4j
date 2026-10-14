package io.agt.examples.asi02;

import io.agt.core.InterventionPoint;
import io.agt.core.policy.ContentRule;
import io.agt.examples.support.ToolScenario;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 改写后的参数实际到达工具，工具结果脱敏后才交付。
 *
 * <p>直接运行 main；公共接线在 ToolScenario，策略和工具行为在本类。
 */
public final class ArgumentRewriteDemo {
    public static void main(String[] args) throws Exception {
        var policy =
                ToolScenario.allowed(false)
                        .content(
                                new ContentRule(
                                        "rewrite",
                                        Set.of(InterventionPoint.PRE_TOOL_CALL),
                                        List.of(),
                                        Map.of("original", "modified")))
                        .content(
                                new ContentRule(
                                        "redact",
                                        Set.of(InterventionPoint.POST_TOOL_CALL),
                                        List.of(),
                                        Map.of("secret", "masked")))
                        .build();
        ToolScenario.run(
                "参数改写与结果脱敏",
                policy,
                ToolScenario.identity(Set.of("read")),
                ToolScenario.VALID,
                null,
                values -> {
                    if (!"modified".equals(values.get("query"))) {
                        throw new IllegalStateException("工具仍收到了原参数");
                    }
                    return "secret:" + values.get("query");
                },
                null,
                "masked:modified",
                1);
    }
}
