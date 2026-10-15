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
    private final CompletableFuture<ChatResponse> result = new CompletableFuture<>();
    private volatile StreamingHandle handle;
    private long characters;
    private long events;
    private boolean terminal;

    private StreamingCollector(StreamingLimits limits) {
        this.limits = limits;
        result.whenComplete(
                (value, error) -> {
                    if (error != null) {
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
        var collector = new StreamingCollector(limits);
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
        charge(text);
    }

    @Override
    public void onPartialResponse(PartialResponse value, PartialResponseContext context) {
        capture(context.streamingHandle());
        charge(value.text());
    }

    @Override
    public void onPartialThinking(PartialThinking value) {
        charge(value.text());
    }

    @Override
    public void onPartialThinking(PartialThinking value, PartialThinkingContext context) {
        capture(context.streamingHandle());
        charge(value.text());
    }

    @Override
    public void onPartialToolCall(PartialToolCall value) {
        charge(
                (long) length(value.id()) + length(value.name()) + length(value.partialArguments()),
                false);
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
        charge(null);
    }

    @Override
    public void onCompleteResponse(ChatResponse response) {
        if (result.isDone()) {
            return;
        }
        try {
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
