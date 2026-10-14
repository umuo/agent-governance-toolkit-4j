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
 * 真实沙箱允许工作区写入，但拒绝工作区外写入。
 *
 * <p>直接运行 main；只使用经审核的固定程序与合成数据，不运行模型生成代码。
 */
public final class WorkspaceWriteIsolationDemo {
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
            // shell 代码是宿主静态模板，路径也是宿主固定 literal，模型不能提供脚本。
            var allowed =
                    demo.command(
                            "/bin/sh",
                            List.of(
                                    CommandArgument.literal("-c"),
                                    CommandArgument.literal(
                                            "printf workspace > allowed.txt; printf ok")),
                            CommandLimits.DEFAULT,
                            backend);
            CommandScenario.execute(allowed, "{}", null, "ok");
            if (!"workspace".equals(Files.readString(demo.work.resolve("allowed.txt")))) {
                throw new IllegalStateException("工作区写入未发生");
            }
            var denied =
                    demo.command(
                            "/bin/sh",
                            List.of(
                                    CommandArgument.literal("-c"),
                                    CommandArgument.literal("printf changed > \"$1\""),
                                    CommandArgument.literal("writer"),
                                    CommandArgument.literal(demo.canary.toString())),
                            CommandLimits.DEFAULT,
                            backend);
            CommandScenario.execute(denied, "{}", "sandbox_exit_denied", null);
            if (!"synthetic-canary".equals(Files.readString(demo.canary))) {
                throw new IllegalStateException("工作区外文件被改动");
            }
            System.out.println("工作区内写入成功，工作区外 canary 保持不变。HOST 仍允许宿主文件读取。");
        }
    }
}
