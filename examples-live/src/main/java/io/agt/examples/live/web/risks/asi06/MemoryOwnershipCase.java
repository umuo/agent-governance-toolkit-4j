package io.agt.examples.live.web.risks.asi06;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import io.agt.core.data.*;
import io.agt.core.policy.StructuredPolicy;
import io.agt.examples.live.web.risks.LearningCase;
import io.agt.langchain4j.LangChain4jGovernance;
import java.util.Map;
import java.util.Set;

/** 使用真实 ChatMemory 和数据所有者检查，避免把其他用户记忆混入当前上下文。 */
public final class MemoryOwnershipCase extends LearningCase {
    public MemoryOwnershipCase(boolean guarded) {
        super(guarded);
    }

    private int deliveredMemories;

    @Tool("读取本轮固定记忆样本，owner 为 demo-user 或 other-user")
    public String readUserMemory(String owner) {
        entered++;
        if (!java.util.Set.of("demo-user", "other-user").contains(owner))
            throw new IllegalArgumentException("Unknown fixture");
        ChatMemory memory =
                MessageWindowChatMemory.builder().id("owner-memory").maxMessages(5).build();
        memory.add(UserMessage.from("DEMO-MEMORY-" + owner));
        ChatMemory access =
                guarded
                        ? new LangChain4jGovernance(control)
                                .guardMemory(
                                        new DataResource(
                                                "user-memory",
                                                DataResource.Kind.MEMORY,
                                                "tenant-a",
                                                owner,
                                                "owner-memory"),
                                        memory)
                        : memory;
        String result = access.messages().toString();
        deliveredMemories++;
        if (owner.equals("other-user")) riskEffects++;
        return result;
    }

    @Override
    public StructuredPolicy policy() {
        return policy("asi06-owner")
                .tool("readUserMemory", rule(Map.of("owner", choice("demo-user", "other-user"))))
                .resource(
                        "user-memory",
                        new DataRule(
                                DataResource.Kind.MEMORY,
                                DataRule.Scope.PRINCIPAL,
                                Map.of(DataOperation.MEMORY_READ, Set.of("use"))))
                .build();
    }

    @Override
    protected Map<String, Object> state() {
        return Map.of("deliveredMemories", deliveredMemories);
    }
}
