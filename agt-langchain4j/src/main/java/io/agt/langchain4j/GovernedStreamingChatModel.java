package io.agt.langchain4j;

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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

/**
 * 严格缓冲模型装饰器。
 *
 * <p>整轮 post_model_call 通过后才交付文本与完成事件。
 */
final class GovernedStreamingChatModel implements StreamingChatModel {
    private final AgentControl control;
    private final ChatModel governed;
    private final StreamingChatModel delegate;

    GovernedStreamingChatModel(
            AgentControl control, StreamingChatModel delegate, StreamingLimits limits) {
        this.control = control;
        this.delegate = delegate;
        ChatModel collected =
                new ChatModel() {
                    @Override
                    public CompletableFuture<ChatResponse> chatAsync(
                            ChatRequest request, ChatRequestOptions options) {
                        return StreamingCollector.collect(
                                control.currentSession(), delegate, limits, request, options);
                    }

                    @Override
                    public ChatRequestParameters defaultRequestParameters() {
                        return delegate.defaultRequestParameters();
                    }
                };
        governed = new GovernedChatModel(control, collected);
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
        CompletableFuture<ChatResponse> future = governed.chatAsync(request, options);
        future.whenComplete(
                (response, error) -> {
                    try (var scope = session.bind()) {
                        if (error != null) {
                            handler.onError(safe(error));
                            return;
                        }
                        try {
                            session.checkActive();
                            String text = response.aiMessage().text();
                            if (text != null && !response.aiMessage().hasToolExecutionRequests()) {
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
                            session.checkActive();
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
        throw new GovernanceException("reactive_stream_unsupported");
    }

    @Override
    public Flow.Publisher<ChatModelStreamingEvent> doChat(ChatRequest request) {
        throw new GovernanceException("reactive_stream_unsupported");
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
