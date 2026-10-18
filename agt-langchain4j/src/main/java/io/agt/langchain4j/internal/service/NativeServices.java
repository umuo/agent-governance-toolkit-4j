package io.agt.langchain4j.internal.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.service.AiServiceContext;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.service.tool.ToolService;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.langchain4j.AiServiceOptions;
import io.agt.langchain4j.internal.memory.MemoryAdapters;
import io.agt.langchain4j.internal.model.ModelAdapters;
import io.agt.langchain4j.internal.rag.RetrievalAdapters;
import io.agt.langchain4j.internal.streaming.StreamSignals;
import io.agt.langchain4j.internal.streaming.StreamingAdapters;
import io.agt.langchain4j.internal.tool.NativeToolErrors;
import io.agt.langchain4j.internal.tool.ToolAdapters;
import io.agt.langchain4j.internal.tool.ToolCallbacks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 复用原生 Builder、记忆缓存和工具注册的受控装配入口。
 *
 * <p>固定针对已验证的 LangChain4j 版本，不注册全局工厂，也不访问框架私有字段。
 */
public final class NativeServices {
    private NativeServices() {}

    public static <T> T build(
            AgentControl control,
            Class<T> type,
            Consumer<AiServices<T>> configure,
            AiServiceOptions options) {
        var builder = new Configuration<T>(control, type, options);
        configure.accept(builder);
        return builder.finish();
    }

    /**
     * 原生记忆上下文。
     *
     * <p>在初始化位置包装记忆，保留框架的缓存与驱逐行为。
     */
    private static final class Context extends AiServiceContext {
        private final AgentControl control;

        Context(AgentControl control, Class<?> type) {
            super(type);
            this.control = control;
            var tools = new Tools(control);
            toolService = tools;
            eventListenerRegistrar.register(
                    new AiServiceListener<
                            dev.langchain4j.observability.api.event.AiServiceCompletedEvent>() {
                        public Class<
                                        dev.langchain4j.observability.api.event
                                                .AiServiceCompletedEvent>
                                getEventClass() {
                            return dev.langchain4j.observability.api.event.AiServiceCompletedEvent
                                    .class;
                        }

                        public void onEvent(
                                dev.langchain4j.observability.api.event.AiServiceCompletedEvent
                                        event) {
                            tools.compensation.clear(event.invocationContext());
                        }
                    });
            eventListenerRegistrar.register(
                    new AiServiceListener<
                            dev.langchain4j.observability.api.event.AiServiceErrorEvent>() {
                        public Class<dev.langchain4j.observability.api.event.AiServiceErrorEvent>
                                getEventClass() {
                            return dev.langchain4j.observability.api.event.AiServiceErrorEvent
                                    .class;
                        }

                        public void onEvent(
                                dev.langchain4j.observability.api.event.AiServiceErrorEvent event) {
                            tools.compensation.clear(event.invocationContext());
                        }
                    });
        }

        @Override
        public void initChatMemories(ChatMemory memory) {
            super.initChatMemories(MemoryAdapters.context(control, memory));
        }

        @Override
        public void initChatMemories(ChatMemoryProvider provider) {
            super.initChatMemories(id -> MemoryAdapters.context(control, provider.get(id)));
        }
    }

    /**
     * 原生配置入口。
     *
     * <p>继承框架配置方法，只限制尚未治理的旁路。
     */
    private static final class Configuration<T> extends AiServices<T> {
        private final AgentControl control;
        private final AiServiceOptions options;
        private final Map<AiServiceListener<?>, AiServiceListener<?>> listeners =
                new java.util.IdentityHashMap<>();

        Configuration(AgentControl control, Class<T> type, AiServiceOptions options) {
            super(new Context(control, type));
            this.control = control;
            this.options = options;
        }

        @Override
        public AiServices<T> executeToolsConcurrently() {
            // 默认并发继续受运行时的有限工作池约束，不创建框架的额外默认执行器。
            return super.executeToolsConcurrently(control.asyncExecutor());
        }

        @Override
        public AiServices<T> executeToolsConcurrently(Executor executor) {
            return super.executeToolsConcurrently(Objects.requireNonNull(executor));
        }

        @Override
        public AiServices<T> tools(Object... objects) {
            return tools(Arrays.asList(objects));
        }

        @Override
        public AiServices<T> tools(Collection<Object> objects) {
            return super.tools(objects);
        }

