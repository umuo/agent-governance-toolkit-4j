package io.agt.examples.asi02;

import io.agt.examples.support.ToolScenario;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 审批 Future 永不完成时，有限超时后拒绝。
 *
 * <p>公共接线设置演示审批期限为 500ms；生产期限由宿主按交互需求配置。
 */
public final class ApprovalTimeoutDemo {
    public static void main(String[] args) {
        ToolScenario.run(
                "审批 Future 永不完成时，有限超时后拒绝。",
                ToolScenario.allowed(true).build(),
                ToolScenario.identity(Set.of("read")),
                ToolScenario.VALID,
                request -> new CompletableFuture<>(),
                values -> "不会执行",
                "approval_timeout",
                null,
                0);
    }
}
