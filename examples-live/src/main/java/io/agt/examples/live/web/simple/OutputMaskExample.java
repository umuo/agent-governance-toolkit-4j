package io.agt.examples.live.web.simple;

import dev.langchain4j.model.chat.ChatModel;
import io.agt.langchain4j.spring.GovernedAiServices;

/** 输出护栏：最终回答中的演示号码 13800138000 替换为 138****8000。 规则见 SimpleExamplesConfiguration；普通聊天案例可用于对照。 */
public final class OutputMaskExample {
    private OutputMaskExample() {}

    public static String chat(GovernedAiServices services, ChatModel model, String message) {
        var assistant =
                services.create(
                        PlainChatExample.Assistant.class, builder -> builder.chatModel(model));
        return assistant.chat(message);
    }
}
