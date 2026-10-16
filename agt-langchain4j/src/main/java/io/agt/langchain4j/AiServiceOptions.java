package io.agt.langchain4j;

import java.util.Objects;

/**
 * 单个原生 AI Service 的不可变交付配置。
 *
 * <p>预取窗口限制外层 Publisher 队列；模型流与服务聚合输出均使用 streamingLimits。
 */
public record AiServiceOptions(
        StreamDelivery delivery, StreamingLimits streamingLimits, int publisherPrefetch) {
    public static final AiServiceOptions DEFAULT =
            new AiServiceOptions(StreamDelivery.REALTIME, StreamingLimits.DEFAULT, 32);

    public AiServiceOptions {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(streamingLimits, "streamingLimits");
        if (publisherPrefetch < 1 || publisherPrefetch > 1024) {
            throw new IllegalArgumentException("publisherPrefetch must be between 1 and 1024");
        }
    }
}
