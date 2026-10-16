package io.agt.examples.live.web.simple;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

/** 原生普通聊天：只创建接口并调用，不注册工具或治理规则。 */
public final class PlainChatExample {
    public interface Assistant {
        String chat(String message);
    }

    private PlainChatExample() {}

    public static String chat(ChatModel model, String message) {
        var assistant = AiServices.builder(Assistant.class).chatModel(model).build();
        return assistant.chat(message);
    }
}
