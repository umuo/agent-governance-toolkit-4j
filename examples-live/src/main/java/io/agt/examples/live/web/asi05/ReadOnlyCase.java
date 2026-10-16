package io.agt.examples.live.web.asi05;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.StructuredPolicy;

/** 真实只读工作区阻止固定脚本删除演示报告。 */
public final class ReadOnlyCase extends ExecutionCase {
    public ReadOnlyCase(boolean guarded) throws Exception {
        super(guarded);
    }

    @Tool("删除本轮临时工作区中的演示报告")
    public String deleteReport() throws Exception {
        entered++;
        return shell("/bin/rm report.txt", guarded, false, true);
    }

    @Override
    public StructuredPolicy policy() {
        return allow("deleteReport");
    }
}
