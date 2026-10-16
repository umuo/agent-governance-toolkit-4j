package io.agt.langchain4j.spring;

import io.agt.core.TrustedContext;

/**
 * 宿主认证入口的身份解析器。
 *
 * <p>每次顶层调用解析身份；嵌套调用复用当前治理会话。不得从模型输入推断权限。
 */
@FunctionalInterface
public interface TrustedContextResolver {
    TrustedContext resolve();
}
