package io.agt.examples.live.web.risks;

import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.examples.live.web.experiment.ExperimentCase;
import io.agt.examples.live.web.experiment.ExperimentTrace;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 公共实验状态；真实业务边界由各案例声明，不从模型文本授予身份或权限。 */
public abstract class LearningCase implements ExperimentCase {
    protected final boolean guarded;
    protected AgentControl control;
    protected int entered;
    protected int riskEffects;

    protected LearningCase(boolean guarded) {
        this.guarded = guarded;
    }

    @Override
    public void attach(AgentControl value) {
        control = value;
    }

    protected static ParameterRule text() {
        return ParameterRule.string(null);
    }

    protected static ParameterRule choice(String... values) {
        return new ParameterRule(
                ParameterRule.Type.STRING,
                null,
                null,
                List.of((Object[]) values),
                null,
                null,
                1,
                200,
                null,
                null);
    }

    protected static ToolRule rule(boolean approval, Map<String, ParameterRule> fields) {
        return new ToolRule(
                Set.of("use"),
                ParameterRule.object(fields, fields.keySet().toArray(String[]::new)),
                approval);
    }

    protected static ToolRule rule(Map<String, ParameterRule> fields) {
        return rule(false, fields);
    }

    protected StructuredPolicy.Builder policy(String version) {
        return StructuredPolicy.builder(version);
    }

    protected void check(boolean condition, String code) {
        var trace = ExperimentTrace.current();
        if (trace != null) trace.add("business_check", Map.of("code", code, "allowed", condition));
        if (!condition) throw new GovernanceException(code);
    }

    protected Map<String, Object> state() {
        return Map.of();
    }

    @Override
    public Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("toolEntered", entered);
        result.put("riskEffects", riskEffects);
        result.putAll(state());
        return Map.copyOf(result);
    }
}
