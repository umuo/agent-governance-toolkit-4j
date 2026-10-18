package io.agt.langchain4j.internal.model;

import dev.langchain4j.model.chat.ChatModel;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.langchain4j.ModelTerminationContract;
import io.agt.langchain4j.ModelUsageMeter;
import java.util.Objects;
import java.util.Set;

/**
 * 模型内部装配入口。
 *
 * <p>不属于稳定公开 API；具体包装实现保持包级可见，治理组件经检查后组装。
 */
public final class ModelAdapters {
    private ModelAdapters() {}

    public static ChatModel guard(AgentControl control, ChatModel model) {
        return guard(control, model, null);
    }

    public static ChatModel guard(AgentControl control, ChatModel model, ModelUsageMeter meter) {
        return guard(control, model, meter, null);
    }

    public static ChatModel guard(
            AgentControl control,
            ChatModel model,
            ModelUsageMeter meter,
            ModelTerminationContract termination) {
        control.requirePoints(
                Set.of(InterventionPoint.PRE_MODEL_CALL, InterventionPoint.POST_MODEL_CALL));
        if (!model.listeners().isEmpty()) {
            throw new GovernanceException("raw_model_listeners_unsupported");
        }
        if (model instanceof GovernedChatModel) {
            throw new GovernanceException("already_governed");
        }
        return new GovernedChatModel(control, Objects.requireNonNull(model), meter, termination);
    }
}
