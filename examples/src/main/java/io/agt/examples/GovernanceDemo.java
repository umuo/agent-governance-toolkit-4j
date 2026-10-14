package io.agt.examples;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.AgentControl;
import io.agt.core.BudgetLimits;
import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.JsonValues;
import io.agt.core.TrustedContext;
import io.agt.core.approval.ApprovalResponse;
import io.agt.core.audit.AuditRecord;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.langchain4j.LangChain4jGovernance;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线演示真实的 LangChain4j 工具调用循环。
 *
 * <p>无需外部 API Key，演示包含允许、阻断、内容转换、人工审批以及预算超限等治理场景。
 */
public final class GovernanceDemo {
    public interface Assistant {
        String chat(String text);
    }

    public static final class Tools {
        private final AtomicInteger effects = new AtomicInteger();

        @Tool("查询当前租户资料")
        public String lookup(String tenant, String query) {
            effects.incrementAndGet();
            return "secret:" + query;
        }
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("command")) {
            CommandGovernanceDemo.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("data")) {
            DataGovernanceDemo.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("stream")) {
            try {
                StreamingGovernanceDemo.main(Arrays.copyOfRange(args, 1, args.length));
                return;
            } catch (Exception e) {
                throw new IllegalStateException("Streaming demo failed", e);
            }
        }
        if (args.length > 0 && args[0].equals("async")) {
            try {
                AsyncGovernanceDemo.main(Arrays.copyOfRange(args, 1, args.length));
                return;
            } catch (Exception e) {
                throw new IllegalStateException("Async demo failed", e);
            }
        }
        if (args.length > 0 && args[0].equals("benchmark")) {
            GovernanceBenchmark.run();
            return;
        }
        String requested = args.length == 0 ? "all" : args[0];
        var scenarios =
                List.of("allowed", "denied", "transform", "approval", "approval-denied", "budget");
        if (!requested.equals("all") && !scenarios.contains(requested)) {
            throw new IllegalArgumentException(
                    "Use all, allowed, denied, transform, approval, approval-denied, budget or benchmark");
        }
        for (String scenario : requested.equals("all") ? scenarios : List.of(requested)) {
            run(scenario, args.length > 1 ? Path.of(args[1]) : null);
        }
    }

    private static void run(String scenario, Path policyPath) {
        var tools = new Tools();
        var modelCalls = new AtomicInteger();
        var records = new ArrayList<AuditRecord>();
        var params =
                ParameterRule.object(
                        Map.of(
                                "tenant",
                                ParameterRule.string("tenantId"),
                                "query",
                                ParameterRule.string(null)),
                        "tenant",
                        "query");
        var builder =
                StructuredPolicy.builder("demo-v1")
                        .tool(
                                "lookup",
                                new ToolRule(
                                        Set.of("read"), params, scenario.startsWith("approval")));
        if (scenario.equals("transform")) {
            builder.content(
                    new ContentRule(
                            "rewrite-query",
                            Set.of(InterventionPoint.PRE_TOOL_CALL),
                            List.of(),
                            Map.of("original", "modified")));
            builder.content(
                    new ContentRule(
                            "redact-result",
                            Set.of(InterventionPoint.POST_TOOL_CALL),
                            List.of(),
                            Map.of("secret", "masked")));
        }
        StructuredPolicy policy =
                policyPath == null ? builder.build() : StructuredPolicy.fromPath(policyPath);
        var runtime =
                AgentControl.builder(policy)
                        .audit(records::add)
                        .approvalResolver(
                                r ->
                                        CompletableFuture.completedFuture(
                                                scenario.equals("approval-denied")
                                                        ? ApprovalResponse.deny(r)
                                                        : ApprovalResponse.allow(r)));
        if (scenario.equals("budget")) {
            runtime.budget(new BudgetLimits(20, 0, 300_000));
        }
        ChatModel model =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        modelCalls.incrementAndGet();
                        var last = request.messages().get(request.messages().size() - 1);
                        if (last instanceof ToolExecutionResultMessage result) {
                            return ChatResponse.builder()
                                    .aiMessage(AiMessage.from(result.text()))
                                    .build();
                        }
                        var call =
                                ToolExecutionRequest.builder()
                                        .id("demo-call")
                                        .name("lookup")
                                        .arguments(
                                                JsonValues.write(
                                                        Map.of(
                                                                "tenant",
                                                                scenario.equals("denied")
                                                                        ? "tenant-other"
                                                                        : "tenant-demo",
                                                                "query",
                                                                "original")))
                                        .build();
                        return ChatResponse.builder().aiMessage(AiMessage.from(call)).build();
                    }
                };
        String result = null;
        String error = null;
        try (var control = runtime.build();
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "demo-agent",
                                        "tenant-demo",
                                        "demo-user",
                                        Set.of("read")))) {
            var assistant =
                    new LangChain4jGovernance(control)
                            .aiServices(Assistant.class)
                            .chatModel(model)
                            .tools(tools)
                            .build();
            try {
                result = assistant.chat("查询资料");
            } catch (GovernanceException e) {
                error = session.failureCode() == null ? e.code() : session.failureCode();
            }
        } catch (GovernanceException e) {
            error = e.code();
        }
        boolean shouldDeny = Set.of("denied", "approval-denied", "budget").contains(scenario);
        if ((error != null) != shouldDeny || tools.effects.get() != (shouldDeny ? 0 : 1)) {
            throw new IllegalStateException("Demo acceptance failed: " + scenario);
        }
        if (scenario.equals("transform") && !"masked:modified".equals(result)) {
            throw new IllegalStateException("Transform acceptance failed");
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("scenario", scenario);
        summary.put("blocked", error != null);
        summary.put("toolEffects", tools.effects.get());
        summary.put("modelCalls", modelCalls.get());
        summary.put("result", result);
        summary.put("errorCode", error);
        summary.put("auditRecords", records.size());
        System.out.println(JsonValues.write(summary));
    }
}
