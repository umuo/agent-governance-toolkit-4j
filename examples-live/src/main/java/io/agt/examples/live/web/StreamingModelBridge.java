package io.agt.examples.live.web;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将真实模型 SSE 聚合成供 AGT 检查的完整响应。
 *
 * <p>可选 preview 仅用于明确标注的未检查展示；工具执行仍只使用完整响应并经过 guardTool。
 */
public final class StreamingModelBridge implements ChatModel {
    private final StreamingChatModel delegate;
    private final AgentEvents events;
    private final boolean preview;
    private final AtomicReference<StreamingHandle> handle;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Throwable failure;

    public Throwable failure() {
        return failure;
    }

    public StreamingModelBridge(
            StreamingChatModel delegate,
            AgentEvents events,
            boolean preview,
            AtomicReference<StreamingHandle> handle) {
        this.delegate = delegate;
        this.events = events;
        this.preview = preview;
        this.handle = handle;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        int call = calls.incrementAndGet();
        var result = new CompletableFuture<ChatResponse>();
        var size = new AtomicInteger();
        handle.set(null);
        events.send("model_start", Map.of("call", call));
        try {
            delegate.chat(
                    request,
                    new StreamingChatResponseHandler() {
                        private boolean accept(int length) {
                            if (result.isDone()) {
                                return false;
                            }
                            if (size.addAndGet(length) > 131072) {
                                failure = new IllegalStateException("model_stream_size_limit");
                                result.completeExceptionally(failure);
                                cancel();
                                return false;
                            }
                            return true;
                        }

                        @Override
                        public void onPartialResponse(
                                PartialResponse response, PartialResponseContext context) {
                            handle.set(context.streamingHandle());
                            onPartialResponse(response.text());
                        }

                        @Override
                        public void onPartialResponse(String text) {
                            if (!accept(text.length())) {
                                return;
                            }
                            if (preview) {
                                events.send(
                                        "text_delta",
                                        Map.of("text", text, "call", call, "preview", true));
                            }
                        }

                        @Override
                        public void onPartialThinking(
                                PartialThinking thinking, PartialThinkingContext context) {
                            handle.set(context.streamingHandle());
                            onPartialThinking(thinking);
                        }

                        @Override
                        public void onPartialThinking(PartialThinking thinking) {
                            if (!accept(thinking.text().length())) {
                                return;
                            }
                            if (preview) {
                                events.send(
                                        "reasoning_delta",
                                        Map.of(
                                                "text",
                                                thinking.text(),
                                                "call",
                                                call,
                                                "preview",
                                                true,
                                                "source",
                                                "provider"));
                            }
                        }

                        @Override
                        public void onPartialToolCall(
                                PartialToolCall tool, PartialToolCallContext context) {
                            handle.set(context.streamingHandle());
                            onPartialToolCall(tool);
                        }

                        @Override
                        public void onPartialToolCall(PartialToolCall tool) {
                            String arguments =
                                    tool.partialArguments() == null ? "" : tool.partialArguments();
                            if (!accept(arguments.length())) {
                                return;
                            }
                            if (preview) {
                                events.send(
                                        "tool_delta",
                                        Map.of(
                                                "key",
                                                call + ":" + tool.index(),
                                                "id",
                                                tool.id() == null ? "" : tool.id(),
                                                "name",
                                                tool.name() == null ? "" : tool.name(),
                                                "arguments",
                                                arguments,
                                                "preview",
                                                true));
                            }
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse response) {
                            if (!result.isDone()) {
                                events.send(
                                        "model_complete",
                                        Map.of(
                                                "call",
                                                call,
                                                "hasTools",
                                                response.aiMessage().hasToolExecutionRequests()));
                            }
                            result.complete(response);
                        }

                        @Override
                        public void onError(Throwable error) {
                            failure = error;
                            org.slf4j.LoggerFactory.getLogger(StreamingModelBridge.class)
                                    .error("Provider streaming failed", error);
                            result.completeExceptionally(error);
                        }
                    });
        } catch (RuntimeException error) {
            failure = error;
            org.slf4j.LoggerFactory.getLogger(StreamingModelBridge.class)
                    .error("Provider request failed", error);
            throw error;
        }
        try {
            return result.get(65, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cancel();
            throw new IllegalStateException("model_cancelled", error);
        } catch (ExecutionException error) {
            failure = error.getCause();
            throw new IllegalStateException("provider_stream_failed", error.getCause());
        } catch (java.util.concurrent.TimeoutException error) {
            failure = error;
            cancel();
            throw new IllegalStateException("provider_stream_timeout", error);
        }
    }

    private void cancel() {
        var current = handle.get();
        if (current != null) {
            current.cancel();
        }
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
