package io.agt.examples.live.web.risks.asi07;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.StructuredPolicy;
import io.agt.examples.live.web.risks.LearningCase;
import io.agt.examples.live.web.risks.SignedChannel;
import java.util.Map;

/** 两个本机逻辑 Agent 之间真实执行 HMAC 校验；修改任务后旧签名不再有效。 */
public final class MessageTamperCase extends LearningCase {
    public MessageTamperCase(boolean guarded) {
        super(guarded);
    }

    private final SignedChannel channel = new SignedChannel();
    private int workerActions;

    @Tool("投递固定的跨 Agent 消息样本：valid 或 tampered")
    public String deliverEnvelope(String variant) {
        entered++;
        if (!java.util.Set.of("valid", "tampered").contains(variant))
            throw new IllegalArgumentException("Unknown fixture");
        var signed = channel.signed("report");
        var actual =
                variant.equals("valid")
                        ? signed
                        : new SignedChannel.Envelope(
                                signed.sender(),
                                signed.audience(),
                                signed.nonce(),
                                "export",
                                signed.signature());
        if (guarded) channel.verify(actual);
        workerActions++;
        if (actual.task().equals("export")) riskEffects++;
        return "本轮 worker 已执行任务：" + actual.task();
    }

    @Override
    public StructuredPolicy policy() {
        return policy("asi07-signature")
                .tool("deliverEnvelope", rule(Map.of("variant", choice("valid", "tampered"))))
                .build();
    }

    @Override
    protected Map<String, Object> state() {
        return Map.of("workerActions", workerActions);
    }
}
