package io.agt.langchain4j.internal.streaming;

import dev.langchain4j.model.chat.StreamingChatModel;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.InterventionPoint;
import io.agt.langchain4j.StreamingLimits;
import java.util.Objects;
import java.util.Set;

/**
 * 流式内部装配入口。
 *
 * <p>不属于稳定公开 API；具体包装实现保持包级可见，治理组件经检查后组装。
 */
public final class StreamingAdapters {
    private StreamingAdapters() {}

    public static StreamingChatModel guard(
            AgentControl control, StreamingChatModel model, StreamingLimits limits) {
        Objects.requireNonNull(model);
        control.requirePoints(
                Set.of(InterventionPoint.PRE_MODEL_CALL, InterventionPoint.POST_MODEL_CALL));
        if (!model.listeners().isEmpty()) {
            throw new GovernanceException("raw_model_listeners_unsupported");
        }
        if (model instanceof GovernedStreamingChatModel) {
            throw new GovernanceException("already_governed");
        }
        return new GovernedStreamingChatModel(control, model, Objects.requireNonNull(limits));
    }

    public static BufferedStreamControl buffered(
            HostSession session,
            java.util.function.Supplier<
                            java.util.concurrent.CompletableFuture<
                                    dev.langchain4j.model.chat.response.ChatResponse>>
                    invoke) {
        return new BufferedStream(session, invoke);
    }
}
