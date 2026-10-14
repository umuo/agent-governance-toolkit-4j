package io.agt.examples.asi02;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.internal.Json;
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
 * 推荐接入入口：受控 AI Services 完成模型、工具、模型的完整循环。 当前 DEMO 主要测试调用工具前修改参数，调用工具结果后修改执行结果；
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
                        System.out.println(Json.toJson(request));
                        calls.incrementAndGet();
                        var last = request.messages().get(request.messages().size() - 1);
                        if (last instanceof ToolExecutionResultMessage result) {
                            // 获取最后一条工具执行结果的消息，如果和指定字符串不匹配则抛出异常，否则返回AiMessage Response
                            // 第二次模型调用必须收到脱敏后的工具文本。
                            if (!"masked:modified".equals(result.text())) {
                                throw new IllegalStateException("原始结果进入模型");
                            }
                            return ChatResponse.builder()
                                    .aiMessage(AiMessage.from(result.text()))
                                    .build();
                        }

                        // 这里是返回一个带有工具调用 request AiMessage Response
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
        // 工具的策略 StructuredPolicy
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
        /*
         * 使用建造者模式初始化治理运行时配置，
         * policy：传入定义好的策略评估器实例
         * 将策略规则绑定为该运行时的全局判定引擎，后续所有会话的鉴权、限额、工具拦截、改写都基于该 policy 进行。
         * .audit(r -> points.add(r.point()))
         * 强制审计约束（No Audit, No Run）： 框架在设计上有严格的安全保障——AgentControl 强制要求必须提供一个 AuditSink
         * （若为 null 会直接抛出 NullPointerException("An audit sink is required")），杜绝没有任何审计留痕的裸奔运行。
         *
         * 入参 r 是框架生成的结构化审计日志对象 AuditRecord。
         * r.point() 获取本次治理动作触发的生命周期拦截点，保存到points
         * */
        // 创建 AgentControl；points.add
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
                /*
                 * 这里返回的结果：masked:modified
                 * ToolRequest mock 的 {"query": "original", "tenant": "tenant-a"}
                 * tool call before -> 调用工具前会把 original 替换为 modified
                 * 调用工具后返回：secret:modified
                 * tool call after -> 会把 secret 替换为 masked
                 * 最终就会返回：masked:modified
                 * */
                // 这里开始执行 Agent，在上面代码中mock，保证返回的一定是工具的执行结果
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
