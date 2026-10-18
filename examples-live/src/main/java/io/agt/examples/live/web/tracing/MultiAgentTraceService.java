package io.agt.examples.live.web.tracing;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import io.agt.core.*;
import io.agt.core.delegation.DelegationScope;
import io.agt.core.policy.*;
import io.agt.observability.AgentTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 固定父级调度 + 两个并行真实 LLM 子 Agent，演示身份、执行与拒绝的关联。 */
@Service
public final class MultiAgentTraceService {
    public interface Assistant {
        String chat(String text);
    }

    private final TraceStore store;
    private final SdkTracerProvider provider;
    private final AgentTracer tracer;
    private final ObjectProvider<ChatModel> models;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private final ExecutorService children = Executors.newFixedThreadPool(2);
    private final AtomicBoolean busy = new AtomicBoolean();

    public MultiAgentTraceService(
            ObjectProvider<ChatModel> models,
            @Value("${agt.examples.traces-directory:.cache/agent-traces}") String directory)
            throws java.io.IOException {
        if (directory == null || directory.isBlank())
            throw new IllegalArgumentException("Trace directory is required");
        this.models = models;
        store = new TraceStore(Path.of(directory));
        provider =
                SdkTracerProvider.builder()
                        .setSampler(Sampler.alwaysOn())
                        .addSpanProcessor(store)
                        .build();
        var telemetry =
                OpenTelemetrySdk.builder()
                        .setTracerProvider(provider)
                        .setPropagators(
                                ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                        .build();
        tracer = new AgentTracer(telemetry);
    }

    public TraceStore store() {
        return store;
    }

    public String start() {
        if (!busy.compareAndSet(false, true))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "trace_demo_busy");
        var accepted = new CompletableFuture<String>();
        boolean submitted = false;
        try {
            try {
                store.requireCapacity();
            } catch (IllegalStateException error) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        error.getMessage().equals("trace_store_full")
                                ? "trace_store_full"
                                : "trace_store_unavailable");
            }
            ChatModel model;
            try {
                model = models.getObject();
            } catch (org.springframework.beans.BeansException error) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "trace_model_unavailable");
            }
            coordinator.submit(
                    () -> {
                        String[] traceId = {null};
                        String outcome = "completed";
                        try {
                            captureAgent(
                                    "coordinator-agent",
                                    "并行读取合成事实，并核验外部投递请求。",
                                    () -> {
                                        try (var control =
                                                        AgentControl.builder(tracePolicy())
                                                                .audit(
                                                                        tracer.correlate(
                                                                                store::audit))
                                                                .build();
                                                var parent =
                                                        control.openTaskSession(
                                                                new TrustedContext(
                                                                        "coordinator-agent",
                                                                        "tenant-demo",
                                                                        "demo-user",
                                                                        Set.of("read", "send")),
                                                                new TaskLimits(
                                                                        new BudgetLimits(
                                                                                8, 5, 120000),
                                                                        10,
                                                                        3,
                                                                        1),
                                                                tracer.currentExecution(),
                                                                new DelegationScope(
                                                                        "trace-demo",
                                                                        true,
                                                                        traceCapabilities(),
                                                                        Map.of()));
                                                var binding = parent.bind()) {
                                            traceId[0] = tracer.currentExecution().traceId();
                                            accepted.complete(traceId[0]);
                                            Future<String> research =
                                                    tracer.operation(
                                                            "delegate",
                                                            "research",
                                                            () ->
                                                                    children.submit(
                                                                            tracer.wrap(
                                                                                    (Callable<
                                                                                                    String>)
                                                                                            () ->
                                                                                                    child(
                                                                                                            "research-agent",
                                                                                                            false,
                                                                                                            model,
                                                                                                            parent))));
                                            Future<String> review =
                                                    tracer.operation(
                                                            "delegate",
                                                            "review",
                                                            () ->
                                                                    children.submit(
                                                                            tracer.wrap(
                                                                                    (Callable<
                                                                                                    String>)
                                                                                            () ->
                                                                                                    child(
                                                                                                            "review-agent",
                                                                                                            true,
                                                                                                            model,
                                                                                                            parent))));
                                            // 即使一个子任务失败，也等待另一个完成，根链不会先于子任务结束。
                                            String first = await(research);
                                            String second = await(review);
                                            if (first.equals("error") || second.equals("error"))
                                                throw new IllegalStateException("child_failed");
                                            store.capture(
                                                    tracer.currentExecution(),
                                                    "task_budget",
                                                    parent.taskSnapshot());
                                            return "子任务已汇总";
                                        }
                                    });
                        } catch (RuntimeException error) {
                            outcome = "error";
                            accepted.completeExceptionally(
                                    new IllegalStateException("trace_start_failed"));
                        } finally {
                            try {
                                if (traceId[0] != null) store.finish(traceId[0], outcome);
                            } finally {
                                busy.set(false);
                            }
                        }
                    });
            submitted = true;
            return accepted.get(5, TimeUnit.SECONDS);
        } catch (Exception error) {
            if (!submitted) busy.set(false);
            if (error instanceof ResponseStatusException failure) throw failure;
            throw new ResponseStatusException(
                    error instanceof TimeoutException
                            ? HttpStatus.GATEWAY_TIMEOUT
                            : HttpStatus.SERVICE_UNAVAILABLE,
                    error instanceof TimeoutException
                            ? "trace_start_timeout"
                            : "trace_start_failed");
        }
    }

    private String await(Future<String> future) {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "error";
        } catch (ExecutionException error) {
            return AgentTracer.isDenied(error.getCause()) ? "blocked" : "error";
        }
    }

    private String captureAgent(
            String agentId, String input, java.util.function.Supplier<String> action) {
        return tracer.invokeAgent(
                agentId,
                () -> {
                    var execution = tracer.currentExecution();
                    store.capture(execution, "agent_request", Map.of("message", input));
                    try {
                        String answer = action.get();
                        store.capture(
                                execution,
                                "agent_response",
                                Map.of("status", "completed", "answer", answer));
                        return answer;
                    } catch (RuntimeException error) {
                        store.capture(execution, "agent_response", failure(error));
                        throw error;
                    }
                });
    }

    private static Map<String, Object> failure(RuntimeException error) {
        return Map.of(
                "status",
                AgentTracer.isDenied(error) ? "blocked" : "error",
                "code",
                error instanceof GovernanceException g ? g.code() : "execution_failed");
    }

    private ChatResponse captureModel(ChatModel model, ChatRequest request) {
        var execution = tracer.currentExecution();
        var exchange = new io.agt.examples.live.web.experiment.ExperimentTrace();
        store.capture(execution, "model_request", request);
        try (var scope = exchange.bind()) {
            ChatResponse response;
            try {
                response = model.chat(request);
            } catch (RuntimeException error) {
                for (var step : exchange.snapshot())
                    store.capture(execution, step.kind(), step.body());
                store.capture(execution, "model_error", failure(error));
                throw error;
            }
            for (var step : exchange.snapshot()) store.capture(execution, step.kind(), step.body());
            store.capture(execution, "model_response", response);
            return response;
        }
    }

    private String child(String agentId, boolean review, ChatModel model, HostSession parent) {
        String taskInput =
                review
                        ? "请调用 deliverDigest，destination 为 external-demo，完成本轮合成投递。"
                        : "请调用 lookupFacts 读取本轮合成事实，并概括结果。";
        return captureAgent(
                agentId,
                taskInput,
                () -> {
                    var identity =
                            new TrustedContext(
                                    agentId,
                                    "tenant-demo",
                                    "demo-user",
                                    review ? Set.of("send") : Set.of("read"));
                    var control = parent.control();
                    return tracer.governedDelegated(
                            parent,
                            identity,
                            new DelegationScope(
                                    "trace-demo",
                                    true,
                                    Map.of(
                                            review ? "deliverDigest" : "lookupFacts",
                                            traceCapabilities()
                                                    .get(review ? "deliverDigest" : "lookupFacts")),
                                    Map.of()),
                            session -> {
                                store.capture(
                                        tracer.currentExecution(),
                                        "agent_authority",
                                        Map.of(
                                                "taskId", session.taskSnapshot().taskId(),
                                                "parentSessionId", parent.sessionId(),
                                                "delegationDepth", session.delegationDepth(),
                                                "permissions", session.context().permissions(),
                                                "taskName", session.delegationScope().taskName(),
                                                "toolScopes", session.delegationScope().tools()));
                                var tools = new DemoTools(control, tracer, store);
                                ChatModel governedModel =
                                        new io.agt.langchain4j.LangChain4jGovernance(control)
                                                .guardModel(model);
                                ChatModel observed =
                                        new ChatModel() {
                                            @Override
                                            public ChatResponse doChat(ChatRequest request) {
                                                return tracer.operation(
                                                        "model",
                                                        "chat",
                                                        () -> captureModel(governedModel, request));
                                            }
                                        };
                                var assistant =
                                        AiServices.builder(Assistant.class)
                                                .chatModel(observed)
                                                .tools(tools)
                                                .systemMessageProvider(
                                                        ignored ->
                                                                "你在本机可重置教学环境工作。工具只读取合成资料、写本轮内存计数，不连接真实外部系统。按用户要求实际调用指定工具，然后如实汇报；工具拒绝时停止，不编造成功。")
                                                .maxSequentialToolsInvocations(5)
                                                .toolExecutionErrorHandler(
                                                        (error, context) -> {
                                                            if (error
                                                                    instanceof
                                                                    RuntimeException failure)
                                                                throw failure;
                                                            throw new GovernanceException(
                                                                    "tool_failed");
                                                        })
                                                .build();
                                return control.run(taskInput, assistant::chat);
                            });
                });
    }

    private StructuredPolicy tracePolicy() {
        return StructuredPolicy.builder("trace-demo-v1")
                .budget(new BudgetLimits(8, 5, 120000))
                .tool(
                        "lookupFacts",
                        new ToolRule(Set.of("read"), ParameterRule.object(Map.of()), false))
                .tool(
                        "deliverDigest",
                        new ToolRule(
                                Set.of("send"),
                                ParameterRule.object(
                                        Map.of(
                                                "destination",
                                                new ParameterRule(
                                                        ParameterRule.Type.STRING,
                                                        null,
                                                        null,
                                                        java.util.List.of("internal-demo"),
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null)),
                                        "destination"),
                                false))
                .build();
    }

    private Map<String, ParameterRule> traceCapabilities() {
        return Map.of(
                "lookupFacts",
                ParameterRule.object(Map.of()),
                "deliverDigest",
                ParameterRule.object(
                        Map.of(
                                "destination",
                                new ParameterRule(
                                        ParameterRule.Type.STRING,
                                        null,
                                        null,
                                        java.util.List.of("internal-demo"),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)),
                        "destination"));
    }

    public static final class DemoTools {
        private final AgentControl control;
        private final AgentTracer tracer;
        private final TraceStore store;

        DemoTools(AgentControl control, AgentTracer tracer, TraceStore store) {
            this.control = control;
            this.tracer = tracer;
            this.store = store;
        }

        private String invoke(
                String name,
                Map<String, Object> arguments,
                java.util.function.Function<Map<String, Object>, String> action) {
            return tracer.operation(
                    "tool",
                    name,
                    () -> {
                        var execution = tracer.currentExecution();
                        store.capture(
                                execution,
                                "tool_request",
                                Map.of("name", name, "arguments", arguments));
                        var entered = new AtomicBoolean();
                        try {
                            String result =
                                    control.runTool(
                                            name,
                                            arguments,
                                            effective -> {
                                                entered.set(true);
                                                store.capture(
                                                        execution,
                                                        "tool_effective_arguments",
                                                        effective);
                                                return action.apply(effective);
                                            });
                            store.capture(
                                    execution,
                                    "tool_response",
                                    Map.of(
                                            "status",
                                            "completed",
                                            "executed",
                                            entered.get(),
                                            "result",
                                            result));
                            return result;
                        } catch (RuntimeException error) {
                            var outcome =
                                    new java.util.LinkedHashMap<String, Object>(failure(error));
                            outcome.put("executed", entered.get());
                            store.capture(execution, "tool_response", outcome);
                            throw error;
                        }
                    });
        }

        @Tool("读取合成业务事实")
        public String lookupFacts() {
            return invoke(
                    "lookupFacts",
                    Map.of(),
                    args -> {
                        store.effect(tracer.currentExecution().traceId(), "factReads");
                        return "本期营收增长 5%，新增客户 12 家。";
                    });
        }

        @Tool("向 internal-demo 或 external-demo 写入本轮合成投递计数")
        public String deliverDigest(String destination) {
            return invoke(
                    "deliverDigest",
                    Map.of("destination", destination),
                    args -> {
                        store.effect(tracer.currentExecution().traceId(), "deliveries");
                        return "已写入内存计数";
                    });
        }
    }

    @PreDestroy
    public void close() {
        coordinator.shutdownNow();
        children.shutdownNow();
        provider.close();
    }
}
