package io.agt.langchain4j;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.execution.TerminationEvidence;
import java.util.concurrent.CompletionStage;

/**
 * 可信宿主的逐请求 Provider 终止契约。bind/完成分类必须是快速本地操作，不发出生成请求。 取消 stage 只能在核实同一请求已终止或保证不会再开始后完成，不能把断开连接/404
 * 当作证明。
 */
@FunctionalInterface
public interface ModelTerminationContract {
    Call bind(ChatRequest actualRequest);

    interface Call {
        CompletionStage<TerminationEvidence> requestCancellation();

        default TerminationEvidence completed(ChatResponse response) {
            return TerminationEvidence.CONFIRMED;
        }

        default TerminationEvidence failed(Throwable error) {
            return TerminationEvidence.UNKNOWN;
        }
    }
}
