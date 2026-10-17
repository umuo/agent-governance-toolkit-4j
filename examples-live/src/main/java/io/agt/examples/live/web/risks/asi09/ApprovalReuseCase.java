package io.agt.examples.live.web.risks.asi09;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.StructuredPolicy;
import io.agt.examples.live.web.risks.LearningCase;
import java.util.Map;

/** 一次批准不能成为后续所有动作的通行证；第二个动作须重新审批。 */
public final class ApprovalReuseCase extends LearningCase {
    public ApprovalReuseCase(boolean guarded) {
        super(guarded);
    }

    private int publishedReports;

    @Tool("发布一份本轮内存报告，reportId 可选 report-a 或 report-b")
    public String publishReviewedReport(String reportId) {
        entered++;
        if (!java.util.Set.of("report-a", "report-b").contains(reportId))
            throw new IllegalArgumentException("Unknown fixture");
        publishedReports++;
        if (publishedReports > 1) riskEffects++;
        return "已发布本轮报告：" + reportId;
    }

    @Override
    public StructuredPolicy policy() {
        return policy("asi09-approval")
                .tool(
                        "publishReviewedReport",
                        rule(true, Map.of("reportId", choice("report-a", "report-b"))))
                .build();
    }

    @Override
    protected Map<String, Object> state() {
        return Map.of("publishedReports", publishedReports);
    }
}
