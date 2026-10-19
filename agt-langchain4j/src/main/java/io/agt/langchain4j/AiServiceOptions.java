package io.agt.langchain4j;

import io.agt.core.streaming.ChunkBufferOptions;
import java.util.Objects;

/**
 * 单个原生 AI Service 的不可变交付配置。
 *
 * <p>预取窗口限制外层 Publisher 队列；模型流与服务聚合输出均使用 streamingLimits。
 */
public record AiServiceOptions(
        StreamDelivery delivery,
        StreamingLimits streamingLimits,
        int publisherPrefetch,
        ChunkBufferOptions guardrails) {
    public static final AiServiceOptions DEFAULT =
            new AiServiceOptions(StreamDelivery.REALTIME, StreamingLimits.DEFAULT, 32);

    public AiServiceOptions(StreamDelivery delivery, StreamingLimits limits, int prefetch) {
        this(delivery, limits, prefetch, null);
    }

    public AiServiceOptions {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(streamingLimits, "streamingLimits");
        if (delivery == StreamDelivery.GUARDED && guardrails == null) {
            throw new IllegalArgumentException("GUARDED requires explicit local rules");
        }
        if (guardrails != null
                && guardrails.maxBufferedCharacters() > streamingLimits.maxCharacters()) {
            throw new IllegalArgumentException("guarded buffer exceeds streaming limit");
        }
        if (publisherPrefetch < 1 || publisherPrefetch > 1024) {
            throw new IllegalArgumentException("publisherPrefetch must be between 1 and 1024");
        }
    }
}
