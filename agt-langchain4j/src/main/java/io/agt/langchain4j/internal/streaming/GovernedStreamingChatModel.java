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
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.InterventionPoint;
import io.agt.core.streaming.ChunkBufferEngine;
import io.agt.core.streaming.ChunkBufferOptions;
import io.agt.langchain4j.StreamDelivery;
import io.agt.langchain4j.StreamingLimits;
import io.agt.langchain4j.internal.message.TextProjection;
import io.agt.langchain4j.internal.model.ModelAdapters;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

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
    private final ChunkBufferOptions guardrails;

    GovernedStreamingChatModel(
            AgentControl control,
            StreamingChatModel delegate,
            StreamingLimits limits,
            StreamDelivery delivery,
            ChunkBufferOptions guardrails) {
        this.control = control;
        this.delegate = delegate;
        this.limits = limits;
        this.delivery = delivery;
        this.guardrails = guardrails;
    }

    private ChatModel governed(
            StreamingChatResponseHandler handler,
            AtomicReference<ChunkBufferEngine> gate,
            AtomicReference<String> round) {
        ChatModel collected =
                new ChatModel() {
                    @Override
                    public CompletableFuture<ChatResponse> chatAsync(
                            ChatRequest request, ChatRequestOptions options) {
                        HostSession session = control.currentSession();
                        if (delivery == StreamDelivery.GUARDED) {
                            round.set(session.currentActionId());
                            gate.set(newGuard(session, handler, round.get()));
                        }
                        return StreamingCollector.collect(
                                control.currentSession(),
                                delegate,
                                limits,
                                request,
                                options,
                                delivery == StreamDelivery.REALTIME ? handler : null,
                                gate.get());
                    }

                    @Override
                    public ChatRequestParameters defaultRequestParameters() {
                        return delegate.defaultRequestParameters();
                    }
                };
        return ModelAdapters.guard(control, collected);
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
        var gate = new AtomicReference<ChunkBufferEngine>();
        var round = new AtomicReference<String>();
        CompletableFuture<ChatResponse> future;
        try {
            future = governed(handler, gate, round).chatAsync(request, options);
        } catch (Throwable error) {
            if (gate.get() != null) gate.get().close();
            handler.onError(safe(error));
            return;
        }
        future.whenComplete(
                (response, error) -> {
                    var guard = gate.get();
                    try (var scope = session.bind()) {
                        if (error != null) {
                            if (guard != null) guard.close();
                            handler.onError(safe(error));
                            return;
                        }
                        try {
                            session.checkActive();
                            String text = response.aiMessage().text();
                            if (guard != null) {
                                // The undecided tail also waits for a full output check.
                                // Service-level
                                // aggregate OUTPUT governance still runs on its existing path.
                                if (!response.aiMessage().hasToolExecutionRequests()) {
                                    session.evaluate(
                                            InterventionPoint.OUTPUT,
                                            round.get(),
                                            null,
                                            TextProjection.message(response.aiMessage()));
                                }
                                guard.complete(text);
                            }
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
                            session.checkActive();
                            // Native streaming services require complete tool-call events to
                            // populate their execution results. Replay only authoritative calls
                            // after post_model_call, never provider partial tool events.
                            var calls = response.aiMessage().toolExecutionRequests();
                            for (int index = 0; index < calls.size(); index++) {
                                session.checkActive();
                                handler.onCompleteToolCall(
                                        new CompleteToolCall(index, calls.get(index)));
                            }
                            session.checkActive();
                            handler.onCompleteResponse(response);
                        } catch (Throwable failure) {
                            if (guard != null) guard.close();
                            String code = safe(failure).code();
                            session.refuse(code);
                            handler.onError(new GovernanceException(code));
                        }
                    }
                });
    }

    private ChunkBufferEngine newGuard(
            HostSession session, StreamingChatResponseHandler handler, String round) {
        return session.chunkBuffer(
                guardrails,
                round,
                text ->
                        handler.onPartialResponse(
                                new PartialResponse(text),
                                new PartialResponseContext(
                                        new StreamingHandle() {
                                            public void cancel() {
                                                session.revoke();
                                            }

                                            public boolean isCancelled() {
                                                return session.budgetSnapshot().revoked();
                                            }
                                        })),
                error -> session.refuse(safe(error).code()));
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
