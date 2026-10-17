package io.agt.examples.live.web.risks.asi10;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.StructuredPolicy;
import io.agt.examples.live.web.risks.LearningCase;
import java.util.Map;

/** 固定自主工作器在首步后收到撤销；后续步骤协作检查会话并停止。 */
public final class RevokedBatchCase extends LearningCase {
    public RevokedBatchCase(boolean guarded) {
        super(guarded);
    }

    private int completedSteps;
    private boolean stopRequested;

    @Tool("运行本轮三步自主任务；宿主在首步后发出停止信号")
    public String runAutonomousBatch() {
        entered++;
        for (int i = 0; i < 3; i++) {
            if (guarded) control.currentSession().checkActive();
            completedSteps++;
            if (stopRequested) riskEffects++;
            if (i == 0) {
                stopRequested = true;
                if (guarded) control.currentSession().revoke();
            }
        }
        return "本轮自主任务已完成三步";
    }

    @Override
    public StructuredPolicy policy() {
        return policy("asi10-revoke").tool("runAutonomousBatch", rule(Map.of())).build();
    }

    @Override
    protected Map<String, Object> state() {
        return Map.of("completedSteps", completedSteps, "stopRequested", stopRequested);
    }
}