        @Override
        public <I extends AiServiceEvent> AiServices<T> registerListener(
                AiServiceListener<I> listener) {
            Objects.requireNonNull(listener);
            AiServiceListener<I> wrapped =
                    new AiServiceListener<>() {
                        public Class<I> getEventClass() {
                            return listener.getEventClass();
                        }

                        @SuppressWarnings("unchecked")
                        public void onEvent(I event) {
                            if (event
                                    instanceof
                                    dev.langchain4j.observability.api.event.AiServiceErrorEvent
                                                    error) {
                                // 终止后不再披露请求正文，只交付稳定错误及去掉业务载荷的调用元数据。
                                var context =
                                        dev.langchain4j.invocation.InvocationContext.builder()
                                                .invocationId(
                                                        error.invocationContext().invocationId())
                                                .interfaceName(
                                                        error.invocationContext().interfaceName())
                                                .methodName(error.invocationContext().methodName())
                                                .timestamp(error.invocationContext().timestamp())
                                                .methodArguments(List.of())
                                                .userMessage(null)
                                                .chatMemoryId(null)
                                                .invocationParameters(
                                                        new dev.langchain4j.invocation
                                                                .InvocationParameters())
                                                .managedParameters(Map.of())
                                                .build();
                                String code =
                                        error.error() instanceof GovernanceException g
                                                ? g.code()
                                                : "agent_execution_failed";
                                listener.onEvent(
                                        (I)
                                                error.toBuilder()
                                                        .invocationContext(context)
                                                        .error(new GovernanceException(code))
                                                        .build());
                                return;
                            }
                            var session = control.currentSession();
                            try {
                                listener.onEvent((I) CallbackViews.event(session, event));
                            } catch (RuntimeException error) {
                                throw session.fail(
                                        io.agt.core.InterventionPoint.OUTPUT,
                                        error instanceof GovernanceException g
                                                ? g.code()
                                                : "callback_failed");
                            }
                        }
                    };
            var previous = listeners.putIfAbsent(listener, wrapped);
            return super.registerListener(previous == null ? wrapped : castListener(previous));
        }

        @SuppressWarnings("unchecked")
        private static <I extends AiServiceEvent> AiServiceListener<I> castListener(
                AiServiceListener<?> listener) {
            return (AiServiceListener<I>) listener;
        }

        @Override
        public AiServices<T> registerListeners(AiServiceListener<?>... values) {
            return registerListeners(Arrays.asList(values));
        }

        @Override
        public AiServices<T> registerListeners(Collection<? extends AiServiceListener<?>> values) {
            values.forEach(this::registerListener);
            return this;
        }

        @Override
        public <I extends AiServiceEvent> AiServices<T> unregisterListener(
                AiServiceListener<I> listener) {
            var wrapped = listeners.remove(listener);
            if (wrapped != null) {
                super.unregisterListener(castListener(wrapped));
            }
            return this;
        }

        @Override
        public AiServices<T> unregisterListeners(AiServiceListener<?>... values) {
            return unregisterListeners(Arrays.asList(values));
        }

        @Override
        public AiServices<T> unregisterListeners(
                Collection<? extends AiServiceListener<?>> values) {
            values.forEach(this::unregisterListener);
            return this;
        }

        @Override
        public T build() {
            // 禁止在配置结束、模型装饰和完整校验之前取得半装配服务。
            throw new GovernanceException("native_builder_build_managed");
        }

