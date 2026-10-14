package io.agt.examples.asi02;

import io.agt.examples.support.ToolScenario;
import java.util.Set;

/**
 * 模型不能用参数建立另一个租户的可信身份。
 *
 * <p>直接运行 main；公共接线在 ToolScenario，策略和工具行为在本类。
 */
public final class TenantBindingDemo {
    public static void main(String[] args) throws Exception {
        // tenantId 来自宿主身份；即使模型提供合法字符串，跨租户也拒绝。
        String arguments = "{\"tenant\":\"tenant-b\",\"query\":\"original\"}";
        ToolScenario.run(
                "跨租户",
                ToolScenario.allowed(false).build(),
                ToolScenario.identity(Set.of("read")),
                arguments,
                null,
                values -> "不会执行",
                "context_binding_denied",
                null,
                0);
    }
}
