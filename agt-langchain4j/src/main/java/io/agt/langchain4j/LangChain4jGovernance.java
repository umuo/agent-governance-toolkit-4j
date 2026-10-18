package io.agt.langchain4j;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.InterventionPoint;
import io.agt.core.TrustedContext;
import io.agt.core.data.DataResource;
import io.agt.core.execution.FixedCommand;
import io.agt.langchain4j.internal.memory.MemoryAdapters;
import io.agt.langchain4j.internal.message.TextProjection;
import io.agt.langchain4j.internal.model.ModelAdapters;
import io.agt.langchain4j.internal.rag.RetrievalAdapters;
import io.agt.langchain4j.internal.service.NativeServices;
import io.agt.langchain4j.internal.service.ServiceMethods;
import io.agt.langchain4j.internal.service.TypedValues;
import io.agt.langchain4j.internal.streaming.StreamingAdapters;
import io.agt.langchain4j.internal.tool.ToolAdapters;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * LangChain4j 治理门面与构建器。
 *
 * <p>提供受治理的模型、流式模型、记忆、检索器及工具适配，治理范围由实际组装的受控组件及配置决定。
 */
public final class LangChain4jGovernance {
    private final AgentControl control;
    private final AiServiceOptions options;

    public LangChain4jGovernance(AgentControl control) {
        this(control, AiServiceOptions.DEFAULT);
    }

    /** 新原生入口显式选择交付契约；旧 Builder/guardStreamingModel 保留严格语义。 */
    public LangChain4jGovernance(AgentControl control, StreamDelivery delivery) {
        this(control, new AiServiceOptions(delivery, StreamingLimits.DEFAULT, 32));
    }

    /** 指定此门面创建的原生服务的默认配置。 */
    public LangChain4jGovernance(AgentControl control, AiServiceOptions options) {
        this.control = Objects.requireNonNull(control);
        this.options = Objects.requireNonNull(options);
        requireVersion(ChatModel.class, "langchain4j-core");
        requireVersion(AiServices.class, "langchain4j");
    }

    private static void requireVersion(Class<?> type, String artifact) {
        try (var stream =
                type.getResourceAsStream(
                        "/META-INF/maven/dev.langchain4j/" + artifact + "/pom.properties")) {
            var properties = new Properties();
            if (stream == null) {
                throw new IllegalArgumentException();
            }
            properties.load(stream);
            if (!"1.20.0".equals(properties.getProperty("version"))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new GovernanceException("unsupported_langchain4j_version");
        }
    }

    public ChatModel guardModel(ChatModel model) {
        return ModelAdapters.guard(
                control, model, options.modelUsageMeter(), options.modelTerminationContract());
    }

    public StreamingChatModel guardStreamingModel(StreamingChatModel model) {
        return guardStreamingModel(model, StreamingLimits.DEFAULT);
    }

    public StreamingChatModel guardStreamingModel(
            StreamingChatModel model, StreamingLimits limits) {
        return StreamingAdapters.guard(
                control,
                model,
                limits,
                StreamDelivery.STRICT,
                options.modelUsageMeter(),
                options.modelTerminationContract());
    }

    public static BufferedTokenStream buffered(TokenStream stream) {
        if (!(stream instanceof BufferedTokenStream buffered)) {
            throw new GovernanceException("ungoverned_stream");
        }
        return buffered;
    }

    public ContentRetriever guardRetriever(DataResource resource, ContentRetriever retriever) {
        return RetrievalAdapters.guard(control, resource, retriever);
    }

    public ContentRetriever guardRetriever(
            DataResource resource, ContentRetriever retriever, DocumentResolver documents) {
        return RetrievalAdapters.guard(
                control, resource, retriever, Objects.requireNonNull(documents));
    }

    public ChatMemory guardMemory(DataResource resource, ChatMemory memory) {
        return MemoryAdapters.guard(control, resource, memory);
    }

    public ToolExecutor guardTool(String name, ToolExecutor executor) {
        return ToolAdapters.guard(control, name, executor);
    }

    public ToolExecutor guardEffectTool(
            String name,
            io.agt.core.effects.EffectLedger ledger,
            java.util.function.Function<HostSession, io.agt.core.effects.EffectIntent> intent,
            io.agt.core.effects.EffectBackend backend) {
        return guardTool(name, ToolAdapters.effectRegistration(name, ledger, intent, backend));
    }

    /** 返回受模型/工具策略治理的固定命令；不能提供任意脚本执行能力。 */
    public ToolExecutor guardCommand(FixedCommand command) {
        return ToolAdapters.command(control, command);
    }

    public <T> Builder<T> aiServices(Class<T> type) {
        return new Builder<>(this, type);
    }

    /**
     * 使用原生 Builder 配置受治理的服务，调用方显式管理会话。
     *
     * @param type AI Service 接口
     * @param configure 原生配置回调；由本方法完成最终构建
     * @param <T> 服务类型
     * @return 受治理服务
     */
    public <T> T aiServices(Class<T> type, Consumer<AiServices<T>> configure) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(configure);
        control.requirePoints(InterventionPoint.lifecyclePoints());
        if (!type.isInterface()) {
            throw new GovernanceException("ai_service_interface_required");
        }
        for (Method method : type.getMethods()) {
            TypedValues.validate(method.getGenericReturnType());
            for (Type parameter : method.getGenericParameterTypes()) {
                TypedValues.validate(parameter);
            }
        }
        return new Builder<>(this, type)
                .wrap(NativeServices.build(control, type, configure, options), true);
    }

