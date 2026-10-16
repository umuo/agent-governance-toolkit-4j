package io.agt.examples.live.web.simple;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.agt.core.AgentControl;
import io.agt.core.InterventionPoint;
import io.agt.core.TrustedContext;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.examples.live.config.LocalEnvironment;
import io.agt.langchain4j.spring.TrustedContextResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** 入门案例共用的三条规则；所有护栏案例都启用这些规则。 原生案例直接使用模型，不经过此运行时。身份固定为无权限的演示访客。 */
@Configuration(proxyBeanMethods = false)
public class SimpleExamplesConfiguration {
    @Bean
    @Lazy
    ChatModel exampleChatModel() throws IOException {
        var env = LocalEnvironment.load(Path.of("."), System.getenv());
        if (env.getOrDefault("LLM_API_KEY", "").isBlank()
                || env.getOrDefault("LLM_MODEL", "").isBlank()) {
            throw new IllegalStateException("请配置 LLM_API_KEY 和 LLM_MODEL");
        }
        return OpenAiChatModel.builder()
                .baseUrl(env.getOrDefault("LLM_BASE_URL", "https://api.openai.com/v1"))
                .apiKey(env.get("LLM_API_KEY"))
                .modelName(env.get("LLM_MODEL"))
                .timeout(Duration.ofSeconds(60))
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean
    AgentControl exampleControl() {
        var policy =
                StructuredPolicy.builder("simple-examples-v1")
                        .content(
                                new ContentRule(
                                        "customer_export_denied",
                                        Set.of(InterventionPoint.INPUT),
                                        List.of("导出全部客户"),
                                        Map.of()))
                        .content(
                                new ContentRule(
                                        "demo_phone_masked",
                                        Set.of(InterventionPoint.OUTPUT),
                                        List.of(),
                                        Map.of("13800138000", "138****8000")))
                        .tool(
                                "readInternalNote",
                                new ToolRule(
                                        Set.of("notes.read"),
                                        ParameterRule.object(Map.of()),
                                        false))
                        .build();
        // 入门案例不持久化审计；需要完整审计与事件观察时使用“完整 Agent”。
        return AgentControl.builder(policy).audit(record -> {}).build();
    }

    @Bean
    TrustedContextResolver exampleIdentity() {
        return () -> new TrustedContext("simple-examples", "demo", "guest", Set.of());
    }
}
