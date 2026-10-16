package io.agt.langchain4j;

/**
 * 新原生接入入口的流式交付契约。
 *
 * <p>REALTIME 的增量是未经最终检查的预览；STRICT 在完整检查后交付，均不绕过工具授权。
 */
public enum StreamDelivery {
    REALTIME,
    STRICT
}
