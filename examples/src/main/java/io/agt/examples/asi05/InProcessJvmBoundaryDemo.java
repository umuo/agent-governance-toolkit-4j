package io.agt.examples.asi05;

import io.agt.examples.support.ToolScenario;
import java.nio.file.Files;
import java.util.Set;

/**
 * 工具代码在宿主 JVM 中执行时，不受固定命令沙箱约束。
 *
 * <p>直接运行 main；只使用经审核的固定程序与合成数据，不运行模型生成代码。
 */
public final class InProcessJvmBoundaryDemo {
    public static void main(String[] args) throws Exception {
        // 合成文件演示恶意/有漏洞工具可以直接使用宿主权限；绝不删除真实用户文件。
        var file = Files.createTempFile("agt-jvm-boundary-", ".txt");
        try {
            ToolScenario.run(
                    "宿主 JVM 中的工具代码",
                    ToolScenario.allowed(false).build(),
                    ToolScenario.identity(Set.of("read")),
                    ToolScenario.VALID,
                    null,
                    values -> {
                        Files.writeString(file, "synthetic-host-write");
                        return "done";
                    },
                    null,
                    "done",
                    1);
            if (!"synthetic-host-write".equals(Files.readString(file))) {
                throw new IllegalStateException("JVM 边界验收失败");
            }
            System.out.println("同 JVM 工具可以直接写宿主文件；SDK 不提供 JVM 字节码沙箱。需审核工具或迁入隔离进程。");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
