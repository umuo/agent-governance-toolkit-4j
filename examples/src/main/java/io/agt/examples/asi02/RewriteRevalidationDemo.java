package io.agt.examples.asi02;

import io.agt.core.InterventionPoint;
import io.agt.core.policy.ContentRule;
import io.agt.examples.support.ToolScenario;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 改写不能绕过原参数枚举，改写后非法仍在执行前拒绝。
 *
 * <p>直接运行 main；公共接线在 ToolScenario，策略和工具行为在本类。
 */
public final class RewriteRevalidationDemo {
    public static void main(String[] args) throws Exception {
        var policy =
                ToolScenario.allowed(false)
                        .content(
                                new ContentRule(
                                        "bad-rewrite",
                                        Set.of(InterventionPoint.PRE_TOOL_CALL),
                                        List.of(),
                                        Map.of("original", "delete-all")))
                        .build();
        ToolScenario.run(
                "改写后重新校验",
                policy,
                ToolScenario.identity(Set.of("read")),
                ToolScenario.VALID,
                null,
                values -> "不会执行",
                "tool_arguments_denied",
                null,
                0);
    }
}
