package io.agt.langchain4j;

/**
 * 每轮模型流的有界容量。
 *
 * <p>字符数包含增量与最终响应，事件包含被抑制的事件。
 */
public record StreamingLimits(int maxCharacters, int maxEvents) {
    public static final StreamingLimits DEFAULT = new StreamingLimits(1_048_576, 10_000);

    public StreamingLimits {
        if (maxCharacters < 1
                || maxCharacters > 16_777_216
                || maxEvents < 1
                || maxEvents > 100_000) {
            throw new IllegalArgumentException("invalid streaming limits");
        }
    }
}
