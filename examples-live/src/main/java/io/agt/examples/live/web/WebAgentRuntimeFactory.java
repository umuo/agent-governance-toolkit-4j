package io.agt.examples.live.web;

import io.agt.core.AgentControl;
import io.agt.core.PolicyEvaluator;
import io.agt.core.TrustedContext;
import io.agt.core.approval.ApprovalResolver;
import io.agt.core.audit.AuditSink;
import io.agt.langchain4j.spring.GovernanceAutoConfiguration;
import io.agt.langchain4j.spring.GovernedAiServices;
import io.agt.langchain4j.spring.TrustedContextResolver;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 为每轮浏览器调用创建独立的 Starter 上下文。
 *
 * <p>策略、身份、审批与审计按轮隔离；服务默认配置继承宿主 Spring Environment。 独立上下文关闭时销毁治理运行时，审计文件仍由调用方管理。
 */
@Component
public final class WebAgentRuntimeFactory {
    private final Environment environment;

    public WebAgentRuntimeFactory(Environment environment) {
        this.environment = environment;
    }

    public Runtime open(
            PolicyEvaluator policy,
            TrustedContext identity,
            AuditSink audit,
            ApprovalResolver approvals) {
        var context = new AnnotationConfigApplicationContext();
        if (environment instanceof ConfigurableEnvironment configurable) {
            context.getEnvironment().merge(configurable);
        }
        context.registerBean(
                AgentControl.class,
                () ->
                        AgentControl.builder(policy)
                                .audit(audit)
                                .approvalResolver(approvals)
                                .build());
        context.registerBean(TrustedContextResolver.class, () -> () -> identity);
        context.register(GovernanceAutoConfiguration.class);
        try {
            context.refresh();
            return new Runtime(
                    context,
                    context.getBean(AgentControl.class),
                    context.getBean(GovernedAiServices.class),
                    identity);
        } catch (RuntimeException | Error error) {
            context.close();
            throw error;
        }
    }

    public record Runtime(
            AnnotationConfigApplicationContext context,
            AgentControl control,
            GovernedAiServices services,
            TrustedContext identity)
            implements AutoCloseable {
        @Override
        public void close() {
            context.close();
        }
    }
}
