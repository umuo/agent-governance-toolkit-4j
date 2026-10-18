package io.agt.examples.live.web.experiment;

import io.agt.core.AgentControl;
import io.agt.core.TaskLimits;
import io.agt.core.TrustedContext;
import io.agt.core.policy.StructuredPolicy;
import java.util.Map;
import java.util.Set;

/** 每次实验创建新的业务状态；模型与工具均经过真实调用，不预设攻击是否触发。 */
public interface ExperimentCase extends AutoCloseable {
    record Call(String name, String arguments) {}

    StructuredPolicy policy();

    Map<String, Object> snapshot();

    default TrustedContext identity() {
        return new TrustedContext("asi02-demo", "tenant-a", "demo-user", Set.of("use"));
    }

    default void attach(AgentControl control) {}

    /** 可选根任务约束，由宿主案例声明，不接受网页上传策略。 */
    default TaskLimits taskLimits() {
        return null;
    }

    @Override
    default void close() throws Exception {}
}
