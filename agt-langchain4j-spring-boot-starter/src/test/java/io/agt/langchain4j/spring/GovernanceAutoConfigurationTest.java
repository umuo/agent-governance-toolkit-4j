package io.agt.langchain4j.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.*;
import io.agt.core.audit.AuditRecord;
import io.agt.core.policy.*;
import io.agt.langchain4j.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 真正的 Boot 上下文装配、属性绑定、宿主覆盖与调用闭环。 */
class GovernanceAutoConfigurationTest {
    interface Assistant {
        String chat(String input);
    }

    static final TrustedContext IDENTITY = new TrustedContext("agent", "tenant", "user", Set.of());
    final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(GovernanceAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class Host {
        @Bean
        List<AuditRecord> records() {
            return new java.util.concurrent.CopyOnWriteArrayList<>();
        }

        @Bean
        AgentControl control(List<AuditRecord> records) {
            return AgentControl.builder(StructuredPolicy.builder("test").build())
                    .audit(records::add)
                    .build();
        }

        @Bean
        AtomicInteger identities() {
            return new AtomicInteger();
        }

        @Bean
        TrustedContextResolver identity(AtomicInteger identities) {
            return () -> {
                identities.incrementAndGet();
                return IDENTITY;
            };
        }

        @Bean
        ChatModel model() {
            return new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("answer")).build();
                }
            };
        }

        @Bean
        Assistant assistant(GovernedAiServices services, ChatModel model) {
            return services.create(Assistant.class, b -> b.chatModel(model));
        }
    }

    @Test
    void guardedPropertiesShareJavaValidationAndServiceOverrides() {
        var policy =
                StructuredPolicy.builder("guarded")
                        .content(
                                new ContentRule(
                                        "local",
                                        Set.of(InterventionPoint.POST_MODEL_CALL),
                                        List.of("ABCDEF"),
                                        Map.of()))
                        .build();
        runner.withBean(
                        AgentControl.class,
                        () -> AgentControl.builder(policy).audit(r -> {}).build())
                .withBean(TrustedContextResolver.class, () -> () -> IDENTITY)
                .withPropertyValues(
                        "agt.langchain4j.delivery=GUARDED",
                        "agt.langchain4j.guardrails.local-rules=local",
                        "agt.langchain4j.guardrails.chunk-characters=4",
                        "agt.langchain4j.guardrails.max-wait=25ms",
                        "agt.langchain4j.guardrails.max-buffered-characters=128")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var options = context.getBean(AiServiceOptions.class);
                            assertEquals(StreamDelivery.GUARDED, options.delivery());
                            assertEquals(4, options.guardrails().chunkCharacters());
                            assertEquals(
                                    java.time.Duration.ofMillis(25),
                                    options.guardrails().maxWait());
                            assertEquals(Set.of("local"), options.guardrails().localRules());
                            assertEquals(
                                    StreamDelivery.REALTIME, AiServiceOptions.DEFAULT.delivery());
                        });
        var properties = new GovernanceProperties();
        properties.setDelivery(StreamDelivery.GUARDED);
        assertThrows(IllegalArgumentException.class, properties::toOptions);
        properties.getGuardrails().setLocalRules(Set.of("local"));
        properties.getGuardrails().setMaxWait(java.time.Duration.ZERO);
        assertThrows(IllegalArgumentException.class, properties::toOptions);
    }

    @Test
    void bindsPropertiesAndCallsGovernedBeanWithLazyIdentityAndShutdownAudit() {
        runner.withUserConfiguration(Host.class)
                .withPropertyValues(
                        "agt.langchain4j.delivery=STRICT",
                        "agt.langchain4j.max-events=123",
                        "agt.langchain4j.max-characters=1000",
                        "agt.langchain4j.publisher-prefetch=2")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(GovernedAiServices.class);
                            assertEquals(
                                    new AiServiceOptions(
                                            StreamDelivery.STRICT,
                                            new StreamingLimits(1000, 123),
                                            2),
                                    context.getBean(AiServiceOptions.class));
                            assertEquals(0, context.getBean(AtomicInteger.class).get());
                            assertEquals("answer", context.getBean(Assistant.class).chat("hello"));
                            assertEquals("answer", context.getBean(Assistant.class).chat("again"));
                            assertEquals(2, context.getBean(AtomicInteger.class).get());
                            @SuppressWarnings("unchecked")
                            List<AuditRecord> records =
                                    (List<AuditRecord>) context.getBean("records");
                            assertEquals(
                                    2,
                                    records.stream()
                                            .filter(
                                                    r ->
                                                            r.point()
                                                                    == InterventionPoint
                                                                            .AGENT_SHUTDOWN)
                                            .count());
                        });
    }

    @Test
    void noRuntimeMeansNoDefaultPolicyOrFactory() {
        runner.run(
                context ->
                        assertThat(context)
                                .hasNotFailed()
                                .doesNotHaveBean(AgentControl.class)
                                .doesNotHaveBean(GovernedAiServices.class));
    }

    @Test
    void missingIdentityFailsAtStartup() {
        runner.withBean(
                        AgentControl.class,
                        () ->
                                AgentControl.builder(StructuredPolicy.builder("test").build())
                                        .audit(r -> {})
                                        .build())
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasMessageContaining("agtGovernedAiServices"));
    }

    @Test
    void disabledDoesNotRequireIdentity() {
        runner.withBean(
                        AgentControl.class,
                        () ->
                                AgentControl.builder(StructuredPolicy.builder("test").build())
                                        .audit(r -> {})
                                        .build())
                .withPropertyValues("agt.langchain4j.enabled=false")
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .doesNotHaveBean(GovernedAiServices.class));
    }

    @Test
    void invalidCapacityFailsDuringStartup() {
        runner.withUserConfiguration(Host.class)
                .withPropertyValues("agt.langchain4j.publisher-prefetch=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void customOptionsAndFactoryTakePrecedence() {
        var options = new AiServiceOptions(StreamDelivery.STRICT, new StreamingLimits(200, 20), 1);
        try (var control =
                AgentControl.builder(StructuredPolicy.builder("test").build())
                        .audit(r -> {})
                        .build()) {
            var factory =
                    new GovernedAiServices(
                            new LangChain4jGovernance(control), () -> IDENTITY, options);
            runner.withBean(AgentControl.class, () -> control)
                    .withBean(AiServiceOptions.class, () -> options)
                    .withBean(GovernedAiServices.class, () -> factory)
                    .run(
                            context -> {
                                assertThat(context)
                                        .hasNotFailed()
                                        .hasSingleBean(GovernedAiServices.class);
                                assertSame(factory, context.getBean(GovernedAiServices.class));
                                assertSame(options, context.getBean(AiServiceOptions.class));
                            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class Discovery {}

    @Test
    void bootFindsConfigurationThroughImportsResource() {
        new ApplicationContextRunner()
                .withUserConfiguration(Discovery.class, Host.class)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(GovernedAiServices.class);
                            assertEquals("answer", context.getBean(Assistant.class).chat("hello"));
                        });
    }

    public static class Tools {
        final AtomicInteger effects = new AtomicInteger();

        @dev.langchain4j.agent.tool.Tool
        public String lookup() {
            effects.incrementAndGet();
            return "found";
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void nativeToolSideEffectsFollowHostApprovalInsideBoot(boolean approve) {
        var tool = new Tools();
        var approvals = new AtomicInteger();
        var policy =
                StructuredPolicy.builder("approval")
                        .tool(
                                "lookup",
                                new ToolRule(Set.of(), ParameterRule.object(Map.of()), true))
                        .build();
        var control =
                AgentControl.builder(policy)
                        .audit(r -> {})
                        .approvalResolver(
                                request -> {
                                    approvals.incrementAndGet();
                                    return java.util.concurrent.CompletableFuture.completedFuture(
                                            approve
                                                    ? io.agt.core.approval.ApprovalResponse.allow(
                                                            request)
                                                    : io.agt.core.approval.ApprovalResponse.deny(
                                                            request));
                                })
                        .build();
        ChatModel model =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        boolean hasResult =
                                request.messages().stream()
                                        .anyMatch(
                                                dev.langchain4j.data.message
                                                                .ToolExecutionResultMessage.class
                                                        ::isInstance);
                        return ChatResponse.builder()
                                .aiMessage(
                                        hasResult
                                                ? AiMessage.from("done")
                                                : AiMessage.from(
                                                        dev.langchain4j.agent.tool
                                                                .ToolExecutionRequest.builder()
                                                                .id("tool-1")
                                                                .name("lookup")
                                                                .arguments("{}")
                                                                .build()))
                                .build();
                    }
                };
        runner.withBean(AgentControl.class, () -> control)
                .withBean(TrustedContextResolver.class, () -> () -> IDENTITY)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var service =
                                    context.getBean(GovernedAiServices.class)
                                            .create(
                                                    Assistant.class,
                                                    b -> b.chatModel(model).tools(tool));
                            if (approve) assertEquals("done", service.chat("lookup"));
                            else
                                assertThrows(
                                        GovernanceException.class, () -> service.chat("lookup"));
                            assertEquals(1, approvals.get());
                            assertEquals(approve ? 1 : 0, tool.effects.get());
                        });
    }
}
