package io.agt.examples.asi05;

import io.agt.core.GovernanceException;
import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandLimits;
import io.agt.core.execution.FixedCommand;
import io.agt.examples.support.CommandScenario;
import java.nio.file.Path;
import java.util.List;

/**
 * 固定程序摘要不匹配时，构造失败，不能执行。
 *
 * <p>直接运行 main；只使用经审核的固定程序与合成数据，不运行模型生成代码。
 */
public final class ExecutableIntegrityDemo {
    public static void main(String[] args) throws Exception {
        try (var demo = new CommandScenario()) {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
            try {
                // 故意使用错误的预期摘要；不修改真实 JDK 文件。
                new FixedCommand(
                        "report",
                        java,
                        "0".repeat(64),
                        demo.work,
                        List.of(CommandArgument.literal("-version")),
                        CommandLimits.DEFAULT);
                throw new IllegalStateException("摘要不匹配却被允许");
            } catch (GovernanceException error) {
                if (!error.code().equals("command_integrity_denied")) {
                    throw error;
                }
                System.out.println("程序摘要错误：command_integrity_denied，未启动任何子进程。");
            }
        }
    }
}
