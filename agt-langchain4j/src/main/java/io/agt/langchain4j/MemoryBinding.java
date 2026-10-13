package io.agt.langchain4j;

import dev.langchain4j.memory.ChatMemory;
import io.agt.core.data.DataResource;
import java.util.Objects;

/**
 * 宿主注册的实际记忆对象与可信资源描述符；不通过模型提供的 ID 推断所有权。
 *
 * <p>注册键、resource.instanceId 和原生 memory.id 必须一致。
 */
public record MemoryBinding(DataResource resource, ChatMemory memory) {
    public MemoryBinding {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(memory);
    }
}
