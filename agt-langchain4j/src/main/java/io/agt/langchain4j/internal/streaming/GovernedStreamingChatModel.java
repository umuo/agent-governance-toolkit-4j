package io.agt.langchain4j.internal.streaming;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.langchain4j.ModelTerminationContract;
import io.agt.langchain4j.ModelUsageMeter;
import io.agt.langchain4j.StreamDelivery;
import io.agt.langchain4j.StreamingLimits;
import io.agt.langchain4j.internal.model.ModelAdapters;
import io.agt.langchain4j.internal.model.ModelExecutionBridge;
import io.agt.langchain4j.internal.model.TerminalAwareModel;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

/**
 * 受控流式模型装饰器。
 *
 * <p>完整响应经 post_model_call 后交付，实时模式可先交付预览增量；工具调用不提前放行。
 */
final class GovernedStreamingChatModel implements StreamingChatModel {
    private final AgentControl control;
    private final StreamingLimits limits;
    private final StreamDelivery delivery;
    private final StreamingChatModel delegate;
    private final ModelUsageMeter meter;
    private final ModelTerminationContract termination;

    GovernedStreamingChatModel(
            AgentControl control,
            StreamingChatModel delegate,
            StreamingLimits limits,
            StreamDelivery delivery,
            ModelUsageMeter meter,
            ModelTerminationContract termination) {
        this.control = control;
        this.delegate = delegate;
        this.limits = limits;
        this.delivery = delivery;
        this.meter = meter;
        this.termination = termination;
    }

    private ChatModel governed(StreamingChatResponseHandler handler) {
        ChatModel collected =
                new TerminalAwareModel() {
                    @Override
                    public CompletableFuture<ChatResponse> chatAsyncObserved(
                            ChatRequest request,
                            ChatRequestOptions options,
                            ModelExecutionBridge.Call observation) {
                        return StreamingCollector.collect(
                                control.currentSession(),
                                delegate,
                                limits,
                                request,
                                options,
                                delivery == StreamDelivery.REALTIME ? handler : null,
                                observation);
                    }

                    @Override
                    public ChatRequestParameters defaultRequestParameters() {
                        return delegate.defaultRequestParameters();
                    }
                };
        return ModelAdapters.guard(control, collected, meter, termination);
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        chat(request, ChatRequestOptions.EMPTY, handler);
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        chat(request, handler);
    }

    @Override
    public void chat(
            ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
        HostSession session = control.currentSession();
        CompletableFuture<ChatResponse> future = governed(handler).chatAsync(request, options);
        future.whenComplete(
                (response, error) -> {
                    try (var scope = session.bind()) {
                        if (error != null) {
                            handler.onError(safe(error));
                            return;
                        }
                        try {
                            session.checkDelivery();
                            String text = response.aiMessage().text();
                            if (delivery == StreamDelivery.STRICT
                                    && text != null
                                    && !response.aiMessage().hasToolExecutionRequests()) {
                                handler.onPartialResponse(
                                        new PartialResponse(text),
                                        new PartialResponseContext(
                                                new StreamingHandle() {
                                                    @Override
                                                    public void cancel() {
                                                        session.revoke();
                                                    }

                                                    @Override
                                                    public boolean isCancelled() {
                                                        return session.budgetSnapshot().revoked();
                                                    }
                                                }));
                            }
                            session.checkDelivery();
                            handler.onCompleteResponse(response);
                        } catch (Throwable failure) {
                            String code = safe(failure).code();
                            session.refuse(code);
                            handler.onError(new GovernanceException(code));
                        }
                    }
                });
    }

    static GovernanceException safe(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        return new GovernanceException(
                error instanceof GovernanceException g ? g.code() : "stream_callback_failed");
    }

    @Override
    public Flow.Publisher<ChatModelStreamingEvent> chat(ChatRequest request) {
        return new HandlerPublisher(
                control.currentSession(), limits, handler -> chat(request, handler));
    }

    @Override
    public Flow.Publisher<ChatModelStreamingEvent> doChat(ChatRequest request) {
        return chat(request);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }
}
