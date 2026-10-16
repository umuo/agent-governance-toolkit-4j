package io.agt.examples;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.AgentControl;
import io.agt.core.InterventionPoint;
import io.agt.core.TrustedContext;
import io.agt.core.audit.AuditRecord;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.langchain4j.LangChain4jGovernance;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原生 LangChain4j 配置与自动会话的离线迁移示例。
 *
 * <p>保留业务接口和工具，使用本机确定性模型验证调用、结果改写和会话关闭，无需 API Key。
 */
public final class NativeIntegrationDemo {
    private NativeIntegrationDemo() {}

    public interface Assistant {
        String chat(String message);
    }

    /**
     * 原生工具对象。
     *
     * <p>不引用治理会话或策略 API，权限由宿主配置。
     */
    public static final class Tools {
        private final AtomicInteger effects = new AtomicInteger();

        @Tool("查询租户资料")
        public String lookup(String tenant) {
            effects.incrementAndGet();
            return "secret-value";
        }
    }

    public static void main(String[] args) {
        var policy =
                StructuredPolicy.builder("native-demo")
                        .tool(
                                "lookup",
                                new ToolRule(
                                        Set.of("read"),
                                        ParameterRule.object(
                                                Map.of("tenant", ParameterRule.string("tenantId")),
                                                "tenant"),
                                        false))
                        .content(
                                new ContentRule(
                                        "redact",
                                        Set.of(InterventionPoint.POST_TOOL_CALL),
                                        List.of(),
                                        Map.of("secret", "masked")))
                        .build();
        var records = new ArrayList<AuditRecord>();
        var tools = new Tools();
        try (var control = AgentControl.builder(policy).audit(records::add).build()) {
            var assistant =
                    new LangChain4jGovernance(control)
                            .create(
                                    Assistant.class,
                                    // 示例身份固定；真实应用从服务端已验证的认证上下文取得。
                                    () ->
                                            new TrustedContext(
                                                    "demo", "tenant-a", "user", Set.of("read")),
                                    builder ->
                                            builder.chatModel(new Model())
                                                    .systemMessageProvider(
                                                            id ->
                                                                    "Use tools to answer the request.")
                                                    .tools(tools)
                                                    .maxToolCallingRoundTrips(3));
            String answer = assistant.chat("查询当前租户资料");
            long closed =
                    records.stream()
                            .filter(r -> r.point() == InterventionPoint.AGENT_SHUTDOWN)
                            .count();
            if (!"masked-value".equals(answer) || tools.effects.get() != 1 || closed != 1) {
                throw new IllegalStateException("原生接入演示验收失败");
            }
            System.out.println(
                    "answer="
                            + answer
                            + ", toolEffects="
                            + tools.effects.get()
                            + ", closedSessions="
                            + closed);
        }
    }

    /**
     * 确定性工具循环模型。
     *
     * <p>实际业务可替换为任意符合当前支持契约的 ChatModel 实现。
     */
    private static final class Model implements ChatModel {
        @Override
        public ChatResponse doChat(ChatRequest request) {
            var last = request.messages().get(request.messages().size() - 1);
            AiMessage answer =
                    last instanceof ToolExecutionResultMessage result
                            ? AiMessage.from(result.text())
                            : AiMessage.from(
                                    ToolExecutionRequest.builder()
                                            .id("lookup-1")
                                            .name("lookup")
                                            .arguments("{\"tenant\":\"tenant-a\"}")
                                            .build());
            return ChatResponse.builder().aiMessage(answer).build();
        }
    }
}
