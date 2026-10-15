package io.agt.langchain4j.internal.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agt.core.AgentControl;
import io.agt.core.JsonValues;
import io.agt.core.execution.FixedCommand;
import java.util.concurrent.CompletableFuture;

/**
 * 固定命令的内部执行器。
 *
 * <p>必须由 GovernedToolExecutor 包装；准备阶段在审批与改写后、执行计数前校验模板。
 */
final class CommandToolExecutor implements ToolExecutor {
    private final AgentControl control;
    private final FixedCommand command;

    CommandToolExecutor(AgentControl control, FixedCommand command) {
        this.control = control;
        this.command = command;
    }

    ToolExecutionRequest prepare(ToolExecutionRequest request) {
        command.prepare(JsonValues.parseObject(request.arguments()));
        return request;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return command.execute(
                JsonValues.parseObject(request.arguments()), control.currentSession());
    }

    @Override
    public CompletableFuture<ToolExecutionResult> executeAsync(
            ToolExecutionRequest request, InvocationContext context) {
        var session = control.currentSession();
        return session.supplyAsync(
                () ->
                        CompletableFuture.completedFuture(
                                ToolExecutionResult.builder()
                                        .resultText(
                                                command.execute(
                                                        JsonValues.parseObject(request.arguments()),
                                                        session))
                                        .build()));
    }
}
