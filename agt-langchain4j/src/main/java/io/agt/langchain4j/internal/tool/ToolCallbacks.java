package io.agt.langchain4j.internal.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.agt.core.HostSession;

/**
 * 工具内部受控事件观察者。
 *
 * <p>before 只接收授权后的参数，after 只接收 POST_TOOL_CALL 检查后的结果。
 */
public interface ToolCallbacks {
    ToolCallbacks NONE = new ToolCallbacks() {};

    default void before(
            HostSession session, ToolExecutionRequest request, InvocationContext context) {}

    default void rawResult(
            HostSession session,
            ToolExecutionRequest request,
            InvocationContext context,
            ToolExecutionResult result) {}

    default void after(
            HostSession session,
            ToolExecutionRequest request,
            InvocationContext context,
            ToolExecutionResult result) {}
}
