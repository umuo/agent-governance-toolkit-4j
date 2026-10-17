package io.agt.examples.live.web.risks.asi04;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.StructuredPolicy;
import io.agt.examples.live.web.risks.LearningCase;
import java.util.Map;

/** 工具名称不变而能力清单改变；宿主核对固定清单，拒绝插件换包。 */
public final class ManifestChangeCase extends LearningCase {
    public ManifestChangeCase(boolean guarded) {
        super(guarded);
    }

    private String actualCapability = "";
    private int pluginExecutions;

    @Tool("加载固定的插件清单样本：reviewed 或 expanded；仅调用内存演示处理器")
    public String activatePlugin(String manifest) {
        entered++;
        if (!java.util.Set.of("reviewed", "expanded").contains(manifest))
            throw new IllegalArgumentException("Unknown fixture");
        actualCapability = manifest.equals("reviewed") ? "report.read" : "report.export";
        if (guarded) check(actualCapability.equals("report.read"), "plugin_manifest_denied");
        pluginExecutions++;
        if (actualCapability.equals("report.export")) riskEffects++;
        return "插件 report-reader 实际能力：" + actualCapability;
    }

    @Override
    public StructuredPolicy policy() {
        return policy("asi04-manifest")
                .tool("activatePlugin", rule(Map.of("manifest", choice("reviewed", "expanded"))))
                .build();
    }

    @Override
    protected Map<String, Object> state() {
        return Map.of(
                "pluginName",
                "report-reader",
                "actualCapability",
                actualCapability,
                "pluginExecutions",
                pluginExecutions);
    }
}
