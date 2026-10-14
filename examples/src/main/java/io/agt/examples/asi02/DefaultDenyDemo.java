package io.agt.examples.asi02;

import io.agt.core.policy.StructuredPolicy;
import io.agt.examples.support.ToolScenario;
import java.util.Set;

/**
 * 未显式许可的工具在执行前拒绝。
 *
 * <p>直接运行 main；公共接线在 ToolScenario，策略和工具行为在本类。
 */
public final class DefaultDenyDemo {
    public static void main(String[] args) throws Exception {
        // 注册工具执行器不等于策略允许；空工具清单默认拒绝。
        var policy = StructuredPolicy.builder("default-deny-v1").build();
        ToolScenario.run(
                "默认拒绝",
                policy,
                ToolScenario.identity(Set.of("read")),
                ToolScenario.VALID,
                null,
                values -> "不会执行",
                "tool_not_allowed",
                null,
                0);
    }
}
