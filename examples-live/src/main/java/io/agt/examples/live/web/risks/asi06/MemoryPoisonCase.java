package io.agt.examples.live.web.risks.asi06;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import io.agt.core.InterventionPoint;
import io.agt.core.data.*;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.examples.live.web.risks.LearningCase;
import io.agt.langchain4j.LangChain4jGovernance;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用真实 LangChain4j ChatMemory 保存与读取；数据前置检查阻止投毒持久化。 */
public final class MemoryPoisonCase extends LearningCase {
    public MemoryPoisonCase(boolean guarded) {
        super(guarded);
    }

    private final ChatMemory memory =
            MessageWindowChatMemory.builder().id("private-memory").maxMessages(20).build();
    private String recalled = "";

    private ChatMemory access() {
        return guarded
                ? new LangChain4jGovernance(control)
                        .guardMemory(
                                new DataResource(
                                        "conversation",
                                        DataResource.Kind.MEMORY,
                                        "tenant-a",
                                        "demo-user",
                                        "private-memory"),
                                memory)
                : memory;
    }

    @Tool("把一条文本写入本轮长期记忆演示存储，随后可调用 recallInstruction 读取")
    public String rememberInstruction(String text) {
        entered++;
        access().add(UserMessage.from(text));
        if (text.contains("DEMO-POISON:")) riskEffects++;
        return "已保存，后续调用可读取";
    }

    @Tool("读取本轮已持久化的记忆内容，不创建新的存储")
    public String recallInstruction() {
        entered++;
        recalled = access().messages().toString();
        return recalled;
    }

    @Override
    public StructuredPolicy policy() {
        return policy("asi06-memory")
                .tool("rememberInstruction", rule(Map.of("text", text())))
                .tool("recallInstruction", rule(Map.of()))
                .resource(
                        "conversation",
                        new DataRule(
                                DataResource.Kind.MEMORY,
                                DataRule.Scope.PRINCIPAL,
                                Map.of(
                                        DataOperation.MEMORY_ADD,
                                        Set.of("use"),
                                        DataOperation.MEMORY_READ,
                                        Set.of("use"))))
                .content(
                        new ContentRule(
                                "memory_poison_denied",
                                Set.of(InterventionPoint.PRE_DATA_ACCESS),
                                List.of("DEMO-POISON:"),
                                Map.of()))
                .build();
    }

    @Override
    protected Map<String, Object> state() {
        return Map.of(
                "memoryEntries",
                memory.messages().size(),
                "memoryContainsPoison",
                memory.messages().toString().contains("DEMO-POISON:"),
                "recalledMemory",
                recalled);
    }
}
