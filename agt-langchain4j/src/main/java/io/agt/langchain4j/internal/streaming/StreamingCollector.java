package io.agt.langchain4j.internal.streaming;

import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.JsonValues;
import io.agt.core.streaming.ChunkBufferEngine;
import io.agt.langchain4j.StreamingLimits;
import io.agt.langchain4j.internal.message.TextProjection;
import java.util.concurrent.CompletableFuture;

/**
 * 把 provider 的事件转换为单个完整响应。
 *
 * <p>不保留或传播增量原文。
 */
final class StreamingCollector implements StreamingChatResponseHandler {
    private final StreamingLimits limits;
    private final HostSession session;
    private final StreamingChatResponseHandler preview;
    private final CompletableFuture<ChatResponse> result = new CompletableFuture<>();
    private final ChunkBufferEngine guard;
    private volatile StreamingHandle handle;
    private long characters;
    private long events;
    private boolean terminal;

    private StreamingCollector(
            HostSession session,
            StreamingLimits limits,
            StreamingChatResponseHandler preview,
            ChunkBufferEngine guard) {
        this.limits = limits;
        this.session = session;
        this.preview = preview;
        this.guard = guard;
        result.whenComplete(
                (value, error) -> {
                    if (error != null) {
                        if (guard != null) guard.close();
                        cancelTransport();
                    }
                });
    }

    static CompletableFuture<ChatResponse> collect(
            HostSession session,
            StreamingChatModel model,
            StreamingLimits limits,
            ChatRequest request,
            ChatRequestOptions options) {
        return collect(session, model, limits, request, options, null);
    }

    static CompletableFuture<ChatResponse> collect(
            HostSession session,
            StreamingChatModel model,
            StreamingLimits limits,
            ChatRequest request,
            ChatRequestOptions options,
            StreamingChatResponseHandler preview) {
        return collect(session, model, limits, request, options, preview, null);
    }

    static CompletableFuture<ChatResponse> collect(
            HostSession session,
            StreamingChatModel model,
            StreamingLimits limits,
            ChatRequest request,
            ChatRequestOptions options,
            StreamingChatResponseHandler preview,
            ChunkBufferEngine guard) {
        var collector = new StreamingCollector(session, limits, preview, guard);
        // provider 的入口可能尚未返回；取得 handle 后也必须能立即响应会话终止。
        session.watchTermination(collector.result);
        try {
            session.checkActive();
            model.chat(request, options, collector);
        } catch (Throwable error) {
            collector.onError(error);
        }
        return collector.result;
    }

    private boolean charge(String text) {
        return charge(text == null ? 0 : text.length(), false);
    }

    private boolean charge(long length, boolean complete) {
        boolean exceeded;
        synchronized (this) {
            if (terminal || result.isDone()) {
                return false;
            }
            events++;
            characters += length;
            exceeded = events > limits.maxEvents() || characters > limits.maxCharacters();
            if (exceeded || complete) {
                terminal = true;
            }
        }
        if (exceeded) {
            result.completeExceptionally(new GovernanceException("stream_capacity_exceeded"));
        }
        return !exceeded;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private void capture(StreamingHandle value) {
        handle = value;
        if (result.isCompletedExceptionally()) {
            cancelTransport();
        }
    }

    private void cancelTransport() {
        StreamingHandle current = handle;
        if (current != null) {
            try {
                current.cancel();
            } catch (RuntimeException ignored) {
                // provider 不支持取消时，受控 future 仍已失败，晚到结果不会传播。
            }
        }
    }

    @Override
    public void onPartialResponse(String text) {
        if (charge(text)) {
            if (guard != null) guard.accept(text);
            else preview(handler -> handler.onPartialResponse(text));
        }
    }

    @Override
    public void onPartialResponse(PartialResponse value, PartialResponseContext context) {
        capture(context.streamingHandle());
        if (charge(value.text())) {
            if (guard != null) guard.accept(value.text());
            else preview(handler -> handler.onPartialResponse(value, context));
        }
    }

    @Override
    public void onPartialThinking(PartialThinking value) {
        if (charge(value.text())) {
            preview(handler -> handler.onPartialThinking(value));
        }
    }

    @Override
    public void onPartialThinking(PartialThinking value, PartialThinkingContext context) {
        capture(context.streamingHandle());
        if (charge(value.text())) {
            preview(handler -> handler.onPartialThinking(value, context));
        }
    }

    @Override
    public void onPartialToolCall(PartialToolCall value) {
        if (charge(
                (long) length(value.id()) + length(value.name()) + length(value.partialArguments()),
                false)) {
            preview(handler -> handler.onPartialToolCall(value));
        }
    }

    @Override
    public void onPartialToolCall(PartialToolCall value, PartialToolCallContext context) {
        capture(context.streamingHandle());
        onPartialToolCall(value);
    }

    @Override
    public void onCompleteToolCall(CompleteToolCall value) {
        var request = value.toolExecutionRequest();
        charge(
                (long) length(request.id()) + length(request.name()) + length(request.arguments()),
                false);
    }

    @Override
    public void onUnmappedRawEvent(Object value) {
        if (preview == null) {
            charge(null);
            return;
        }
        try {
            if (charge(
                    JsonValues.write(
                            io.agt.langchain4j.internal.service.TypedValues.project(value)))) {
                preview(handler -> handler.onUnmappedRawEvent(value));
            }
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    private void preview(java.util.function.Consumer<StreamingChatResponseHandler> emit) {
        if (preview == null || result.isDone()) {
            return;
        }
        try (var scope = session.bind()) {
            session.checkActive();
            emit.accept(preview);
        } catch (Throwable error) {
            onError(error);
        }
    }

    @Override
    public void onCompleteResponse(ChatResponse response) {
        if (result.isDone()) {
            return;
        }
        try {
            if (guard != null) guard.seal(response.aiMessage().text());
            String payload = JsonValues.write(TextProjection.message(response.aiMessage()));
            if (charge(payload.length(), true)) {
                result.complete(response);
            }
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    @Override
    public void onError(Throwable error) {
        synchronized (this) {
            if (terminal || result.isDone()) {
                return;
            }
            terminal = true;
        }
        result.completeExceptionally(
                new GovernanceException(
                        error instanceof GovernanceException g ? g.code() : "stream_failed"));
    }
}
