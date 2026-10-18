package io.agt.langchain4j.internal.model;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.*;
import io.agt.core.usage.*;
import io.agt.langchain4j.ModelTerminationContract;
import io.agt.langchain4j.ModelUsageMeter;
import java.util.Objects;

/** 框架 DTO 与根任务账本之间的边界，不从模型响应选择价格表。 */
final class ModelUsageBridge {
    private ModelUsageBridge() {}

    record Prepared(
            ChatRequest request,
            ModelUsageReservation usageReservation,
            ModelExecutionBridge.Call execution)
            implements ManagedModelInput {
        public void beforeModelDispatch() {
            execution.started();
        }

        public void closeModelPreparation() {
            execution.abandon();
        }
    }

    private record Budgeted(ChatRequest request, ModelUsageReservation usageReservation) {}

    static Prepared prepare(
            HostSession session,
            ChatRequest request,
            ModelUsageMeter meter,
            ModelTerminationContract contract) {
        var bounded = quote(session, request, meter);
        try {
            return new Prepared(
                    bounded.request(),
                    bounded.usageReservation(),
                    ModelExecutionBridge.prepare(session, bounded.request(), contract));
        } catch (RuntimeException unavailable) {
            if (bounded.usageReservation() != null) bounded.usageReservation().close();
            throw unavailable;
        }
    }

    private static Budgeted quote(HostSession session, ChatRequest request, ModelUsageMeter meter) {
        if (session.usageLimits() == null) return new Budgeted(request, null);
        if (meter == null) throw new GovernanceException("model_usage_meter_unavailable");
        String model = request.parameters().modelName();
        var rate = model == null ? null : session.usageLimits().models().get(model);
        if (rate == null) throw new GovernanceException("model_usage_model_denied");
        int output =
                request.parameters().maxOutputTokens() == null
                        ? rate.maxOutputTokens()
                        : request.parameters().maxOutputTokens();
        if (output < 1 || output > rate.maxOutputTokens())
            throw new GovernanceException("model_usage_request_bound_denied");
        var bound = Objects.requireNonNull(meter.bind(request, output));
        var prepared = bound.request();
        if (!model.equals(prepared.parameters().modelName())
                || !request.messages().equals(prepared.messages())
                || !Objects.equals(request.toolSpecifications(), prepared.toolSpecifications())) {
            throw new GovernanceException("model_usage_request_changed");
        }
        return new Budgeted(
                prepared, session.reserveModelUsage(model, bound.inputTokenUpperBound(), output));
    }

    static void record(HostSession session, Prepared prepared, ChatResponse response) {
        var usage = response.tokenUsage();
        if (prepared.usageReservation() != null) {
            try {
                prepared.usageReservation()
                        .settle(
                                response.metadata().modelName(),
                                usage == null ? null : usage.inputTokenCount(),
                                usage == null ? null : usage.outputTokenCount(),
                                usage == null ? null : usage.totalTokenCount());
            } catch (RuntimeException error) {
                session.recordTokens(null);
                throw error;
            }
        }
        session.recordTokens(usage == null ? null : usage.totalTokenCount());
    }
}
