package io.agt.langchain4j.internal.streaming;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import java.util.concurrent.CompletableFuture;

/**
 * 公开流门面与内部状态机之间的最小桥接协议。
 *
 * <p>仅供适配器内部装配，应用应通过 BufferedTokenStream 获取完成与取消能力。
 */
public interface BufferedStreamControl extends TokenStream {
    CompletableFuture<ChatResponse> completion();

    boolean cancel();
}
