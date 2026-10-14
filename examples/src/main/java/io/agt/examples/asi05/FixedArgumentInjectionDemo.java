package io.agt.examples.asi05;

import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandLimits;
import io.agt.core.execution.FixedCommand;
import io.agt.examples.support.CommandScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 固定命令的枚举参数拒绝命令注入字符串，不启动子进程。
 *
 * <p>直接运行 main；只使用经审核的固定程序与合成数据，不运行模型生成代码。
 */
public final class FixedArgumentInjectionDemo {
    public static void main(String[] args) throws Exception {
        try (var demo = new CommandScenario()) {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
            // 六参数入口是受控本机进程，不是 OS 沙箱；此例只演示参数阻断。
            var command =
                    new FixedCommand(
                            "report",
                            java,
                            CommandScenario.digest(java),
                            demo.work,
                            List.of(CommandArgument.choice("option", "-version")),
                            CommandLimits.DEFAULT);
            CommandScenario.execute(
                    command,
                    "{\"option\":\"-version; touch injected\"}",
                    "tool_arguments_denied",
                    null);
            if (Files.exists(demo.work.resolve("injected"))) {
                throw new IllegalStateException("发生注入副作用");
            }
            System.out.println("枚举以外的字符串被阻断，工具执行尝试=0。");
        }
    }
}
