package io.agt.examples.live.web.asi05;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.GovernanceException;
import io.agt.core.execution.CommandExecution;
import io.agt.core.execution.CommandLimits;
import io.agt.core.policy.StructuredPolicy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 固定一秒等待探针；超时隔离后端，并实际预检其拒绝复用，不声称进程树回收已确认。 */
public final class TimeoutCase extends ExecutionCase {
    private boolean deadlineExceeded;
    private boolean backendQuarantined;

    public TimeoutCase(boolean guarded) throws Exception {
        super(guarded);
    }

    @Tool("运行宿主固定的一秒等待探针，观察 150 毫秒期限与后端隔离")
    public String runSlowProgram() throws Exception {
        entered++;
        try {
            return execute("/bin/sleep", List.of("1"), guarded, false, false, guarded ? 150 : 3000);
        } catch (GovernanceException error) {
            if (error.code().equals("sandbox_timeout")) {
                deadlineExceeded = true;
                try {
                    backend.prepare(
                            new CommandExecution(
                                    Path.of("/bin/sleep").toRealPath(),
                                    work,
                                    List.of("1"),
                                    new CommandLimits(150, 4096, 1)));
                } catch (GovernanceException denied) {
                    backendQuarantined = denied.code().equals("sandbox_cleanup_unconfirmed");
                }
            }
            throw error;
        }
    }

    @Override
    public Map<String, Object> snapshot() {
        var state = new LinkedHashMap<>(super.snapshot());
        state.put("deadlineExceeded", deadlineExceeded);
        state.put("backendQuarantined", backendQuarantined);
        return Map.copyOf(state);
    }

    @Override
    public StructuredPolicy policy() {
        return allow("runSlowProgram");
    }
}
