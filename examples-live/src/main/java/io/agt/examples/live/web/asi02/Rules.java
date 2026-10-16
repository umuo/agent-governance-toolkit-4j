package io.agt.examples.live.web.asi02;

import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.ToolRule;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 减少重复的参数形状代码；具体业务授权仍定义在每个案例内。 */
final class Rules {
    private Rules() {}

    static ParameterRule text() {
        return ParameterRule.string(null);
    }

    static ParameterRule choice(String... values) {
        return new ParameterRule(
                ParameterRule.Type.STRING,
                null,
                null,
                List.of(values),
                null,
                null,
                1,
                100,
                null,
                null);
    }

    static ParameterRule integer(long minimum, long maximum) {
        return new ParameterRule(
                ParameterRule.Type.INTEGER,
                null,
                null,
                null,
                BigDecimal.valueOf(minimum),
                BigDecimal.valueOf(maximum),
                null,
                null,
                null,
                null);
    }

    static ToolRule tool(Map<String, ParameterRule> fields) {
        return new ToolRule(
                Set.of("use"),
                ParameterRule.object(fields, fields.keySet().toArray(String[]::new)),
                false);
    }
}
