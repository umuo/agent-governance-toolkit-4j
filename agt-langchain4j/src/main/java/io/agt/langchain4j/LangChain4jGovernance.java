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
import io.agt.core.data.DataResource;
import io.agt.core.execution.FixedCommand;
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
import java.util.function.Function;

/**
 * LangChain4j 治理门面与构建器。
 *
 * <p>提供受治理的模型、流式模型、记忆、检索器及工具适配，保证对 LangChain4j 组件的全面治理管控。
 */
public final class LangChain4jGovernance {
    private final AgentControl control;

    public LangChain4jGovernance(AgentControl control) {
        this.control = Objects.requireNonNull(control);
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
        control.requirePoints(
                Set.of(InterventionPoint.PRE_MODEL_CALL, InterventionPoint.POST_MODEL_CALL));
        if (!model.listeners().isEmpty()) {
            throw new GovernanceException("raw_model_listeners_unsupported");
        }
        if (model instanceof GovernedChatModel) {
            throw new GovernanceException("already_governed");
        }
        return new GovernedChatModel(control, Objects.requireNonNull(model));
    }

    public StreamingChatModel guardStreamingModel(StreamingChatModel model) {
        return guardStreamingModel(model, StreamingLimits.DEFAULT);
    }

    public StreamingChatModel guardStreamingModel(
            StreamingChatModel model, StreamingLimits limits) {
        Objects.requireNonNull(model);
        control.requirePoints(
                Set.of(InterventionPoint.PRE_MODEL_CALL, InterventionPoint.POST_MODEL_CALL));
        if (!model.listeners().isEmpty()) {
            throw new GovernanceException("raw_model_listeners_unsupported");
        }
        if (model instanceof GovernedStreamingChatModel) {
            throw new GovernanceException("already_governed");
        }
        return new GovernedStreamingChatModel(control, model, Objects.requireNonNull(limits));
    }

    public static BufferedTokenStream buffered(TokenStream stream) {
        if (!(stream instanceof BufferedTokenStream buffered)) {
            throw new GovernanceException("ungoverned_stream");
        }
        return buffered;
    }

    private void requireData(DataResource resource, DataResource.Kind kind) {
        control.requirePoints(
                Set.of(InterventionPoint.PRE_DATA_ACCESS, InterventionPoint.POST_DATA_ACCESS));
        if (resource.kind() != kind) {
            throw new GovernanceException("data_resource_kind_mismatch");
        }
    }

    public ContentRetriever guardRetriever(DataResource resource, ContentRetriever retriever) {
        requireData(Objects.requireNonNull(resource), DataResource.Kind.RETRIEVAL);
        Objects.requireNonNull(retriever);
        if (retriever instanceof GovernedContentRetriever) {
            throw new GovernanceException("already_governed");
        }
        if (retriever
                .getClass()
                .getName()
                .equals("dev.langchain4j.rag.content.retriever.ListeningContentRetriever")) {
            throw new GovernanceException("data_listener_unsupported");
        }
        return new GovernedContentRetriever(control, resource, retriever);
    }

    public ChatMemory guardMemory(DataResource resource, ChatMemory memory) {
        requireData(Objects.requireNonNull(resource), DataResource.Kind.MEMORY);
        if (memory instanceof GovernedChatMemory) {
            throw new GovernanceException("already_governed");
        }
        return new GovernedChatMemory(control, resource, Objects.requireNonNull(memory));
    }

    public ToolExecutor guardTool(String name, ToolExecutor executor) {
        control.requirePoints(
                Set.of(InterventionPoint.PRE_TOOL_CALL, InterventionPoint.POST_TOOL_CALL));
        if (executor instanceof GovernedToolExecutor) {
            throw new GovernanceException("already_governed");
        }
        return new GovernedToolExecutor(control, name, Objects.requireNonNull(executor));
    }

    /** 返回受模型/工具策略治理的固定命令；不能提供任意脚本执行能力。 */
    public ToolExecutor guardCommand(FixedCommand command) {
        Objects.requireNonNull(command);
        return guardTool(command.name(), new CommandToolExecutor(control, command));
    }

    public <T> Builder<T> aiServices(Class<T> type) {
        return new Builder<>(this, type);
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
                    new SingleSourceAugmentor(
                            governance.control, governance.guardRetriever(resource, retriever));
            return this;
        }

        public Builder<T> chatMemory(ChatMemory memory) {
            if (governedMemoryProvider || this.memory instanceof GovernedChatMemory) {
                throw new GovernanceException("data_component_conflict");
            }
            this.memory = memory;
            return this;
        }

        public Builder<T> chatMemoryProvider(ChatMemoryProvider value) {
            if (governedMemoryProvider || memory instanceof GovernedChatMemory) {
                throw new GovernanceException("data_component_conflict");
            }
            memoryProvider = value;
            return this;
        }

        public Builder<T> retrievalAugmentor(RetrievalAugmentor augmentor) {
            if (this.augmentor instanceof SingleSourceAugmentor) {
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
                            new CommandToolExecutor(governance.control, command)));
        }

        public Builder<T> tools(List<AiServiceTool> values) {
            tools.addAll(values);
            return this;
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
            if (!type.isInterface()) {
                throw new GovernanceException("ai_service_interface_required");
            }
            for (Method method : type.getMethods()) {
                validateMethod(method);
            }
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
                builder.chatMemory(new ContextChatMemory(governance.control, memory));
            }
            if (memoryProvider != null) {
                builder.chatMemoryProvider(
                        id -> new ContextChatMemory(governance.control, memoryProvider.get(id)));
            }
            if (augmentor != null) {
                builder.retrievalAugmentor(
                        new ContextRetrievalAugmentor(governance.control, augmentor));
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
            T delegate = builder.build();
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
                                boolean async = isAsync(method);
                                try {
                                    HostSession session = governance.control.currentSession();
                                    Object[] original = args == null ? new Object[0] : args.clone();
                                    Map<String, Object> input = new LinkedHashMap<>();
                                    for (int i = 0; i < original.length; i++) {
                                        if (!trustedParameter(method.getParameters()[i])) {
                                            input.put(Integer.toString(i), original[i]);
                                        }
                                    }
                                    Function<Object, Object> invoke =
                                            effective -> {
                                                var values = TextProjection.object(effective);
                                                TextProjection.sameKeys(input, values);
                                                Object[] actual = original.clone();
                                                for (int i = 0; i < actual.length; i++) {
                                                    if (!trustedParameter(
                                                            method.getParameters()[i])) {
                                                        actual[i] =
                                                                TextProjection.requiredString(
                                                                        values.get(
                                                                                Integer.toString(
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
                                                    result instanceof AiMessage m
                                                            ? TextProjection.message(m)
                                                            : result;
                                    BiFunction<Object, Object, Object> replace =
                                            (result, effective) ->
                                                    result instanceof AiMessage m
                                                            ? TextProjection.replace(m, effective)
                                                            : TextProjection.nullableString(
                                                                    effective);
                                    if (isStreaming(method)) {
                                        return new BufferedTokenStream(
                                                session,
                                                () ->
                                                        session.runAsync(
                                                                input,
                                                                effective -> {
                                                                    Object value =
                                                                            invoke.apply(effective);
                                                                    if (!(value
                                                                            instanceof
                                                                            TokenStream
                                                                                            nativeStream)) {
                                                                        throw new GovernanceException(
                                                                                "missing_stream_result");
                                                                    }
                                                                    var result =
                                                                            new CompletableFuture<
                                                                                    ChatResponse>();
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
                                                                                .build()));
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
