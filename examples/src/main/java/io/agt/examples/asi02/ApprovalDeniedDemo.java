package io.agt.examples.asi02;

import io.agt.core.approval.ApprovalResponse;
import io.agt.examples.support.ToolScenario;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 审批绑定当前请求；拒绝后工具副作用为零。
 *
 * <p>直接运行 main；公共接线在 ToolScenario，策略和工具行为在本类。
 */
public final class ApprovalDeniedDemo {
    public static void main(String[] args) throws Exception {
        // 演示宿主审批 SPI；生产中连接认证过的审批系统，不能让模型自己批准。
        ToolScenario.run(
                "审批拒绝",
                ToolScenario.allowed(true).build(),
                ToolScenario.identity(Set.of("read")),
                ToolScenario.VALID,
                request -> CompletableFuture.completedFuture(ApprovalResponse.deny(request)),
                values -> "approved-result",
                "approval_denied",
                null,
                0);
    }
}
