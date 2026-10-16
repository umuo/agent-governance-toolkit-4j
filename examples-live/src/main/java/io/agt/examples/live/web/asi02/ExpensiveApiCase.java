package io.agt.examples.live.web.asi02;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.BudgetLimits;
import io.agt.core.policy.StructuredPolicy;
import java.util.Map;

/** 每次真实进入工具增加十个模拟积分；会话工具额度为三，不声称跨会话限流。 */
public final class ExpensiveApiCase implements CaseStudy {
    private int entered;

    @Tool("生成一份演示报告，每次消耗十个模拟积分")
    public String generateReport(String projectId) {
        entered++;
        return "report-" + entered;
    }

    public StructuredPolicy policy() {
        return StructuredPolicy.builder("asi02-cost")
                .budget(new BudgetLimits(20, 3, 30000))
                .tool(
                        "generateReport",
                        Rules.tool(Map.of("projectId", Rules.choice("demo-project"))))
                .build();
    }

    public Map<String, Object> snapshot() {
        return Map.of("toolEntered", entered, "simulatedCredits", entered * 10);
    }
}