    /** 使用服务专属配置，调用方显式管理会话。 */
    public <T> T aiServices(
            Class<T> type, AiServiceOptions options, Consumer<AiServices<T>> configure) {
        return new LangChain4jGovernance(control, options).aiServices(type, configure);
    }

    /** 使用服务专属配置并自动管理会话。 */
    public <T> T create(
            Class<T> type,
            Supplier<TrustedContext> identity,
            AiServiceOptions options,
            Consumer<AiServices<T>> configure) {
        return new LangChain4jGovernance(control, options).create(type, identity, configure);
    }

    /**
     * 使用原生配置并自动管理顶层调用会话。
     *
     * <p>身份必须来自宿主认证上下文。已有会话时复用其身份和预算，不重复解析身份或关闭宿主会话。
     *
     * @param type AI Service 接口
     * @param identity 每次顶层调用取得可信身份
     * @param configure 原生配置回调
     * @param <T> 服务类型
     * @return 可直接调用的受治理服务
     */
    public <T> T create(
            Class<T> type, Supplier<TrustedContext> identity, Consumer<AiServices<T>> configure) {
        Objects.requireNonNull(identity);
        T service = aiServices(type, configure);
        var methods = ServiceMethods.inspect(type);
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (self, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "toString" ->
                                            "ManagedGovernedAiService["
                                                    + type.getSimpleName()
                                                    + "]";
                                    case "hashCode" -> System.identityHashCode(self);
                                    case "equals" -> self == args[0];
                                    default -> throw new GovernanceException("method_unsupported");
                                };
                            }
                            try {
                                return ManagedSession.invoke(
                                        control,
                                        identity,
                                        () -> {
                                            try {
                                                method.trySetAccessible();
                                                return method.invoke(service, args);
                                            } catch (InvocationTargetException e) {
                                                if (e.getCause()
                                                        instanceof RuntimeException runtime) {
                                                    throw runtime;
                                                }
                                                throw new GovernanceException("ai_service_failed");
                                            } catch (IllegalAccessException e) {
                                                throw new GovernanceException("ai_service_failed");
                                            }
                                        });
                            } catch (RuntimeException error) {
                                if (methods.get(method).publisher().publisher()) {
                                    java.util.concurrent.Flow.Publisher<Object> failed =
                                            subscriber -> {
                                                subscriber.onSubscribe(
                                                        new java.util.concurrent.Flow
                                                                .Subscription() {
                                                            public void request(long n) {}

                                                            public void cancel() {}
                                                        });
                                                subscriber.onError(error);
                                            };
                                    return methods.get(method).publisher().adapt(failed);
                                }
                                if (Builder.isAsync(method)) {
                                    return CompletableFuture.failedFuture(error);
                                }
                                throw error;
                            }
                        }));
    }

    /**
     * 受治理的 AiServices 构建器。
     *
     * <p>配置模型、流式模型、记忆、检索管道及工具，自动注入治理拦截代理。
     */
    public static final class Builder<T> {
        private final LangChain4jGovernance governance;
        private final Class<T> type;
        private ChatModel model;
        private StreamingChatModel streamingModel;
        private StreamingLimits streamingLimits = StreamingLimits.DEFAULT;
        private final List<AiServiceTool> tools = new ArrayList<>();
        private ToolProvider provider;
        private ChatMemory memory;
        private ChatMemoryProvider memoryProvider;
        private RetrievalAugmentor augmentor;
        private ToolExecutionErrorHandler errorHandler;
        private String systemMessage;
        private boolean concurrentTools;
        private boolean governedMemoryProvider;
        private boolean asyncAnnotatedTools;
        private Executor toolExecutor;

        private Builder(LangChain4jGovernance governance, Class<T> type) {
            this.governance = governance;
            this.type = type;
        }

        public Builder<T> chatModel(ChatModel model) {
            this.model = model;
            return this;
        }

        public Builder<T> streamingChatModel(StreamingChatModel model) {
            streamingModel = Objects.requireNonNull(model);
            return this;
        }

        public Builder<T> streamingLimits(StreamingLimits limits) {
            streamingLimits = Objects.requireNonNull(limits);
            return this;
        }

        public Builder<T> systemMessage(String text) {
            systemMessage = text;
            return this;
        }

        public Builder<T> chatMemory(DataResource resource, ChatMemory memory) {
            if (this.memory != null || memoryProvider != null) {
                throw new GovernanceException("data_component_conflict");
            }
            this.memory = governance.guardMemory(resource, memory);
            return this;
        }

        public Builder<T> governedChatMemoryProvider(Map<String, MemoryBinding> bindings) {
            if (memory != null || memoryProvider != null) {
                throw new GovernanceException("data_component_conflict");
            }
            governedMemoryProvider = true;
            var registered = Map.copyOf(bindings);
            var wrapped = new LinkedHashMap<String, ChatMemory>();
            registered.forEach(
                    (id, binding) -> {
                        if (!id.equals(binding.resource().instanceId())) {
                            throw new GovernanceException("memory_binding_mismatch");
                        }
                        wrapped.put(
                                id, governance.guardMemory(binding.resource(), binding.memory()));
                    });
            memoryProvider =
                    id -> {
                        if (!(id instanceof String key) || !wrapped.containsKey(key)) {
                            throw governance
                                    .control
                                    .currentSession()
                                    .fail(
                                            InterventionPoint.PRE_DATA_ACCESS,
                                            "memory_not_registered");
                        }
                        return wrapped.get(key);
                    };
            return this;
        }

        public Builder<T> contentRetriever(DataResource resource, ContentRetriever retriever) {
            if (augmentor != null) {
                throw new GovernanceException("data_component_conflict");
            }
            augmentor =
                    RetrievalAdapters.singleSource(
                            governance.control, governance.guardRetriever(resource, retriever));
            return this;
        }

        public Builder<T> contentRetriever(
                DataResource resource, ContentRetriever retriever, DocumentResolver documents) {
            if (augmentor != null) throw new GovernanceException("data_component_conflict");
            augmentor =
                    RetrievalAdapters.singleSource(
                            governance.control,
                            governance.guardRetriever(resource, retriever, documents));
            return this;
        }

        public Builder<T> chatMemory(ChatMemory memory) {
            if (governedMemoryProvider || MemoryAdapters.isGoverned(this.memory)) {
                throw new GovernanceException("data_component_conflict");
            }
            this.memory = memory;
            return this;
        }

        public Builder<T> chatMemoryProvider(ChatMemoryProvider value) {
            if (governedMemoryProvider || MemoryAdapters.isGoverned(memory)) {
                throw new GovernanceException("data_component_conflict");
            }
            memoryProvider = value;
            return this;
        }

        public Builder<T> retrievalAugmentor(RetrievalAugmentor augmentor) {
            if (RetrievalAdapters.isSingleSource(this.augmentor)) {
                throw new GovernanceException("data_component_conflict");
            }
            this.augmentor = augmentor;
            return this;
        }

        public Builder<T> tools(Map<ToolSpecification, ToolExecutor> values) {
            values.forEach(
                    (spec, executor) ->
                            tools.add(
                                    AiServiceTool.builder()
                                            .toolSpecification(spec)
                                            .toolExecutor(executor)
                                            .build()));
            return this;
        }

        /** 从固定模板生成工具 schema，仍必须在策略中显式许可该名称及权限。 */
        public Builder<T> commandTool(FixedCommand command) {
            Objects.requireNonNull(command);
            var schema = JsonObjectSchema.builder();
            command.choices().forEach(schema::addEnumProperty);
            schema.required(new ArrayList<>(command.choices().keySet()));
            schema.additionalProperties(false);
            return tools(
                    Map.of(
                            ToolSpecification.builder()
                                    .name(command.name())
                                    .description("执行宿主注册的固定命令，参数只允许预定义选项")
                                    .parameters(schema.build())
                                    .build(),
                            ToolAdapters.registration(command)));
        }

        public Builder<T> tools(List<AiServiceTool> values) {
            tools.addAll(values);
            return this;
        }

        public Builder<T> effectTool(
                ToolSpecification specification,
                io.agt.core.effects.EffectLedger ledger,
                java.util.function.Function<HostSession, io.agt.core.effects.EffectIntent> intent,
                io.agt.core.effects.EffectBackend backend) {
            return tools(
                    Map.of(
                            specification,
                            ToolAdapters.effectRegistration(
                                    specification.name(), ledger, intent, backend)));
        }

        public Builder<T> tools(Object... objects) {
            for (Object object : objects) {
                for (Class<?> c = object.getClass();
                        c != null && c != Object.class;
                        c = c.getSuperclass()) {
                    for (Method method : c.getDeclaredMethods()) {
                        Tool annotation = method.getAnnotation(Tool.class);
                        if (annotation == null) {
                            continue;
                        }
                        asyncAnnotatedTools |= isAsync(method);
                        if (!textReturn(method, true)) {
                            throw new GovernanceException("complex_tool_result_unsupported");
                        }
                        tools.add(
                                AiServiceTool.builder()
                                        .toolSpecification(
                                                ToolSpecifications.toolSpecificationFrom(method))
                                        .toolExecutor(new DefaultToolExecutor(object, method))
                                        .returnBehavior(annotation.returnBehavior())
                                        .build());
                    }
                }
            }
            return this;
        }

        public Builder<T> toolProvider(ToolProvider provider) {
            this.provider = provider;
            return this;
        }

        public Builder<T> toolExecutionErrorHandler(ToolExecutionErrorHandler handler) {
            errorHandler = handler;
            return this;
        }

        public Builder<T> executeToolsConcurrently() {
            concurrentTools = true;
            return this;
        }

        public Builder<T> executeToolsConcurrently(Executor executor) {
            concurrentTools = true;
            toolExecutor = Objects.requireNonNull(executor);
            return this;
        }

        public T build() {
            governance.control.requirePoints(InterventionPoint.lifecyclePoints());
            validateType(type);
            boolean streaming = Arrays.stream(type.getMethods()).anyMatch(Builder::isStreaming);
            boolean nonStreaming = Arrays.stream(type.getMethods()).anyMatch(m -> !isStreaming(m));
            if (streaming
                    && (nonStreaming || concurrentTools || asyncAnnotatedTools || model != null)) {
                throw new GovernanceException("stream_service_configuration_unsupported");
            }
            if (streaming && streamingModel == null) {
                throw new GovernanceException("missing_streaming_model");
            }
            var builder = AiServices.builder(type);
            if (streaming) {
                builder.streamingChatModel(
                        governance.guardStreamingModel(
                                Objects.requireNonNull(streamingModel), streamingLimits));
            } else {
                if (streamingModel != null) {
                    throw new GovernanceException("stream_service_configuration_unsupported");
                }
                builder.chatModel(governance.guardModel(Objects.requireNonNull(model)));
            }
            if (systemMessage != null) {
                builder.systemMessageProvider(memoryId -> systemMessage);
            }
            if (memory != null) {
                builder.chatMemory(MemoryAdapters.context(governance.control, memory));
            }
            if (memoryProvider != null) {
                builder.chatMemoryProvider(
                        id -> MemoryAdapters.context(governance.control, memoryProvider.get(id)));
            }
            if (augmentor != null) {
                builder.retrievalAugmentor(
                        RetrievalAdapters.context(governance.control, augmentor));
            }
            if (errorHandler != null) {
                builder.toolExecutionErrorHandler(errorHandler);
            }
            if (!tools.isEmpty()) {
                builder.tools(wrap(tools));
            }
            if (provider != null) {
                builder.toolProvider(
                        request -> {
                            HostSession session = governance.control.currentSession();
                            try {
                                ToolProviderResult result = provider.provideTools(request);
                                if (result == null) {
                                    return null;
                                }
                                return new ToolProviderResult(wrap(result.aiServiceTools()));
                            } catch (RuntimeException e) {
                                throw session.refuse(
                                        e instanceof GovernanceException g
                                                ? g.code()
                                                : "tool_provider_failed");
                            }
                        });
            }
            Executor parallel =
                    toolExecutor == null
                            ? governance.control.asyncExecutor()
                            : governance.control.contextualExecutor(toolExecutor);
            if (!streaming) {
                builder.executeToolsConcurrently(
                        task -> {
                            HostSession session = governance.control.currentSession();
                            if (concurrentTools || session.isAsyncRun()) {
                                parallel.execute(task);
                            } else {
                                task.run(); // 保留同步默认顺序，异步工具仍交给上下文执行器。
                            }
                        });
            }
            return wrap(builder.build());
        }

        private T wrap(T delegate) {
            return wrap(delegate, false);
        }

        private T wrap(T delegate, boolean typed) {
            var methods =
                    typed ? ServiceMethods.inspect(type) : Map.<Method, ServiceMethods.Shape>of();
            Object proxy =
                    Proxy.newProxyInstance(
                            type.getClassLoader(),
                            new Class<?>[] {type},
                            (self, method, args) -> {
                                if (method.getDeclaringClass() == Object.class) {
                                    return switch (method.getName()) {
                                        case "toString" ->
                                                "GovernedAiService[" + type.getSimpleName() + "]";
                                        case "hashCode" -> System.identityHashCode(self);
                                        case "equals" -> self == args[0];
                                        default ->
                                                throw new GovernanceException("method_unsupported");
                                    };
                                }
                                var shape = methods.get(method);
                                boolean async = isAsync(method);
                                try {
                                    HostSession session = governance.control.currentSession();
                                    Object[] original = args == null ? new Object[0] : args.clone();
                                    Map<String, Object> input = new LinkedHashMap<>();
                                    for (int i = 0; i < original.length; i++) {
                                        if (!(typed
                                                ? shape.trusted().get(i)
                                                : trustedParameter(method.getParameters()[i]))) {
                                            input.put(
                                                    Integer.toString(i),
                                                    typed
                                                            ? TypedValues.project(original[i])
                                                            : original[i]);
                                        }
                                    }
                                    Function<Object, Object> invoke =
                                            effective -> {
                                                var values = TextProjection.object(effective);
                                                TextProjection.sameKeys(input, values);
                                                Object[] actual = original.clone();
                                                for (int i = 0; i < actual.length; i++) {
                                                    if (!(typed
                                                            ? shape.trusted().get(i)
                                                            : trustedParameter(
                                                                    method.getParameters()[i]))) {
                                                        actual[i] =
                                                                typed
                                                                        ? TypedValues.restore(
                                                                                original[i],
                                                                                values.get(
                                                                                        Integer
                                                                                                .toString(
                                                                                                        i)),
                                                                                shape.parameters()
                                                                                        .get(i))
                                                                        : TextProjection
                                                                                .requiredString(
                                                                                        values.get(
                                                                                                Integer
                                                                                                        .toString(
                                                                                                                i)));
                                                    }
                                                }
                                                try {
                                                    method.trySetAccessible();
                                                    return method.invoke(delegate, actual);
                                                } catch (InvocationTargetException e) {
                                                    if (e.getCause()
                                                            instanceof RuntimeException r) {
                                                        throw r;
                                                    }
                                                    throw new GovernanceException(
                                                            "ai_service_failed");
                                                } catch (IllegalAccessException e) {
                                                    throw new GovernanceException(
                                                            "ai_service_failed");
                                                }
                                            };
                                    Function<Object, Object> project =
                                            result ->
                                                    typed
                                                            ? TypedValues.project(result)
                                                            : result instanceof AiMessage m
                                                                    ? TextProjection.message(m)
                                                                    : result;
                                    BiFunction<Object, Object, Object> replace =
                                            (result, effective) ->
                                                    typed
                                                            ? TypedValues.restore(
                                                                    result,
                                                                    effective,
                                                                    TypedValues.resultType(
                                                                            typed
                                                                                    ? shape
                                                                                            .resultType()
                                                                                    : method
                                                                                            .getGenericReturnType()))
                                                            : result instanceof AiMessage m
                                                                    ? TextProjection.replace(
                                                                            m, effective)
                                                                    : TextProjection.nullableString(
                                                                            effective);
                                    if (typed && shape.publisher().publisher()) {
                                        var publisher =
                                                new GovernedPublisher(
                                                        session,
                                                        input,
                                                        invoke,
                                                        TypedValues.argument(shape.resultType(), 0),
                                                        shape.publisher().streaming()
                                                                ? governance.options.delivery()
                                                                : StreamDelivery.STRICT,
                                                        !shape.publisher().streaming(),
                                                        governance.options.streamingLimits(),
                                                        governance.options.publisherPrefetch());
                                        Object exposed = shape.publisher().adapt(publisher);
                                        if (ManagedSession.defer(session, exposed)) {
                                            publisher.manageSession();
                                        }
                                        return exposed;
                                    }
                                    if (isStreaming(method)) {
                                        return new BufferedTokenStream(
                                                session,
                                                () ->
                                                        session.runAsync(
                                                                input,
                                                                effective -> {
                                                                    Object streamValue =
                                                                            invoke.apply(effective);
                                                                    if (!(streamValue
                                                                            instanceof
                                                                            TokenStream
                                                                                            nativeStream)) {
                                                                        throw new GovernanceException(
                                                                                "missing_stream_result");
                                                                    }
                                                                    var result =
                                                                            new CompletableFuture<
                                                                                    ChatResponse>();
                                                                    if (typed) {
                                                                        nativeStream
                                                                                .onPartialResponse(
                                                                                        text ->
                                                                                                io
                                                                                                        .agt
                                                                                                        .langchain4j
                                                                                                        .internal
                                                                                                        .streaming
                                                                                                        .StreamSignals
                                                                                                        .emit(
                                                                                                                session,
                                                                                                                "text",
                                                                                                                text))
                                                                                .onPartialThinking(
                                                                                        value ->
                                                                                                io
                                                                                                        .agt
                                                                                                        .langchain4j
                                                                                                        .internal
                                                                                                        .streaming
                                                                                                        .StreamSignals
                                                                                                        .emit(
                                                                                                                session,
                                                                                                                "thinking",
                                                                                                                value))
                                                                                .onPartialToolCall(
                                                                                        value ->
                                                                                                io
                                                                                                        .agt
                                                                                                        .langchain4j
                                                                                                        .internal
                                                                                                        .streaming
                                                                                                        .StreamSignals
                                                                                                        .emit(
                                                                                                                session,
                                                                                                                "tool",
                                                                                                                value))
                                                                                .onIntermediateResponse(
                                                                                        value ->
                                                                                                io
                                                                                                        .agt
                                                                                                        .langchain4j
                                                                                                        .internal
                                                                                                        .streaming
                                                                                                        .StreamSignals
                                                                                                        .emit(
                                                                                                                session,
                                                                                                                "intermediate",
                                                                                                                value))
                                                                                .onRetrieved(
                                                                                        value ->
                                                                                                io
                                                                                                        .agt
                                                                                                        .langchain4j
                                                                                                        .internal
                                                                                                        .streaming
                                                                                                        .StreamSignals
                                                                                                        .emit(
                                                                                                                session,
                                                                                                                "retrieved",
                                                                                                                value))
                                                                                .onUnmappedRawEvent(
                                                                                        value ->
                                                                                                io
                                                                                                        .agt
                                                                                                        .langchain4j
                                                                                                        .internal
                                                                                                        .streaming
                                                                                                        .StreamSignals
                                                                                                        .emit(
                                                                                                                session,
                                                                                                                "raw",
                                                                                                                value));
                                                                    }
                                                                    nativeStream
                                                                            .onCompleteResponse(
                                                                                    result
                                                                                            ::complete)
                                                                            .onError(
                                                                                    result
                                                                                            ::completeExceptionally)
                                                                            .start();
                                                                    return result;
                                                                },
                                                                response ->
                                                                        TextProjection.message(
                                                                                response
                                                                                        .aiMessage()),
                                                                (response, effective) ->
                                                                        response.toBuilder()
                                                                                .aiMessage(
                                                                                        (AiMessage)
                                                                                                TextProjection
                                                                                                        .replace(
                                                                                                                response
                                                                                                                        .aiMessage(),
                                                                                                                effective))
                                                                                .build()),
                                                typed
                                                        ? governance.options.delivery()
                                                        : StreamDelivery.STRICT,
                                                typed);
                                    }
                                    if (async) {
                                        return session.runAsync(
                                                input,
                                                effective -> {
                                                    Object stage = invoke.apply(effective);
                                                    if (!(stage instanceof CompletionStage<?>)) {
                                                        throw new GovernanceException(
                                                                "missing_async_result");
                                                    }
                                                    @SuppressWarnings("unchecked")
                                                    var result = (CompletionStage<Object>) stage;
                                                    return result;
                                                },
                                                project,
                                                replace);
                                    }
                                    return session.run(input, invoke, project, replace);
                                } catch (RuntimeException e) {
                                    if (async) {
                                        return CompletableFuture.failedFuture(e);
                                    }
                                    throw e;
                                }
                            });
            return type.cast(proxy);
        }

        private List<AiServiceTool> wrap(List<AiServiceTool> source) {
            Set<String> names = new HashSet<>();
            List<AiServiceTool> result = new ArrayList<>();
            for (var tool : source) {
                if (!names.add(tool.name())) {
                    throw new GovernanceException("duplicate_tool");
                }
                result.add(
                        tool.toBuilder()
                                .toolSpecification(
                                        ToolAdapters.reviewedSpecification(
                                                governance.control, tool.toolSpecification()))
                                .toolExecutor(
                                        governance.guardTool(tool.name(), tool.toolExecutor()))
                                .build());
            }
            return result;
        }

        private static boolean trustedParameter(Parameter parameter) {
            return parameter.isAnnotationPresent(MemoryId.class)
                    || parameter.getType() == InvocationParameters.class;
        }

        private static boolean isStreaming(Method method) {
            return method.getReturnType() == TokenStream.class;
        }

        private static boolean isAsync(Method method) {
            return method.getReturnType() == CompletableFuture.class
                    || method.getReturnType() == CompletionStage.class;
        }

        private static boolean textReturn(Method method, boolean tool) {
            Type value = method.getGenericReturnType();
            if (isAsync(method)) {
                if (!(value instanceof ParameterizedType generic)) {
                    return false;
                }
                value = generic.getActualTypeArguments()[0];
            }
            return value == String.class
                    || (tool
                            ? (value == void.class || value == Void.class)
                            : value == AiMessage.class);
        }

        private static void validateType(Class<?> type) {
            if (!type.isInterface()) {
                throw new GovernanceException("ai_service_interface_required");
            }
            for (Method method : type.getMethods()) {
                validateMethod(method);
            }
        }

        private static void validateMethod(Method method) {
            if (method.isDefault() || (!isStreaming(method) && !textReturn(method, false))) {
                throw new GovernanceException("ai_service_shape_unsupported");
            }
            for (Parameter p : method.getParameters()) {
                if (!trustedParameter(p) && p.getType() != String.class) {
                    throw new GovernanceException("ai_service_shape_unsupported");
                }
            }
        }
    }
}
