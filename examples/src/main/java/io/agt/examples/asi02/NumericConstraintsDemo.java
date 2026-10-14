package io.agt.examples.asi02;

import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.examples.support.ToolScenario;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 整数类型、数值范围与数组长度必须在工具执行前满足。
 *
 * <p>每个输入使用新会话，避免拒绝后的失败状态影响下一项教学验证。
 */
public final class NumericConstraintsDemo {
    public static void main(String[] args) {
        var quantity =
                new ParameterRule(
                        ParameterRule.Type.INTEGER,
                        null,
                        null,
                        null,
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        null,
                        null,
                        null,
                        null);
        var tags =
                new ParameterRule(
                        ParameterRule.Type.ARRAY,
                        null,
                        null,
                        null,
                        null,
                        null,
                        1,
                        2,
                        null,
                        ParameterRule.string(null));
        var params =
                ParameterRule.object(
                        Map.of("quantity", quantity, "tags", tags), "quantity", "tags");
        var policy =
                StructuredPolicy.builder("numeric-demo-v1")
                        .tool("lookup", new ToolRule(Set.of("read"), params, false))
                        .build();
        ToolScenario.run(
                "合法范围",
                policy,
                ToolScenario.identity(Set.of("read")),
                "{\"quantity\":2,\"tags\":[\"a\"]}",
                null,
                values -> "ok",
                null,
                "ok",
                1);
        for (String invalid :
                List.of(
                        "{\"quantity\":0,\"tags\":[\"a\"]}",
                        "{\"quantity\":11,\"tags\":[\"a\"]}",
                        "{\"quantity\":1.5,\"tags\":[\"a\"]}",
                        "{\"quantity\":2,\"tags\":[\"a\",\"b\",\"c\"]}")) {
            ToolScenario.run(
                    "范围或类型非法",
                    policy,
                    ToolScenario.identity(Set.of("read")),
                    invalid,
                    null,
                    values -> "不会执行",
                    "tool_arguments_denied",
                    null,
                    0);
        }
    }
}
