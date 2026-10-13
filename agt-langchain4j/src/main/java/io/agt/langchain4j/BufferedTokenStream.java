package io.agt.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 单次启动的严格缓冲 TokenStream。
 *
 * <p>输出检查通过后重放一个有效文本块；通过 completion 等待交付，通过 cancel 取消。
 */
public final class BufferedTokenStream implements TokenStream {
    private final HostSession session;
    private final Supplier<CompletableFuture<ChatResponse>> invoke;
    private final CompletableFuture<ChatResponse> completion = new CompletableFuture<>();
    private CompletableFuture<ChatResponse> running;
    private volatile boolean started;
    private final AtomicBoolean errorDelivered = new AtomicBoolean();
    private Consumer<String> partial;
    private BiConsumer<PartialResponse, PartialResponseContext> partialWithContext;
    private Consumer<ChatResponse> complete;
    private Consumer<Throwable> error;

    BufferedTokenStream(HostSession session, Supplier<CompletableFuture<ChatResponse>> invoke) {
        this.session = session;
        this.invoke = invoke;
        session.watchTermination(completion);
        completion.whenComplete(
                (value, failure) -> {
                    if (completion.isCancelled()) {
                        session.refuse("operation_cancelled");
                        CompletableFuture<ChatResponse> task;
                        synchronized (this) {
                            task = running;
                        }
                        if (task != null) {
                            task.cancel(true);
                        }
                    } else if (failure != null) {
                        notifyError(GovernedStreamingChatModel.safe(failure));
                    }
                });
    }

    public CompletableFuture<ChatResponse> completion() {
        return completion;
    }

    public boolean cancel() {
        return completion.cancel(true);
    }

    private void configurable() {
        if (started || completion.isDone()) {
            throw new GovernanceException("stream_already_started");
        }
    }

    @Override
    public synchronized TokenStream onPartialResponse(Consumer<String> handler) {
        configurable();
        if (partial != null || partialWithContext != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        partial = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public synchronized TokenStream onPartialResponseWithContext(
            BiConsumer<PartialResponse, PartialResponseContext> handler) {
        configurable();
        if (partial != null || partialWithContext != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        partialWithContext = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public synchronized TokenStream onCompleteResponse(Consumer<ChatResponse> handler) {
        configurable();
        if (complete != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        complete = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public synchronized TokenStream onError(Consumer<Throwable> handler) {
        configurable();
        if (error != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        error = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public TokenStream ignoreErrors() {
        throw new GovernanceException("stream_ignore_errors_unsupported");
    }

    @Override
    public void start() {
        synchronized (this) {
            configurable();
            started = true;
        }
        try (var scope = session.bind()) {
            var task = invoke.get();
            synchronized (this) {
                running = task;
                if (completion.isDone()) {
                    task.cancel(true);
                }
            }
            task.whenComplete((response, failure) -> deliver(response, failure));
        } catch (Throwable failure) {
            deliver(null, failure);
        }
    }

    private void deliver(ChatResponse response, Throwable failure) {
        if (completion.isDone()) {
            return;
        }
        try (var scope = session.bind()) {
            try {
                if (failure != null) {
                    throw GovernedStreamingChatModel.safe(failure);
                }
                session.checkActive();
                AiMessage message = response.aiMessage();
                if (message.text() != null) {
                    if (partial != null) {
                        partial.accept(message.text());
                    }
                    if (partialWithContext != null) {
                        partialWithContext.accept(
                                new PartialResponse(message.text()),
                                new PartialResponseContext(
                                        new StreamingHandle() {
                                            @Override
                                            public void cancel() {
                                                BufferedTokenStream.this.cancel();
                                            }

                                            @Override
                                            public boolean isCancelled() {
                                                return completion.isCancelled();
                                            }
                                        }));
                    }
                }
                session.checkActive();
                if (complete != null) {
                    complete.accept(response);
                }
                session.checkActive();
                completion.complete(response);
            } catch (Throwable problem) {
                var safe = GovernedStreamingChatModel.safe(problem);
                session.refuse(safe.code());
                completion.completeExceptionally(safe);
            }
        }
    }

    private void notifyError(GovernanceException failure) {
        Consumer<Throwable> handler;
        synchronized (this) {
            handler = error;
        }
        if (!started || handler == null || !errorDelivered.compareAndSet(false, true)) {
            return;
        }
        try (var scope = session.bind()) {
            try {
                handler.accept(failure);
            } catch (Throwable ignored) {
                // 错误回调不能恢复受控路径，也不能把异常原文送给其他回调。
            }
        }
    }

    private TokenStream unsupported() {
        throw new GovernanceException("stream_side_channel_unsupported");
    }

    @Override
    public TokenStream onPartialThinking(Consumer<PartialThinking> handler) {
        return unsupported();
    }

    @Override
    public TokenStream onPartialThinkingWithContext(
            BiConsumer<PartialThinking, PartialThinkingContext> handler) {
        return unsupported();
    }

    @Override
    public TokenStream onPartialToolCall(Consumer<PartialToolCall> handler) {
        return unsupported();
    }

    @Override
    public TokenStream onPartialToolCallWithContext(
            BiConsumer<PartialToolCall, PartialToolCallContext> handler) {
        return unsupported();
    }

    @Override
    public TokenStream onRetrieved(Consumer<List<Content>> handler) {
        return unsupported();
    }

    @Override
    public TokenStream onIntermediateResponse(Consumer<ChatResponse> handler) {
        return unsupported();
    }

    @Override
    public TokenStream beforeToolExecution(Consumer<BeforeToolExecution> handler) {
        return unsupported();
    }

    @Override
    public TokenStream onUnmappedRawEvent(Consumer<Object> handler) {
        return unsupported();
    }

    @Override
    public TokenStream onToolExecuted(Consumer<ToolExecution> handler) {
        return unsupported();
    }
}
