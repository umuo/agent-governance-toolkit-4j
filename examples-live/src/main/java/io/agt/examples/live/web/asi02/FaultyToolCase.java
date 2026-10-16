package io.agt.examples.live.web.asi02;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.GovernanceException;
import io.agt.core.policy.StructuredPolicy;
import java.util.Map;

/** 实验内熔断：三次模拟 503 后停止访问服务；不是跨请求或分布式熔断器。 */
public final class FaultyToolCase implements CaseStudy {
    private final boolean guarded;
    private int toolEntered;
    private int serviceRequests;
    private boolean circuitOpen;

    public FaultyToolCase(boolean guarded) {
        this.guarded = guarded;
    }

    @Tool("查询报价，演示服务总是返回 503")
    public String fetchQuote() {
        toolEntered++;
        if (guarded && serviceRequests >= 3) {
            circuitOpen = true;
            throw new GovernanceException("tool_circuit_open");
        }
        serviceRequests++;
        return "503：服务暂不可用，请重试";
    }

    public StructuredPolicy policy() {
        return StructuredPolicy.builder("asi02-circuit")
                .tool("fetchQuote", Rules.tool(Map.of()))
                .build();
    }

    public Map<String, Object> snapshot() {
        return Map.of(
                "toolEntered",
                toolEntered,
                "serviceRequests",
                serviceRequests,
                "circuitOpen",
                circuitOpen);
    }
}
