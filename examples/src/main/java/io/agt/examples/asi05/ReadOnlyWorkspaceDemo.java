package io.agt.examples.asi05;

import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandLimits;
import io.agt.examples.support.CommandScenario;
import io.agt.sandbox.SandboxCommandBackend;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.Files;
import java.util.List;

/**
 * 只读工作目录不能因为适配器重建策略而恢复写权限。
 *
 * <p>直接运行 main；只使用经审核的固定程序与合成数据，不运行模型生成代码。
 */
public final class ReadOnlyWorkspaceDemo {
    public static void main(String[] args) throws Exception {
        if (!CommandScenario.nativeMac()) {
            return;
        }
        try (var demo = new CommandScenario();
                var backend =
                        SandboxCommandBackend.allowHostReads(
                                SandboxClient.create(),
                                SandboxPolicy.builder(demo.work)
                                        .readOnlyWorkingDirectory()
                                        .readPolicy(ReadPolicy.HOST)
                                        .network(NetworkPolicy.DENY)
                                        .build())) {
            var command =
                    demo.command(
                            "/bin/sh",
                            List.of(
                                    CommandArgument.literal("-c"),
                                    CommandArgument.literal("printf changed > denied.txt")),
                            CommandLimits.DEFAULT,
                            backend);
            CommandScenario.execute(command, "{}", "sandbox_exit_denied", null);
            if (Files.exists(demo.work.resolve("denied.txt"))) {
                throw new IllegalStateException("只读工作区可写");
            }
            System.out.println("只读工作目录拒绝写入；SDK 自身临时目录的写权限另有边界。");
        }
    }
}
