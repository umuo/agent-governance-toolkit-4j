package io.agt.examples.live.web.asi05;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.StructuredPolicy;

/** 严格读取不可用时明确拒绝，不自动退回 HOST 读取。 */
public final class StrictReadCase extends ExecutionCase {
    public StrictReadCase(boolean guarded) throws Exception {
        super(guarded);
    }

    @Tool("读取本轮工作区外的合成文件，不访问任何真实秘密")
    public String readOutside() throws Exception {
        entered++;
        return shell("/bin/cat \"$1\"", guarded, true, false);
    }

    @Override
    public StructuredPolicy policy() {
        return allow("readOutside");
    }
}