        T finish() {
            ((Tools) context.toolService).validateCompensation();
            boolean streaming =
                    Arrays.stream(context.aiServiceClass.getMethods())
                            .filter(m -> !m.isDefault())
                            .anyMatch(
                                    m ->
                                            m.getReturnType() == TokenStream.class
                                                    || PublisherTypes.isStreaming(
                                                            m.getGenericReturnType()));
            if (streaming && context.streamingChatModel == null) {
                throw new GovernanceException("missing_streaming_model");
            }
            boolean needsChatModel =
                    Arrays.stream(context.aiServiceClass.getMethods())
                            .filter(m -> !m.isDefault() && m.getDeclaringClass() != Object.class)
                            .anyMatch(
                                    m ->
                                            m.getReturnType() != TokenStream.class
                                                    && !PublisherTypes.isStreaming(
                                                            m.getGenericReturnType()));
            if (needsChatModel && context.chatModel == null) {
                throw new GovernanceException("missing_model");
            }
            if (context.chatModel == null && context.streamingChatModel == null) {
                throw new GovernanceException("missing_model");
            }
            if (context.streamingChatModel != null) {
                context.streamingChatModel =
                        StreamingAdapters.guard(
                                control,
                                context.streamingChatModel,
                                options.streamingLimits(),
                                options.delivery(),
                                options.modelUsageMeter(),
                                options.modelTerminationContract());
            }
            if (context.chatModel != null) {
                context.chatModel =
                        ModelAdapters.guard(
                                control,
                                context.chatModel,
                                options.modelUsageMeter(),
                                options.modelTerminationContract());
            }
            if (context.moderationModel != null) {
                context.chatModel =
                        io.agt.langchain4j.internal.model.ModerationGate.install(
                                control, context, context.chatModel);
            }
            Executor requested = context.toolService.executor();
            Executor parallel =
                    requested == null
                            ? control.asyncExecutor()
                            : control.contextualExecutor(requested);
            context.toolService.executeToolsConcurrently(
                    task -> {
                        if (requested != null || control.currentSession().isAsyncRun()) {
                            parallel.execute(task);
                        } else {
                            task.run();
                        }
                    });
            if (context.retrievalAugmentor != null) {
                context.retrievalAugmentor =
                        RetrievalAdapters.context(control, context.retrievalAugmentor);
            }
            return AiServices.<T>builder(context).build();
        }
    }

    /**
     * 受控工具注册表。
     *
     * <p>在框架注册点统一包装静态及动态工具，保留原生工具名称和返回行为。
     */
    private static final class Tools extends ToolService implements ToolCallbacks {
        private final AgentControl control;
        private final CompensationRegistry compensation;

        private Consumer<BeforeToolExecution> before;
        private Consumer<ToolExecution> after;

        Tools(AgentControl control) {
            this.control = control;
            this.compensation = new CompensationRegistry(control);
            super.onCompensableToolExecution(compensation::record);
        }

        @Override
        public void tools(Collection<Object> objects) {
            var prior = new HashSet<>(toolExecutors().keySet());
            super.tools(objects);
            toolExecutors()
                    .replaceAll(
                            (name, executor) ->
                                    prior.contains(name) ? executor : guard(name, executor));
        }

        @Override
        public void tools(List<AiServiceTool> tools) {
            super.tools(wrap(tools));
        }

        @Override
        public void tools(Map<ToolSpecification, ToolExecutor> tools) {
            var guarded = new LinkedHashMap<ToolSpecification, ToolExecutor>();
            tools.forEach(
                    (spec, executor) ->
                            guarded.put(
                                    ToolAdapters.reviewedSpecification(control, spec),
                                    guard(spec.name(), executor)));
            super.tools(guarded);
        }

        @Override
        public void toolProvider(ToolProvider provider) {
            if (provider == null) {
                return;
            }
            super.toolProvider(
                    new ToolProvider() {
                        @Override
                        public boolean isDynamic() {
                            try {
                                return provider.isDynamic();
                            } catch (RuntimeException error) {
                                throw control.currentSession()
                                        .refuse(
                                                error instanceof GovernanceException g
                                                        ? g.code()
                                                        : "tool_provider_failed");
                            }
                        }

                        @Override
                        public ToolProviderResult provideTools(ToolProviderRequest request) {
                            var session = control.currentSession();
                            try {
                                var result = provider.provideTools(request);
                                return result == null
                                        ? null
                                        : new ToolProviderResult(wrap(result.aiServiceTools()));
                            } catch (RuntimeException error) {
                                throw session.refuse(
                                        error instanceof GovernanceException g
                                                ? g.code()
                                                : "tool_provider_failed");
                            }
                        }
                    });
        }

        @Override
        public void toolProviders(Collection<ToolProvider> providers) {
            if (providers != null) {
                providers.forEach(this::toolProvider);
            }
        }

        private List<AiServiceTool> wrap(List<AiServiceTool> tools) {
            Set<String> names = new HashSet<>();
            var guarded = new ArrayList<AiServiceTool>();
            for (var tool : tools) {
                if (!names.add(tool.name())) {
                    throw new GovernanceException("duplicate_tool");
                }
                guarded.add(
                        tool.toBuilder()
                                .toolSpecification(
                                        ToolAdapters.reviewedSpecification(
                                                control, tool.toolSpecification()))
                                .toolExecutor(guard(tool.name(), tool.toolExecutor()))
                                .build());
            }
            return guarded;
        }

