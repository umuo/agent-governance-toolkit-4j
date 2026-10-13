package io.agt.examples;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.AgentControl;
import io.agt.core.BudgetLimits;
import io.agt.core.InterventionPoint;
import io.agt.core.JsonValues;
import io.agt.core.TrustedContext;
import io.agt.core.approval.ApprovalResponse;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.langchain4j.LangChain4jGovernance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线异步治理演示示例。
 *
 * <p>演示异步模型调用、审批流、任务取消、预算监控与并行工具调用的治理能力，无需外部 API Key。
 */
public final class AsyncGovernanceDemo {
    public interface Assistant {
        CompletableFuture<String> chat(String text);
    }

    public interface SyncAssistant {
        String chat(String text);
    }

    public static final class Tools {
        private final AtomicInteger effects = new AtomicInteger();

        @Tool
        public CompletableFuture<String> lookup(String tenant, String query) {
            effects.incrementAndGet();
            return CompletableFuture.completedFuture("secret:" + query);
        }
    }

    public static final class ParallelTools {
        private final AtomicInteger effects = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(2);

        @Tool
        public String lookup(String tenant, String query) {
            effects.incrementAndGet();
            entered.countDown();
            try {
                if (!entered.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Parallel entry did not overlap");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted");
            }
            return "parallel-result";
        }
    }

    public static void main(String[] args) throws Exception {
        var modes =
                List.of(
                        "allowed",
                        "denied",
                        "transform",
                        "approval",
                        "budget",
                        "cancel",
                        "parallel");
        String requested = args.length == 0 ? "all" : args[0];
        if (!requested.equals("all") && !modes.contains(requested)) {
            throw new IllegalArgumentException("Unknown async scenario");
        }
        for (String mode : requested.equals("all") ? modes : List.of(requested)) {
            run(mode);
        }
    }

    private static void run(String mode) throws Exception {
        var params =
                ParameterRule.object(
                        Map.of(
                                "tenant",
                                ParameterRule.string("tenantId"),
                                "query",
                                ParameterRule.string(null)),
                        "tenant",
                        "query");
        var policy =
                StructuredPolicy.builder("demo-v2")
                        .tool(
                                "lookup",
                                new ToolRule(Set.of("read"), params, mode.equals("approval")));
        if (mode.equals("transform")) {
            policy.content(
                    new ContentRule(
                            "rewrite",
                            Set.of(InterventionPoint.PRE_TOOL_CALL),
                            List.of(),
                            Map.of("original", "modified")));
            policy.content(
                    new ContentRule(
                            "redact",
                            Set.of(InterventionPoint.POST_TOOL_CALL),
                            List.of(),
                            Map.of("secret", "masked")));
        }
        var tools = new Tools();
        var parallel = new ParallelTools();
        var calls = new AtomicInteger();
        var source = new CompletableFuture<ChatResponse>();
        var started = new CountDownLatch(1);
        ChatModel model =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest r) {
                        calls.incrementAndGet();
                        if (r.messages().get(r.messages().size() - 1)
                                instanceof ToolExecutionResultMessage m) {
                            return ChatResponse.builder()
                                    .aiMessage(AiMessage.from(m.text()))
                                    .build();
                        }
                        var first =
                                ToolExecutionRequest.builder()
                                        .id("first")
                                        .name("lookup")
                                        .arguments(
                                                JsonValues.write(
                                                        Map.of(
                                                                "tenant",
                                                                mode.equals("denied")
                                                                        ? "foreign"
                                                                        : "demo-tenant",
                                                                "query",
                                                                "original")))
                                        .build();
                        var requests = new ArrayList<ToolExecutionRequest>();
                        requests.add(first);
                        if (mode.equals("parallel")) {
                            requests.add(
                                    ToolExecutionRequest.builder()
                                            .id("second")
                                            .name("lookup")
                                            .arguments(first.arguments())
                                            .build());
                        }
                        return ChatResponse.builder().aiMessage(AiMessage.from(requests)).build();
                    }

                    @Override
                    public CompletableFuture<ChatResponse> chatAsync(
                            ChatRequest r, ChatRequestOptions options) {
                        if (mode.equals("cancel")) {
                            calls.incrementAndGet();
                            started.countDown();
                            return source;
                        }
                        return CompletableFuture.completedFuture(doChat(r));
                    }
                };
        String value = null;
        String error = null;
        var config =
                AgentControl.builder(policy.build())
                        .audit(r -> {})
                        .approvalResolver(
                                r -> CompletableFuture.completedFuture(ApprovalResponse.allow(r)));
        if (mode.equals("budget")) {
            config.budget(new BudgetLimits(20, 0, 10000));
        }
        try (var control = config.build();
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "demo-agent",
                                        "demo-tenant",
                                        "demo-user",
                                        Set.of("read")))) {
            var adapter = new LangChain4jGovernance(control);
            if (mode.equals("parallel")) {
                value =
                        adapter.aiServices(SyncAssistant.class)
                                .chatModel(model)
                                .tools(parallel)
                                .executeToolsConcurrently()
                                .build()
                                .chat("query");
            } else {
                var a = adapter.aiServices(Assistant.class).chatModel(model).tools(tools).build();
                var result = a.chat("query");
                try {
                    if (mode.equals("cancel")) {
                        if (!started.await(2, TimeUnit.SECONDS) || !result.cancel(true)) {
                            throw new IllegalStateException("Cancellation acceptance failed");
                        }
                        try {
                            source.get(2, TimeUnit.SECONDS);
                            throw new IllegalStateException("Source was not cancelled");
                        } catch (CancellationException expected) {
                        }
                        error = session.failureCode();
                    } else {
                        value = result.get(5, TimeUnit.SECONDS);
                    }
                } catch (ExecutionException e) {
                    error = session.failureCode();
                }
            }
        }
        boolean denied = Set.of("denied", "budget", "cancel").contains(mode);
        int effects = mode.equals("parallel") ? parallel.effects.get() : tools.effects.get();
        if ((error != null) != denied
                || effects != (denied ? 0 : mode.equals("parallel") ? 2 : 1)) {
            throw new IllegalStateException("Async demo acceptance failed: " + mode);
        }
        if (mode.equals("transform") && !"masked:modified".equals(value)) {
            throw new IllegalStateException("Async rewrite acceptance failed");
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("scenario", "async-" + mode);
        summary.put("blocked", error != null);
        summary.put("modelCalls", calls.get());
        summary.put("toolEffects", effects);
        summary.put("result", value);
        summary.put("errorCode", error);
        System.out.println(JsonValues.write(summary));
    }
}
