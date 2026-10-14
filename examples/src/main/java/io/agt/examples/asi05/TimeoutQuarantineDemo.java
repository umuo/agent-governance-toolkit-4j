package io.agt.examples.asi05;

import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandLimits;
import io.agt.examples.support.CommandScenario;
import io.agt.sandbox.SandboxCommandBackend;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.util.List;

/**
 * 超时后调用方等待有界，后端永久隔离，不宣称 OS 清理确认。
 *
 * <p>直接运行 main；只使用经审核的固定程序与合成数据，不运行模型生成代码。
 */
public final class TimeoutQuarantineDemo {
    public static void main(String[] args) throws Exception {
        if (!CommandScenario.nativeMac()) {
            return;
        }
        try (var demo = new CommandScenario();
                var backend =
                        SandboxCommandBackend.allowHostReads(
                                SandboxClient.create(),
                                SandboxPolicy.builder(demo.work)
                                        .readPolicy(ReadPolicy.HOST)
                                        .network(NetworkPolicy.DENY)
                                        .build())) {
            var command =
                    demo.command(
                            "/bin/sleep",
                            List.of(CommandArgument.literal("2")),
                            new CommandLimits(100, 65536, 1),
                            backend);
            CommandScenario.execute(command, "{}", "sandbox_timeout", null);
            // 新会话也不能绕过 backend 的隔离状态。
            CommandScenario.execute(command, "{}", "sandbox_cleanup_unconfirmed", null);
            System.out.println("超时 backend 已永久停用；需宿主核验 OS 清理，不能用重建对象掩盖后台进程。");
        }
    }
}
