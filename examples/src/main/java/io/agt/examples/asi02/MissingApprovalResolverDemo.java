package io.agt.examples.asi02;

import io.agt.examples.support.ToolScenario;
import java.util.Set;

/**
 * 缺少宿主审批器时拒绝，不能默认批准。
 *
 * <p>公共接线设置演示审批期限为 500ms；生产期限由宿主按交互需求配置。
 */
public final class MissingApprovalResolverDemo {
    public static void main(String[] args) {
        ToolScenario.run(
                "缺少宿主审批器时拒绝，不能默认批准。",
                ToolScenario.allowed(true).build(),
                ToolScenario.identity(Set.of("read")),
                ToolScenario.VALID,
                null,
                values -> "不会执行",
                "approval_unavailable",
                null,
                0);
    }
}
