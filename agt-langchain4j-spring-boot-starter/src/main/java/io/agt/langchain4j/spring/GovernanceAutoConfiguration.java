package io.agt.langchain4j.spring;

import io.agt.core.AgentControl;
import io.agt.langchain4j.AiServiceOptions;
import io.agt.langchain4j.LangChain4jGovernance;
import io.agt.langchain4j.ModelTerminationContract;
import io.agt.langchain4j.ModelUsageMeter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 宿主提供治理运行时后的条件装配。
 *
 * <p>不创建默认放行策略或身份。身份解析器缺失/歧义由 Spring 在启动时报告。
 */
@AutoConfiguration
@ConditionalOnBean(AgentControl.class)
@ConditionalOnProperty(
        prefix = "agt.langchain4j",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(GovernanceProperties.class)
public class GovernanceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AiServiceOptions agtAiServiceOptions(
            GovernanceProperties properties,
            ObjectProvider<ModelUsageMeter> meter,
            ObjectProvider<ModelTerminationContract> termination) {
        var defaults = properties.toOptions();
        return new AiServiceOptions(
                defaults.delivery(),
                defaults.streamingLimits(),
                defaults.publisherPrefetch(),
                meter.getIfAvailable(),
                termination.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    LangChain4jGovernance agtLangChain4jGovernance(AgentControl control, AiServiceOptions options) {
        return new LangChain4jGovernance(control, options);
    }

    @Bean
    @ConditionalOnMissingBean
    GovernedAiServices agtGovernedAiServices(
            LangChain4jGovernance governance,
            TrustedContextResolver identity,
            AiServiceOptions options) {
        return new GovernedAiServices(governance, identity, options);
    }
}
