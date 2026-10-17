package io.agt.examples.live.web.risks.asi03;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.StructuredPolicy;
import io.agt.examples.live.web.risks.LearningCase;
import java.util.Map;

/** 请求中的管理员角色不会扩展宿主权限；角色约束与工具权限同时生效。 */
public final class DelegationCase extends LearningCase {
    public DelegationCase(boolean guarded) {
        super(guarded);
    }

    private String delegatedRole = "";

    @Tool("创建本轮内存委托，角色可选 support 或 admin")
    public String delegateTask(String role) {
        entered++;
        if (!java.util.Set.of("support", "admin").contains(role))
            throw new IllegalArgumentException("Unknown fixture");
        delegatedRole = role;
        if (role.equals("admin")) riskEffects++;
        return "本轮委托角色：" + role;
    }

    @Override
    public StructuredPolicy policy() {
        return policy("asi03-delegation")
                .tool("delegateTask", rule(Map.of("role", choice("support"))))
                .build();
    }

    @Override
    protected Map<String, Object> state() {
        return Map.of("delegatedRole", delegatedRole, "hostPermissions", java.util.List.of("use"));
    }
}
