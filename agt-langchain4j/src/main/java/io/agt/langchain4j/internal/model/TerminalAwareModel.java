package io.agt.langchain4j.internal.model;

import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.concurrent.CompletableFuture;

/** 内部流式收集器从原生终止回调报证据，不能从收集 future 的取消推导远端已停止。 */
public interface TerminalAwareModel extends ChatModel {
    CompletableFuture<ChatResponse> chatAsyncObserved(
            ChatRequest request, ChatRequestOptions options, ModelExecutionBridge.Call observation);
}
