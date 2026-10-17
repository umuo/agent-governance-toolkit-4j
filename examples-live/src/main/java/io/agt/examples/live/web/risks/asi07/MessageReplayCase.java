package io.agt.examples.live.web.risks.asi07;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.StructuredPolicy;
import io.agt.examples.live.web.risks.LearningCase;
import io.agt.examples.live.web.risks.SignedChannel;
import java.util.Map;

/** 有效签名仍需检查 nonce；重复消息不得导致下游动作重做。 */
public final class MessageReplayCase extends LearningCase {
    public MessageReplayCase(boolean guarded) {
        super(guarded);
    }

    private final SignedChannel channel = new SignedChannel();
    private int workerActions;

    @Tool("向本轮 worker 发送相同签名和 nonce 的固定消息；重复调用用于观察防重放")
    public String replayEnvelope() {
        entered++;
        var envelope = channel.signed("report");
        if (guarded) channel.verify(envelope);
        workerActions++;
        if (workerActions > 1) riskEffects++;
        return "本轮 worker 执行次数：" + workerActions;
    }

    @Override
    public StructuredPolicy policy() {
        return policy("asi07-replay").tool("replayEnvelope", rule(Map.of())).build();
    }

    @Override
    protected Map<String, Object> state() {
        return Map.of("workerActions", workerActions);
    }
}
