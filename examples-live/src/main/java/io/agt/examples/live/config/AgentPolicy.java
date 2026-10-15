package io.agt.examples.live.config;

import io.agt.core.BudgetLimits;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 相同 Agent 在不同可信宿主权限与审批配置下运行。 */
public final class AgentPolicy {
    private AgentPolicy() {}

    public static StructuredPolicy create(String scenario) {
        var policy = StructuredPolicy.builder("live-agent-v1");
        tool(policy, "readFile", "fs.read", false, "path");
        tool(policy, "listFiles", "fs.read", false, "path");
        tool(policy, "searchFile", "fs.read", false, "path", "query");
        boolean approval = scenario.equals("approval");
        tool(policy, "writeFile", "fs.write", approval, "path", "content");
        tool(policy, "editFile", "fs.write", approval, "path", "oldText", "newText");
        tool(policy, "Bash", "shell.execute", true, "command");
        return policy.budget(
                        scenario.equals("budget")
                                ? new BudgetLimits(1, 1, 300_000)
                                : new BudgetLimits(20, 40, 300_000))
                .build();
    }

    private static void tool(
            StructuredPolicy.Builder policy,
            String name,
            String permission,
            boolean approval,
            String... fields) {
        Map<String, ParameterRule> parameters = new LinkedHashMap<>();
        for (String field : fields) {
            parameters.put(
                    field,
                    new ParameterRule(
                            ParameterRule.Type.STRING,
                            null,
                            null,
                            null,
                            null,
                            null,
                            field.equals("path") ? 1 : 0,
                            field.equals("path") ? 1024 : 32768,
                            null,
                            null));
        }
        policy.tool(
                name,
                new ToolRule(
                        Set.of(permission), ParameterRule.object(parameters, fields), approval));
    }

    public static Set<String> permissions(String scenario, boolean bash) {
        if (scenario.equals("read-only")) {
            return Set.of("fs.read");
        }
        return bash
                ? Set.of("fs.read", "fs.write", "shell.execute")
                : Set.of("fs.read", "fs.write");
    }
}
