package io.agt.langchain4j.spring;

import dev.langchain4j.service.AiServices;
import io.agt.langchain4j.AiServiceOptions;
import io.agt.langchain4j.LangChain4jGovernance;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Spring 宿主的受治理 AI Service 工厂。
 *
 * <p>在普通 Bean 方法中使用原生配置回调，可信身份在实际调用时解析。
 */
public final class GovernedAiServices {
    private final LangChain4jGovernance governance;
    private final TrustedContextResolver identity;
    private final AiServiceOptions defaults;

    public GovernedAiServices(
            LangChain4jGovernance governance,
            TrustedContextResolver identity,
            AiServiceOptions defaults) {
        this.governance = Objects.requireNonNull(governance);
        this.identity = Objects.requireNonNull(identity);
        this.defaults = Objects.requireNonNull(defaults);
    }

    public <T> T create(Class<T> type, Consumer<AiServices<T>> configure) {
        return create(type, defaults, configure);
    }

    public <T> T create(
            Class<T> type, AiServiceOptions options, Consumer<AiServices<T>> configure) {
        return governance.create(type, identity::resolve, options, configure);
    }
}
