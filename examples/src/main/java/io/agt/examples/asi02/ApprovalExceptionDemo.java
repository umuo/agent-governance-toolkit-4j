package io.agt.examples.asi02;

import io.agt.examples.support.ToolScenario;
import java.util.Set;

/**
 * 宿主审批器异常时拒绝，不把异常当成批准。
 *
 * <p>公共接线设置演示审批期限为 500ms；生产期限由宿主按交互需求配置。
 */
public final class ApprovalExceptionDemo {
    public static void main(String[] args) {
        ToolScenario.run(
                "宿主审批器异常时拒绝，不把异常当成批准。",
                ToolScenario.allowed(true).build(),
                ToolScenario.identity(Set.of("read")),
                ToolScenario.VALID,
                request -> {
                    throw new IllegalStateException("synthetic-approval-failure");
                },
                values -> "不会执行",
                "approval_failed",
                null,
                0);
    }
}
