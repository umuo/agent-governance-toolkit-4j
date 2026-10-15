package io.agt.langchain4j.internal.model;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.InterventionPoint;
import io.agt.langchain4j.internal.concurrent.AsyncSources;
import io.agt.langchain4j.internal.message.TextProjection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 受治理的同步与异步聊天模型包装器。
 *
 * <p>拦截 PRE_MODEL_CALL 与 POST_MODEL_CALL，执行模型请求参数校验、改写、Token 审计及失败安全处理。
 */
final class GovernedChatModel implements ChatModel {
    private final AgentControl control;
    private final ChatModel delegate;

    GovernedChatModel(AgentControl control, ChatModel delegate) {
        this.control = control;
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return chat(request, ChatRequestOptions.EMPTY);
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        HostSession session = control.currentSession();
        try {
            // delegate 默认参数先合并，以便治理检查实际发送的请求；listener 不暴露未治理响应。
            var merged =
                    request.toBuilder()
                            .parameters(
                                    delegate.defaultRequestParameters()
                                            .overrideWith(request.parameters()))
                            .build();
            var input = merged.messages().stream().map(TextProjection::message).toList();
            return session.operationPrepared(
                    false,
                    null,
                    input,
                    effective -> {
                        List<?> values = TextProjection.list(effective);
                        if (values.size() != merged.messages().size()) {
                            TextProjection.unsupported();
                        }
                        List<ChatMessage> messages = new ArrayList<>();
                        for (int i = 0; i < values.size(); i++) {
                            messages.add(
                                    TextProjection.replace(
                                            merged.messages().get(i), values.get(i)));
                        }
                        return merged.toBuilder().messages(messages).build();
                    },
                    prepared -> {
                        ChatResponse response = delegate.chat(prepared, options);
                        session.recordTokens(
                                response.tokenUsage() == null
                                        ? null
                                        : response.tokenUsage().totalTokenCount());
                        return response;
                    },
                    response -> TextProjection.message(response.aiMessage()),
                    (response, effective) ->
                            response.toBuilder()
                                    .aiMessage(
                                            (AiMessage)
                                                    TextProjection.replace(
                                                            response.aiMessage(), effective))
                                    .build());
        } catch (RuntimeException e) {
            String code = e instanceof GovernanceException g ? g.code() : "model_failed";
            throw session.budgetSnapshot().failed()
                    ? session.refuse(code)
                    : session.fail(InterventionPoint.PRE_MODEL_CALL, code);
        }
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return chat(request);
    }

    @Override
    public CompletableFuture<ChatResponse> chatAsync(
            ChatRequest request, ChatRequestOptions options) {
        HostSession captured = null;
        try {
            HostSession session = control.currentSession();
            captured = session;
            var merged =
                    request.toBuilder()
                            .parameters(
                                    delegate.defaultRequestParameters()
                                            .overrideWith(request.parameters()))
                            .build();
            var input = merged.messages().stream().map(TextProjection::message).toList();
            return session.operationAsyncPrepared(
                    false,
                    null,
                    input,
                    effective -> {
                        List<?> values = TextProjection.list(effective);
                        if (values.size() != merged.messages().size()) {
                            TextProjection.unsupported();
                        }
                        List<ChatMessage> messages = new ArrayList<>();
                        for (int i = 0; i < values.size(); i++) {
                            messages.add(
                                    TextProjection.replace(
                                            merged.messages().get(i), values.get(i)));
                        }
                        return merged.toBuilder().messages(messages).build();
                    },
                    prepared ->
                            AsyncSources.normalize(delegate.chatAsync(prepared, options), "model"),
                    response -> {
                        session.recordTokens(
                                response.tokenUsage() == null
                                        ? null
                                        : response.tokenUsage().totalTokenCount());
                        return TextProjection.message(response.aiMessage());
                    },
                    (response, effective) ->
                            response.toBuilder()
                                    .aiMessage(
                                            (AiMessage)
                                                    TextProjection.replace(
                                                            response.aiMessage(), effective))
                                    .build());
        } catch (RuntimeException e) {
            if (captured != null && !captured.budgetSnapshot().failed()) {
                try {
                    e =
                            captured.fail(
                                    InterventionPoint.PRE_MODEL_CALL,
                                    e instanceof GovernanceException g ? g.code() : "model_failed");
                } catch (GovernanceException failure) {
                    e = failure;
                }
            }
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<ChatResponse> doChatAsync(ChatRequest request) {
        return chatAsync(request, ChatRequestOptions.EMPTY);
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
