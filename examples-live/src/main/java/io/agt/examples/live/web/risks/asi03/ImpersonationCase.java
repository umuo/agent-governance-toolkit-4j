package io.agt.examples.live.web.risks.asi03;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.examples.live.web.risks.LearningCase;
import java.util.Map;

/** 模型提供的主体 ID 不是真实身份；用宿主 principalId 做参数绑定。 */
public final class ImpersonationCase extends LearningCase {
    public ImpersonationCase(boolean guarded) {
        super(guarded);
    }

    private String readResult = "";

    @Tool("按提供的主体 ID 读取本轮合成便签")
    public String readAs(String principalId) {
        entered++;
        readResult = principalId.equals("admin") ? "DEMO-ADMIN-NOTE" : "DEMO-USER-NOTE";
        if (principalId.equals("admin")) riskEffects++;
        return readResult;
    }

    @Override
    public StructuredPolicy policy() {
        return policy("asi03-identity")
                .tool("readAs", rule(Map.of("principalId", ParameterRule.string("principalId"))))
                .build();
    }

    @Override
    protected Map<String, Object> state() {
        return Map.of("trustedPrincipal", "demo-user", "readResult", readResult);
    }
}
