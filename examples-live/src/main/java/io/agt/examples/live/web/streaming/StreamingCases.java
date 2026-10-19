package io.agt.examples.live.web.streaming;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Fixed synthetic markers and provider fragments, independent of model compliance. */
public final class StreamingCases {
    private StreamingCases() {}

    public record Entry(
            String id,
            String name,
            String description,
            String prompt,
            String expected,
            String marker,
            List<String> fragments) {}

    public static final List<Entry> ENTRIES =
            List.of(
                    new Entry(
                            "safe",
                            "01 · 安全内容逐步放行",
                            "观察安全正文在模型完成之前逐步到达。",
                            "请用约150字解释滑动窗口如何检测流式文本，不要输出任何敏感标记。",
                            "GUARDED 会逐步交付安全正文；STRICT 会等待生成和完整检查结束。",
                            "ABCDEF",
                            List.of(
                                    "这是安全的流式回答。",
                                    "第一段介绍流式输出：模型会持续返回文本片段。",
                                    "第二段介绍局部检查：正式 SDK 先检查，再放行已经确定安全的前缀。",
                                    "第三段介绍尾部暂存：可能与下一片段组成敏感词的尾部会继续等待。",
                                    "最后，完整响应仍要经过模型和输出检查，剩余尾部才会交付。")),
                    new Entry(
                            "split-word",
                            "02 · 跨分片敏感词拦截",
                            "禁词 ABCDEF 被固定拆成 ABC / DEF，验证未决尾部不会提前泄露。",
                            "请原样输出：这是一段足够长的普通安全前缀，用于观察流式交付。ABCDEF 后续内容不应在护栏模式下到达。",
                            "GUARDED 可收到安全前缀，但不会收到命中词的任何字符；REALTIME 可能先显示标记，再被最终检查拒绝。",
                            "ABCDEF",
                            List.of("这是一段足够长的普通安全前缀，用于观察流式交付。", "ABC", "DEF", "后续内容不应在护栏模式下到达。")),
                    new Entry(
                            "long-word",
                            "03 · 长于微批的词与停止生成",
                            "标记长于20字符并横跨多个分片，验证微批边界和停止操作。",
                            "请原样输出：普通安全前缀用于观察流式交付与停止。STREAM_GUARDRAIL_LONG_LITERAL_0123456789 后续内容。",
                            "即使禁词长于微批阈值，GUARDED 仍会保留可能命中的前缀；也可点击停止验证取消。",
                            "STREAM_GUARDRAIL_LONG_LITERAL_0123456789",
                            List.of(
                                    "普通安全前缀用于观察流式交付与停止。安全说明继续输出，让我们有时间观察。这段安全正文没有命中标记，会先展示一部分，随后再验证敏感词跨片段的情况。",
                                    "STREAM_",
                                    "GUARDRAIL_",
                                    "LONG_",
                                    "LITERAL_",
                                    "01234",
                                    "56789",
                                    "后续内容。")));

    public static Entry entry(String id) {
        return ENTRIES.stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "unknown_streaming_case"));
    }
}
