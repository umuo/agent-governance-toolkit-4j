package io.agt.examples.live.web.simple;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import io.agt.core.GovernanceException;
import io.agt.langchain4j.spring.GovernedAiServices;

/** 工具护栏：readInternalNote 需要 notes.read，演示访客没有此权限。 工具只有固定演示文本，没有真实文件或数据库访问。 */
public final class ToolPermissionExample {
    public static class Notes {
        @Tool("读取内部演示便签")
        public String readInternalNote() {
            return "内部演示便签：下周一发布。";
        }
    }

    private ToolPermissionExample() {}

    public static String chat(GovernedAiServices services, ChatModel model, String message) {
        var assistant =
                services.create(
                        PlainChatExample.Assistant.class,
                        builder ->
                                builder.chatModel(model)
                                        .tools(new Notes())
                                        // 将拒绝直接交给 HTTP 层，避免框架把异常作为工具文本继续重试。
                                        .toolExecutionErrorHandler(
                                                (error, context) -> {
                                                    if (error instanceof RuntimeException denied) {
                                                        throw denied;
                                                    }
                                                    throw new GovernanceException("tool_failed");
                                                }));
        return assistant.chat(message);
    }
}
