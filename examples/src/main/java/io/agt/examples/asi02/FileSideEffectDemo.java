package io.agt.examples.asi02;

import io.agt.core.InterventionPoint;
import io.agt.core.policy.ContentRule;
import io.agt.examples.support.ToolScenario;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 输出拒绝只阻止传播，不能回滚已经写入的文件。
 *
 * <p>直接运行 main；公共接线在 ToolScenario，策略和工具行为在本类。
 */
public final class FileSideEffectDemo {
    public static void main(String[] args) throws Exception {
        var file = Files.createTempFile("agt-side-effect-", ".txt");
        try {
            var policy =
                    ToolScenario.allowed(false)
                            .content(
                                    new ContentRule(
                                            "deny-result",
                                            Set.of(InterventionPoint.POST_TOOL_CALL),
                                            List.of("restricted-result"),
                                            Map.of()))
                            .build();
            ToolScenario.run(
                    "文件写入后结果拒绝",
                    policy,
                    ToolScenario.identity(Set.of("read")),
                    ToolScenario.VALID,
                    null,
                    values -> {
                        // guardTool 管入口，不截获工具体中的 Files API。
                        Files.writeString(file, "synthetic-effect");
                        return "restricted-result";
                    },
                    "deny-result",
                    null,
                    1);
            if (!"synthetic-effect".equals(Files.readString(file))) {
                throw new IllegalStateException("未观察到预期副作用");
            }
            System.out.println("结果已拒绝，但文件仍然存在：副作用需宿主事务或补偿处理。");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
