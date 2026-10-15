package io.agt.langchain4j;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import io.agt.core.HostSession;
import io.agt.langchain4j.internal.streaming.BufferedStreamControl;
import io.agt.langchain4j.internal.streaming.StreamingAdapters;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 单次启动的严格缓冲 TokenStream。
 *
 * <p>输出检查通过后重放一个有效文本块；通过 completion 等待交付，通过 cancel 取消。
 */
public final class BufferedTokenStream implements TokenStream {
    private final BufferedStreamControl delegate;

    BufferedTokenStream(HostSession session, Supplier<CompletableFuture<ChatResponse>> invoke) {
        delegate = StreamingAdapters.buffered(session, invoke);
    }

    public CompletableFuture<ChatResponse> completion() {
        return delegate.completion();
    }

    public boolean cancel() {
        return delegate.cancel();
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public TokenStream onPartialResponse(Consumer<String> handler) {
        delegate.onPartialResponse(handler);
        return this;
    }

    @Override
    public TokenStream onPartialResponseWithContext(
            BiConsumer<PartialResponse, PartialResponseContext> handler) {
        delegate.onPartialResponseWithContext(handler);
        return this;
    }

    @Override
    public TokenStream onCompleteResponse(Consumer<ChatResponse> handler) {
        delegate.onCompleteResponse(handler);
        return this;
    }

    @Override
    public TokenStream onError(Consumer<Throwable> handler) {
        delegate.onError(handler);
        return this;
    }

    @Override
    public TokenStream ignoreErrors() {
        delegate.ignoreErrors();
        return this;
    }

    @Override
    public TokenStream onPartialThinking(Consumer<PartialThinking> handler) {
        delegate.onPartialThinking(handler);
        return this;
    }

    @Override
    public TokenStream onPartialThinkingWithContext(
            BiConsumer<PartialThinking, PartialThinkingContext> handler) {
        delegate.onPartialThinkingWithContext(handler);
        return this;
    }

    @Override
    public TokenStream onPartialToolCall(Consumer<PartialToolCall> handler) {
        delegate.onPartialToolCall(handler);
        return this;
    }

    @Override
    public TokenStream onPartialToolCallWithContext(
            BiConsumer<PartialToolCall, PartialToolCallContext> handler) {
        delegate.onPartialToolCallWithContext(handler);
        return this;
    }

    @Override
    public TokenStream onRetrieved(Consumer<List<Content>> handler) {
        delegate.onRetrieved(handler);
        return this;
    }

    @Override
    public TokenStream onIntermediateResponse(Consumer<ChatResponse> handler) {
        delegate.onIntermediateResponse(handler);
        return this;
    }

    @Override
    public TokenStream beforeToolExecution(Consumer<BeforeToolExecution> handler) {
        delegate.beforeToolExecution(handler);
        return this;
    }

    @Override
    public TokenStream onUnmappedRawEvent(Consumer<Object> handler) {
        delegate.onUnmappedRawEvent(handler);
        return this;
    }

    @Override
    public TokenStream onToolExecuted(Consumer<ToolExecution> handler) {
        delegate.onToolExecuted(handler);
        return this;
    }
}
