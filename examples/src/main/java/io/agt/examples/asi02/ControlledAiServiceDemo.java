package io.agt.examples.asi02;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.AgentControl;
import io.agt.core.InterventionPoint;
import io.agt.core.policy.ContentRule;
import io.agt.examples.support.ToolScenario;
import io.agt.langchain4j.LangChain4jGovernance;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 推荐接入入口：受控 AI Services 完成模型、工具、模型的完整循环。
 *
 * <p>本机确定性模型只生成固定合成调用；真实 LangChain4j 负责工具调度，无需 API Key。
 */
public final class ControlledAiServiceDemo {
    /** 同步文本 Agent 接口。 */
    public interface Assistant {
        String chat(String input);
    }

    /** 经审核的业务工具；工具体仍需自行满足业务授权与数据访问约束。 */
    public static final class LookupTool {
        private final AtomicInteger effects = new AtomicInteger();

        @Tool("查询当前租户的合成资料")
        public String lookup(String tenant, String query) {
            if (!"tenant-a".equals(tenant) || !"modified".equals(query)) {
                throw new IllegalStateException("有效参数未到达工具");
            }
            effects.incrementAndGet();
            return "secret:" + query;
        }
    }

    public static void main(String[] args) {
        var calls = new AtomicInteger();
        var business = new LookupTool();
        ChatModel model =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        calls.incrementAndGet();
                        var last = request.messages().get(request.messages().size() - 1);
                        if (last instanceof ToolExecutionResultMessage result) {
                            // 第二次模型调用必须收到脱敏后的工具文本。
                            if (!"masked:modified".equals(result.text())) {
                                throw new IllegalStateException("原始结果进入模型");
                            }
                            return ChatResponse.builder()
                                    .aiMessage(AiMessage.from(result.text()))
                                    .build();
                        }
                        return ChatResponse.builder()
                                .aiMessage(
                                        AiMessage.from(
                                                ToolExecutionRequest.builder()
                                                        .id("loop-call")
                                                        .name("lookup")
                                                        .arguments(ToolScenario.VALID)
                                                        .build()))
                                .build();
                    }
                };
        var points = EnumSet.noneOf(InterventionPoint.class);
        var policy =
                ToolScenario.allowed(false)
                        .content(
                                new ContentRule(
                                        "rewrite",
                                        Set.of(InterventionPoint.PRE_TOOL_CALL),
                                        List.of(),
                                        Map.of("original", "modified")))
                        .content(
                                new ContentRule(
                                        "redact",
                                        Set.of(InterventionPoint.POST_TOOL_CALL),
                                        List.of(),
                                        Map.of("secret", "masked")))
                        .build();
        try (var control = AgentControl.builder(policy).audit(r -> points.add(r.point())).build()) {
            try (var session = control.openSession(ToolScenario.identity(Set.of("read")))) {
                // SDK 组装受控模型和工具；只包装已构建的 Assistant 无法覆盖内部动作。
                var assistant =
                        new LangChain4jGovernance(control)
                                .aiServices(Assistant.class)
                                .chatModel(model)
                                .tools(business)
                                .build();
                String answer = assistant.chat("查询当前租户资料");
                if (!"masked:modified".equals(answer)
                        || calls.get() != 2
                        || business.effects.get() != 1) {
                    throw new IllegalStateException("完整闭环验收失败");
                }
                System.out.println("模型调用=2，工具副作用=1，调用方收到 masked:modified。");
            }
            // 会话关闭后再检查 shutdown；不能在会话尚未关闭时断言八类干预点齐全。
            var expected =
                    EnumSet.of(
                            InterventionPoint.AGENT_STARTUP,
                            InterventionPoint.INPUT,
                            InterventionPoint.PRE_MODEL_CALL,
                            InterventionPoint.POST_MODEL_CALL,
                            InterventionPoint.PRE_TOOL_CALL,
                            InterventionPoint.POST_TOOL_CALL,
                            InterventionPoint.OUTPUT,
                            InterventionPoint.AGENT_SHUTDOWN);
            if (!points.containsAll(expected)) {
                throw new IllegalStateException("八类干预点不完整");
            }
            System.out.println("已记录完整八类干预点：" + points);
        }
    }
}
