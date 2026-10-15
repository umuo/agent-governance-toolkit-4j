package io.agt.langchain4j.internal.memory;

import dev.langchain4j.memory.ChatMemory;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.data.DataResource;
import java.util.Objects;

/**
 * 记忆内部装配入口。
 *
 * <p>不属于稳定公开 API；具体包装实现保持包级可见，治理组件经检查后组装。
 */
public final class MemoryAdapters {
    private MemoryAdapters() {}

    public static ChatMemory guard(AgentControl control, DataResource resource, ChatMemory memory) {
        io.agt.langchain4j.internal.data.DataAccessSupport.require(
                control, Objects.requireNonNull(resource), DataResource.Kind.MEMORY);
        if (memory instanceof GovernedChatMemory) {
            throw new GovernanceException("already_governed");
        }
        return new GovernedChatMemory(control, resource, Objects.requireNonNull(memory));
    }

    public static boolean isGoverned(ChatMemory memory) {
        return memory instanceof GovernedChatMemory;
    }

    public static ChatMemory context(AgentControl control, ChatMemory memory) {
        return new ContextChatMemory(control, memory);
    }
}
