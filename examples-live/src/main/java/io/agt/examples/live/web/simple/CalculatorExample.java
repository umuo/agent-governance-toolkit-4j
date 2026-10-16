package io.agt.examples.live.web.simple;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

/** 原生工具调用：让模型选择加法工具，无 AGT 治理。 */
public final class CalculatorExample {
    public static class Calculator {
        @Tool("计算两个整数的和")
        public int add(int a, int b) {
            return Math.addExact(a, b);
        }
    }

    private CalculatorExample() {}

    public static String chat(ChatModel model, String message) {
        var assistant =
                AiServices.builder(PlainChatExample.Assistant.class)
                        .chatModel(model)
                        .tools(new Calculator())
                        .build();
        return assistant.chat(message);
    }
}
