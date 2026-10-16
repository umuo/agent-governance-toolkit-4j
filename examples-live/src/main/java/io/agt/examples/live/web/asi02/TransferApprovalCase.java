package io.agt.examples.live.web.asi02;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 内存付款申请：金额和收款人先校验，合法动作仍需审批；不连接真实支付系统。 */
public final class TransferApprovalCase implements CaseStudy {
    private final List<String> ledger = new ArrayList<>();

    @Tool("演示付款，只写内存账本；金额单位为分")
    public String transfer(String recipientId, long amountCents) {
        ledger.add(recipientId + ":" + amountCents);
        return "模拟记账完成";
    }

    public StructuredPolicy policy() {
        return StructuredPolicy.builder("asi02-transfer")
                .tool(
                        "transfer",
                        new ToolRule(
                                Set.of("use"),
                                ParameterRule.object(
                                        Map.of(
                                                "recipientId",
                                                Rules.choice("supplier-a"),
                                                "amountCents",
                                                Rules.integer(1, 10000)),
                                        "recipientId",
                                        "amountCents"),
                                true))
                .build();
    }

    public Map<String, Object> snapshot() {
        return Map.of("ledgerEntries", ledger.size(), "ledger", List.copyOf(ledger));
    }
}