        private ToolExecutor guard(String name, ToolExecutor executor) {
            return ToolAdapters.guard(
                    control, name, NativeToolErrors.wrap(executor, this, control), true, this);
        }

        @Override
        public void rawResult(
                io.agt.core.HostSession session,
                dev.langchain4j.agent.tool.ToolExecutionRequest request,
                dev.langchain4j.invocation.InvocationContext context,
                dev.langchain4j.service.tool.ToolExecutionResult result) {
            compensation.receipt(session, request, context, result);
        }

        @Override
        public ToolResultsOutcome processToolResults(
                AiServiceContext context,
                List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests,
                Map<
                                dev.langchain4j.agent.tool.ToolExecutionRequest,
                                dev.langchain4j.service.tool.ToolExecutionResult>
                        results,
                List<ToolExecution> executions,
                dev.langchain4j.invocation.InvocationContext invocation,
                dev.langchain4j.service.tool.ToolServiceContext tools) {
            var outcome =
                    super.processToolResults(
                            context, requests, results, executions, invocation, tools);
            if (outcome.anyToolErrored())
                compensation.compensate(invocation, context.eventListenerRegistrar);
            return new ToolResultsOutcome(
                    outcome.anyToolErrored(),
                    outcome.returnBehaviors(),
                    compensation.rewrite(invocation, outcome.resultMessages()));
        }

        @Override
        public java.util.concurrent.CompletionStage<List<dev.langchain4j.data.message.ChatMessage>>
                persistToolResultsAndResolveMessages(
                        AiServiceContext context,
                        dev.langchain4j.memory.ChatMemory memory,
                        List<dev.langchain4j.data.message.ChatMessage> messages,
                        List<dev.langchain4j.data.message.ToolExecutionResultMessage> results,
                        dev.langchain4j.invocation.InvocationContext invocation) {
            compensation.rewriteHistory(invocation, memory, messages);
            return super.persistToolResultsAndResolveMessages(
                    context, memory, messages, results, invocation);
        }

        @Override
        public List<dev.langchain4j.data.message.ChatMessage>
                persistToolResultsAndResolveMessagesSync(
                        AiServiceContext context,
                        dev.langchain4j.memory.ChatMemory memory,
                        List<dev.langchain4j.data.message.ChatMessage> messages,
                        List<dev.langchain4j.data.message.ToolExecutionResultMessage> results,
                        dev.langchain4j.invocation.InvocationContext invocation) {
            compensation.rewriteHistory(invocation, memory, messages);
            return super.persistToolResultsAndResolveMessagesSync(
                    context, memory, messages, results, invocation);
        }

        @Override
        public void beforeToolExecution(Consumer<BeforeToolExecution> callback) {
            before = Objects.requireNonNull(callback);
        }

        @Override
        public void afterToolExecution(Consumer<ToolExecution> callback) {
            after = Objects.requireNonNull(callback);
        }

        @Override
        public void before(
                io.agt.core.HostSession session,
                dev.langchain4j.agent.tool.ToolExecutionRequest request,
                dev.langchain4j.invocation.InvocationContext context) {
            var event =
                    BeforeToolExecution.builder()
                            .request(request)
                            .invocationContext(context)
                            .build();
            if (before != null) {
                before.accept(
                        (BeforeToolExecution)
                                CallbackViews.checked(session, event, BeforeToolExecution.class));
            }
            StreamSignals.emit(session, "before", event);
        }

        @Override
        public void after(
                io.agt.core.HostSession session,
                dev.langchain4j.agent.tool.ToolExecutionRequest request,
                dev.langchain4j.invocation.InvocationContext context,
                dev.langchain4j.service.tool.ToolExecutionResult result) {
            var event =
                    ToolExecution.builder()
                            .request(request)
                            .result(result)
                            .invocationContext(context)
                            .build();
            if (after != null) {
                after.accept(
                        (ToolExecution) CallbackViews.checked(session, event, ToolExecution.class));
            }
            StreamSignals.emit(session, "after", event);
        }

        void validateCompensation() {
            super.compensateOnToolErrors(compensation.enabled());
            super.compensateOnToolErrors(false);
        }

        @Override
        public void compensateOnToolErrors(boolean enabled) {
            compensation.enabled(enabled);
            super.compensateOnToolErrors(false);
        }
    }
}
