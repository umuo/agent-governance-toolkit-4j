package io.agt.langchain4j.internal.streaming;

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
import io.agt.langchain4j.StreamDelivery;
import io.agt.langchain4j.internal.service.CallbackViews;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 单次启动的受控 TokenStream。
 *
 * <p>严格模式检查后交付文本，实时模式交付预览；完整结果经检查，通过 completion 等待、cancel 取消。
 */
final class BufferedStream implements BufferedStreamControl {
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
    private final StreamDelivery delivery;
    private final boolean extended;
    private boolean previewed;
    private Consumer<PartialThinking> thinking;
    private BiConsumer<PartialThinking, PartialThinkingContext> thinkingContext;
    private Consumer<PartialToolCall> tool;
    private BiConsumer<PartialToolCall, PartialToolCallContext> toolContext;
    private Consumer<List<Content>> retrieved;
    private Consumer<ChatResponse> intermediate;
    private Consumer<BeforeToolExecution> before;
    private Consumer<ToolExecution> after;
    private Consumer<Object> raw;

    BufferedStream(
            HostSession session,
            Supplier<CompletableFuture<ChatResponse>> invoke,
            StreamDelivery delivery,
            boolean extended) {
        this.session = session;
        if (delivery == StreamDelivery.REALTIME && session.control().requiresCompleteOutputCheck())
            throw new GovernanceException("injection_strict_stream_required");
        this.invoke = invoke;
        this.delivery = delivery;
        this.extended = extended;
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
    public synchronized TokenStream ignoreErrors() {
        if (!extended) {
            throw new GovernanceException("stream_ignore_errors_unsupported");
        }
        configurable();
        if (error != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        error = ignored -> {};
        return this;
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
                session.checkDelivery();
                AiMessage message = response.aiMessage();
                if (message.text() != null && (delivery == StreamDelivery.STRICT || !previewed)) {
                    emitText(message.text());
                }
                if (extended && delivery == StreamDelivery.STRICT && message.thinking() != null) {
                    emitThinking(new PartialThinking(message.thinking()));
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

    private StreamingHandle handle() {
        return new StreamingHandle() {
            public void cancel() {
                BufferedStream.this.cancel();
            }

            public boolean isCancelled() {
                return completion.isCancelled();
            }
        };
    }

    private void emitText(String text) {
        if (partial != null) {
            partial.accept(text);
        }
        if (partialWithContext != null) {
            partialWithContext.accept(
                    new PartialResponse(text), new PartialResponseContext(handle()));
        }
    }

    private void emitThinking(PartialThinking value) {
        if (thinking != null) {
            thinking.accept(value);
        }
        if (thinkingContext != null) {
            thinkingContext.accept(value, new PartialThinkingContext(handle()));
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized void accept(String kind, Object value) {
        if (!extended || !started || completion.isDone()) {
            return;
        }
        try (var scope = session.bind()) {
            session.checkDelivery();
            if (kind.equals("text")
                    || kind.equals("thinking")
                    || kind.equals("tool")
                    || kind.equals("raw")) {
                if (delivery != StreamDelivery.REALTIME) {
                    return;
                }
                switch (kind) {
                    case "text" -> {
                        previewed = true;
                        emitText((String) value);
                    }
                    case "thinking" -> emitThinking((PartialThinking) value);
                    case "tool" -> {
                        if (tool != null) {
                            tool.accept((PartialToolCall) value);
                        }
                        if (toolContext != null) {
                            toolContext.accept(
                                    (PartialToolCall) value, new PartialToolCallContext(handle()));
                        }
                    }
                    case "raw" -> {
                        if (raw != null) {
                            raw.accept(value);
                        }
                    }
                    default -> {}
                }
                return;
            }
            switch (kind) {
                case "retrieved" -> {
                    if (retrieved != null) {
                        retrieved.accept(
                                (List<Content>) CallbackViews.checked(session, value, List.class));
                    }
                }
                case "intermediate" -> {
                    if (intermediate != null) {
                        intermediate.accept(
                                (ChatResponse)
                                        CallbackViews.checked(session, value, ChatResponse.class));
                    }
                }
                case "before" -> {
                    if (before != null) {
                        before.accept(
                                (BeforeToolExecution)
                                        CallbackViews.checked(
                                                session, value, BeforeToolExecution.class));
                    }
                }
                case "after" -> {
                    if (after != null) {
                        after.accept(
                                (ToolExecution)
                                        CallbackViews.checked(session, value, ToolExecution.class));
                    }
                }
                default -> throw new GovernanceException("stream_event_unsupported");
            }
        } catch (Throwable error) {
            var safe = GovernedStreamingChatModel.safe(error);
            session.refuse(safe.code());
            completion.completeExceptionally(safe);
        }
    }

    private TokenStream unsupported() {
        throw new GovernanceException("stream_side_channel_unsupported");
    }

    @Override
    public synchronized TokenStream onPartialThinking(Consumer<PartialThinking> handler) {
        if (!extended) {
            return unsupported();
        }
        configurable();
        if (thinking != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        thinking = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public synchronized TokenStream onPartialThinkingWithContext(
            BiConsumer<PartialThinking, PartialThinkingContext> handler) {
        if (!extended) {
            return unsupported();
        }
        configurable();
        if (thinkingContext != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        thinkingContext = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public synchronized TokenStream onPartialToolCall(Consumer<PartialToolCall> handler) {
        if (!extended) {
            return unsupported();
        }
        configurable();
        if (tool != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        tool = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public synchronized TokenStream onPartialToolCallWithContext(
            BiConsumer<PartialToolCall, PartialToolCallContext> handler) {
        if (!extended) {
            return unsupported();
        }
        configurable();
        if (toolContext != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        toolContext = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public synchronized TokenStream onRetrieved(Consumer<List<Content>> handler) {
        if (!extended) {
            return unsupported();
        }
        configurable();
        if (retrieved != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        retrieved = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public synchronized TokenStream onIntermediateResponse(Consumer<ChatResponse> handler) {
        if (!extended) {
            return unsupported();
        }
        configurable();
        if (intermediate != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        intermediate = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public synchronized TokenStream beforeToolExecution(Consumer<BeforeToolExecution> handler) {
        if (!extended) {
            return unsupported();
        }
        configurable();
        if (before != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        before = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public synchronized TokenStream onUnmappedRawEvent(Consumer<Object> handler) {
        if (!extended) {
            return unsupported();
        }
        configurable();
        if (raw != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        raw = Objects.requireNonNull(handler);
        return this;
    }

    @Override
    public synchronized TokenStream onToolExecuted(Consumer<ToolExecution> handler) {
        if (!extended) {
            return unsupported();
        }
        configurable();
        if (after != null) {
            throw new GovernanceException("duplicate_stream_callback");
        }
        after = Objects.requireNonNull(handler);
        return this;
    }
}
