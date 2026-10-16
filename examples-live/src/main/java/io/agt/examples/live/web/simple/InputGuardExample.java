package io.agt.examples.live.web.simple;

import dev.langchain4j.model.chat.ChatModel;
import io.agt.langchain4j.spring.GovernedAiServices;

/** 输入护栏：包含“导出全部客户”的输入在模型调用之前被拒绝。 规则见 SimpleExamplesConfiguration，身份与会话由 Starter 管理。 */
public final class InputGuardExample {
    private InputGuardExample() {}

    public static String chat(GovernedAiServices services, ChatModel model, String message) {
        var assistant =
                services.create(
                        PlainChatExample.Assistant.class, builder -> builder.chatModel(model));
        return assistant.chat(message);
    }
}
