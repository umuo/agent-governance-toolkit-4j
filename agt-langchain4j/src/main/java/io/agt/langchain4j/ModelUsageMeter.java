package io.agt.langchain4j;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import io.agt.core.GovernanceException;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** 宿主 Provider 契约：限制实际输出，并为完整请求（含工具/媒体/协议开销）提供输入 Token 上界。 */
@FunctionalInterface
public interface ModelUsageMeter {
    Bound bind(ChatRequest request, int outputTokenLimit);

    record Bound(ChatRequest request, int inputTokenUpperBound) {
        public Bound {
            Objects.requireNonNull(request);
        }
    }

    /** 仅适用于标准参数；Provider 专有参数必须由宿主显式处理，不能无声忽略其他输出限制。 */
    static ModelUsageMeter standardParameters(ToIntFunction<ChatRequest> inputUpperBound) {
        Objects.requireNonNull(inputUpperBound);
        return (request, output) -> {
            if (request.parameters().getClass() != DefaultChatRequestParameters.class) {
                throw new GovernanceException("model_usage_parameter_contract_required");
            }
            var bounded =
                    request.toBuilder()
                            .parameters(
                                    request.parameters()
                                            .overrideWith(
                                                    ChatRequestParameters.builder()
                                                            .maxOutputTokens(output)
                                                            .build()))
                            .build();
            return new Bound(bounded, inputUpperBound.applyAsInt(bounded));
        };
    }
}
