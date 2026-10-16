package io.agt.examples.live.web.asi02;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 合法删除工具被客服滥用；只有 use 权限的主体不能取得 customer.delete 权限。 */
public final class DeleteCustomersCase implements CaseStudy {
    private final List<String> customers = new ArrayList<>(List.of("C-001", "C-002", "C-003"));
    private int entered;

    @Tool("删除当前租户的全部演示客户")
    public String deleteCustomers(String tenant) {
        entered++;
        int count = customers.size();
        customers.clear();
        return "删除演示客户 " + count + " 条";
    }

    public StructuredPolicy policy() {
        return StructuredPolicy.builder("asi02-delete")
                .tool(
                        "deleteCustomers",
                        new ToolRule(
                                Set.of("customer.delete"),
                                ParameterRule.object(
                                        Map.of("tenant", ParameterRule.string("tenantId")),
                                        "tenant"),
                                false))
                .build();
    }

    public Map<String, Object> snapshot() {
        return Map.of("toolEntered", entered, "remainingCustomers", customers.size());
    }
}
