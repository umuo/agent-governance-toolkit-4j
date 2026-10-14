package io.agt.examples.asi02;

import io.agt.examples.support.ToolScenario;
import java.util.Set;

/**
 * 工具已许可，但可信身份缺少 read 权限。
 *
 * <p>直接运行 main；公共接线在 ToolScenario，策略和工具行为在本类。
 */
public final class PermissionDeniedDemo {
    public static void main(String[] args) throws Exception {
        ToolScenario.run(
                "权限不足",
                ToolScenario.allowed(false).build(),
                ToolScenario.identity(Set.of()),
                ToolScenario.VALID,
                null,
                values -> "不会执行",
                "permission_denied",
                null,
                0);
    }
}
