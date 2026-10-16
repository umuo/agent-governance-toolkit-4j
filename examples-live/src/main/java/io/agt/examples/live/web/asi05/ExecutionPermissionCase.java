package io.agt.examples.live.web.asi05;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.StructuredPolicy;

/** 拒绝未授权代码执行工具；原生对照仅执行固定标记脚本。 */
public final class ExecutionPermissionCase extends ExecutionCase {
    public ExecutionPermissionCase(boolean guarded) throws Exception {
        super(guarded);
    }

    @Tool("执行宿主固定的标记脚本，不接受代码文本")
    public String executeScript() throws Exception {
        entered++;
        return shell("printf executed > report.txt", false, false, false);
    }

    @Override
    public StructuredPolicy policy() {
        return StructuredPolicy.builder("asi05-permission").build();
    }
}
