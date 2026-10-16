package io.agt.langchain4j.internal.model;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.moderation.Moderation;
import dev.langchain4j.model.moderation.ModerationModel;
import dev.langchain4j.model.moderation.ModerationRequest;
import dev.langchain4j.model.moderation.ModerationResponse;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.service.AiServiceContext;
import dev.langchain4j.service.Moderate;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.langchain4j.internal.message.TextProjection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 在有会话的实际模型请求边界运行原生审核模型。
 *
 * <p>框架自带审核任务不传播线程上下文；这里保留 Moderate 选择语义，实际审核同步门控于首轮模型请求。
 */
public final class ModerationGate {
    private ModerationGate() {}

    public static ChatModel install(
            AgentControl control, AiServiceContext context, ChatModel guarded) {
        ModerationModel delegate = context.moderationModel;
        if (!delegate.listeners().isEmpty()) {
            throw new GovernanceException("raw_moderation_listeners_unsupported");
        }
        var enabled = new ThreadLocal<Boolean>();
        context.eventListenerRegistrar.register(
                new AiServiceListener<AiServiceStartedEvent>() {
                    public Class<AiServiceStartedEvent> getEventClass() {
                        return AiServiceStartedEvent.class;
                    }

                    public void onEvent(AiServiceStartedEvent event) {
                        var candidates =
                                Arrays.stream(context.aiServiceClass.getMethods())
                                        .filter(
                                                m ->
                                                        m.getName()
                                                                .equals(
                                                                        event.invocationContext()
                                                                                .methodName()))
                                        .filter(
                                                m ->
                                                        m.getParameterCount()
                                                                == event.invocationContext()
                                                                        .methodArguments()
                                                                        .size())
                                        .toList();
                        boolean moderated =
                                candidates.stream()
                                        .anyMatch(m -> m.isAnnotationPresent(Moderate.class));
                        if (moderated
                                && candidates.stream()
                                        .anyMatch(m -> !m.isAnnotationPresent(Moderate.class))) {
                            throw control.currentSession().refuse("ambiguous_moderation_method");
                        }
                        enabled.set(moderated);
                    }
                });
        // 原生编排仍验证 Moderate 的同步接口约束；真实审核在下方有会话的请求边界执行。
        context.moderationModel =
                new ModerationModel() {
                    @Override
                    public dev.langchain4j.model.output.Response<Moderation> moderate(
                            List<ChatMessage> messages) {
                        return dev.langchain4j.model.output.Response.from(Moderation.notFlagged());
                    }
                };
        if (context.streamingChatModel != null) {
            var streaming = context.streamingChatModel;
            context.streamingChatModel =
                    new dev.langchain4j.model.chat.StreamingChatModel() {
                        public void chat(
                                ChatRequest request,
                                dev.langchain4j.model.chat.response.StreamingChatResponseHandler
                                        handler) {
                            chat(request, ChatRequestOptions.EMPTY, handler);
                        }

                        public void doChat(
                                ChatRequest request,
                                dev.langchain4j.model.chat.response.StreamingChatResponseHandler
                                        handler) {
                            chat(request, handler);
                        }

                        public void chat(
                                ChatRequest request,
                                ChatRequestOptions options,
                                dev.langchain4j.model.chat.response.StreamingChatResponseHandler
                                        handler) {
                            if (Boolean.TRUE.equals(enabled.get())) {
                                enabled.remove();
                                check(control, delegate, request.messages());
                            }
                            streaming.chat(request, options, handler);
                        }

                        public dev.langchain4j.model.chat.request.ChatRequestParameters
                                defaultRequestParameters() {
                            return streaming.defaultRequestParameters();
                        }

                        public java.util.Set<dev.langchain4j.model.chat.Capability>
                                supportedCapabilities() {
                            return streaming.supportedCapabilities();
                        }

                        public dev.langchain4j.model.ModelProvider provider() {
                            return streaming.provider();
                        }
                    };
        }
        if (guarded == null) {
            return null;
        }
        return new ChatModel() {
            public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
                if (Boolean.TRUE.equals(enabled.get())) {
                    enabled.remove();
                    check(control, delegate, request.messages());
                }
                return guarded.chat(request, options);
            }

            public ChatResponse chat(ChatRequest request) {
                return chat(request, ChatRequestOptions.EMPTY);
            }

            public ChatResponse doChat(ChatRequest request) {
                return chat(request);
            }

            public CompletableFuture<ChatResponse> chatAsync(
                    ChatRequest request, ChatRequestOptions options) {
                return guarded.chatAsync(request, options);
            }

            public dev.langchain4j.model.chat.request.ChatRequestParameters
                    defaultRequestParameters() {
                return guarded.defaultRequestParameters();
            }

            public java.util.Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
                return guarded.supportedCapabilities();
            }

            public dev.langchain4j.model.ModelProvider provider() {
                return guarded.provider();
            }
        };
    }

    private static void check(
            AgentControl control, ModerationModel model, List<ChatMessage> messages) {
        var session = control.currentSession();
        List<String> texts = AiServicesText.messages(messages);
        var response =
                session.operation(
                        false,
                        null,
                        texts,
                        value -> {
                            var input =
                                    TextProjection.list(value).stream()
                                            .map(TextProjection::requiredString)
                                            .toList();
                            var result =
                                    model.moderate(
                                            ModerationRequest.builder()
                                                    .texts(input)
                                                    .modelName(model.modelName())
                                                    .build());
                            session.recordTokens(null);
                            return result;
                        },
                        ModerationGate::project,
                        (original, effective) -> {
                            var before = project(original);
                            var after = TextProjection.object(effective);
                            TextProjection.sameKeys(before, after);
                            TextProjection.fixed(before, after, "flagged");
                            return original.toBuilder()
                                    .moderation(
                                            original.moderation().flagged()
                                                    ? Moderation.flagged(
                                                            TextProjection.requiredString(
                                                                    after.get("text")))
                                                    : Moderation.notFlagged())
                                    .metadata(TextProjection.nullableObject(after.get("metadata")))
                                    .build();
                        });
        if (response.moderation().flagged()) {
            throw session.refuse("moderation_flagged");
        }
    }

    private static Map<String, Object> project(ModerationResponse response) {
        var result = new LinkedHashMap<String, Object>();
        result.put("flagged", response.moderation().flagged());
        result.put("text", response.moderation().flaggedText());
        result.put("metadata", response.metadata());
        return TextProjection.object(io.agt.core.JsonValues.freeze(result));
    }

    /**
     * 与原生审核相同的消息选择。
     *
     * <p>工具结果不进入原生审核输入，媒体语义仍取决于实际审核模型能力。
     */
    private static final class AiServicesText {
        static List<String> messages(List<ChatMessage> messages) {
            return dev.langchain4j.service.AiServices.removeToolMessages(messages).stream()
                    .map(ModerationModel::toText)
                    .toList();
        }
    }
}
