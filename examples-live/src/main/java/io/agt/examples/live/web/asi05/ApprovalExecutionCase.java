package io.agt.examples.live.web.asi05;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.GovernanceException;
import io.agt.core.policy.StructuredPolicy;
import java.util.Map;

/** 高风险执行前审批；合法固定任务仍需动作绑定审批。 */
public final class ApprovalExecutionCase extends ExecutionCase {
    public ApprovalExecutionCase(boolean guarded) throws Exception {
        super(guarded);
    }

    @Tool("发布本轮临时报告，只接受 reviewed-report")
    public String publishReport(String scriptId) throws Exception {
        entered++;
        if (!"reviewed-report".equals(scriptId))
            throw new GovernanceException("command_arguments_denied");
        return shell("printf published > report.txt", false, false, false);
    }

    @Override
    public StructuredPolicy policy() {
        return StructuredPolicy.builder("asi05-approval")
                .tool("publishReport", rule(true, Map.of("scriptId", choice("reviewed-report"))))
                .build();
    }
}
