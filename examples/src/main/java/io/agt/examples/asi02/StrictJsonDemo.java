package io.agt.examples.asi02;

import io.agt.examples.support.ToolScenario;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * 逐个演示畸形 JSON、重复键、类型、必填、未知字段与枚举拒绝。
 *
 * <p>直接运行 main；公共接线在 ToolScenario，策略和工具行为在本类。
 */
public final class StrictJsonDemo {
    public static void main(String[] args) throws Exception {
        // 每次拒绝都终止会话，所以每个输入使用新的会话。
        var invalid = new LinkedHashMap<String, String>();
        invalid.put("畸形 JSON", "{bad}");
        invalid.put(
                "重复键", "{\"tenant\":\"tenant-a\",\"query\":\"original\",\"query\":\"modified\"}");
        invalid.put("类型错误", "{\"tenant\":\"tenant-a\",\"query\":123}");
        invalid.put("缺失必填", "{\"tenant\":\"tenant-a\"}");
        invalid.put("未知字段", "{\"tenant\":\"tenant-a\",\"query\":\"original\",\"admin\":true}");
        invalid.put("枚举之外", "{\"tenant\":\"tenant-a\",\"query\":\"delete-all\"}");
        for (var entry : invalid.entrySet()) {
            String code =
                    Set.of("畸形 JSON", "重复键").contains(entry.getKey())
                            ? "invalid_json"
                            : "tool_arguments_denied";
            ToolScenario.run(
                    entry.getKey(),
                    ToolScenario.allowed(false).build(),
                    ToolScenario.identity(Set.of("read")),
                    entry.getValue(),
                    null,
                    values -> "不会执行",
                    code,
                    null,
                    0);
        }
    }
}
