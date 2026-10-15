package io.agt.langchain4j.internal.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.Identifiers;
import io.agt.core.InterventionPoint;
import io.agt.core.JsonValues;
import io.agt.langchain4j.internal.concurrent.AsyncSources;
import io.agt.langchain4j.internal.message.TextProjection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 受治理的工具执行器包装类。
 *
 * <p>拦截 PRE_TOOL_CALL 与 POST_TOOL_CALL，执行工具参数校验、改写、执行限额管控及异常隔离。
 */
final class GovernedToolExecutor implements ToolExecutor {
    private final AgentControl control;
    private final String toolName;
    private final ToolExecutor delegate;

    GovernedToolExecutor(AgentControl control, String toolName, ToolExecutor delegate) {
        this.control = control;
        this.toolName = Identifiers.require(toolName);
        this.delegate = delegate;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return govern(
                        request,
                        effective ->
                                ToolExecutionResult.builder()
                                        .resultText(delegate.execute(effective, memoryId))
                                        .build())
                .resultText();
    }

    @Override
    public ToolExecutionResult executeWithContext(
            ToolExecutionRequest request, InvocationContext context) {
        return govern(request, effective -> delegate.executeWithContext(effective, context));
    }

    private ToolExecutionResult govern(
            ToolExecutionRequest request,
            Function<ToolExecutionRequest, ToolExecutionResult> execute) {
        HostSession session = control.currentSession();
        try {
            if (!toolName.equals(request.name())) {
                throw new GovernanceException("tool_identity_mismatch");
            }
            var args = JsonValues.parseObject(request.arguments());
            return session.operationPrepared(
                    true,
                    toolName,
                    args,
                    effective ->
                            prepare(
                                    ToolExecutionRequest.builder()
                                            .id(request.id())
                                            .name(toolName)
                                            .arguments(
                                                    JsonValues.write(
                                                            TextProjection.object(effective)))
                                            .build()),
                    execute,
                    GovernedToolExecutor::project,
                    GovernedToolExecutor::replace);
        } catch (RuntimeException e) {
            String code = e instanceof GovernanceException g ? g.code() : "tool_failed";
            throw session.budgetSnapshot().failed()
                    ? session.refuse(code)
                    : session.fail(InterventionPoint.PRE_TOOL_CALL, code);
        }
    }

    private ToolExecutionRequest prepare(ToolExecutionRequest request) {
        return delegate instanceof CommandToolExecutor command ? command.prepare(request) : request;
    }

    private static Map<String, Object> project(ToolExecutionResult result) {
        var p = new LinkedHashMap<String, Object>();
        if (result.result() != null && !(result.result() instanceof String)) {
            throw new GovernanceException("complex_tool_result_unsupported");
        }
        p.put("text", result.resultText());
        p.put("result", result.result());
        p.put("attributes", result.attributes());
        if (result.resultContents().size() != 1
                || !(result.resultContents().get(0) instanceof TextContent)) {
            throw new GovernanceException("multimodal_unsupported");
        }
        return TextProjection.object(JsonValues.freeze(p));
    }

    private static ToolExecutionResult replace(ToolExecutionResult original, Object value) {
        var p = TextProjection.object(value);
        TextProjection.sameKeys(project(original), p);
        // resultText 和 resultContents 在 LC4j 中是互斥构造入口，不能同时设置。
        return ToolExecutionResult.builder()
                .isError(original.isError())
                .resultText(TextProjection.requiredString(p.get("text")))
                .result(TextProjection.nullableString(p.get("result")))
                .attributes(TextProjection.nullableObject(p.get("attributes")))
                .build();
    }

    @Override
    public CompletableFuture<ToolExecutionResult> executeAsync(
            ToolExecutionRequest request, InvocationContext context) {
        HostSession captured = null;
        try {
            HostSession session = control.currentSession();
            captured = session;
            if (!toolName.equals(request.name())) {
                throw session.fail(InterventionPoint.PRE_TOOL_CALL, "tool_identity_mismatch");
            }
            return session.operationAsyncPrepared(
                    true,
                    toolName,
                    JsonValues.parseObject(request.arguments()),
                    effective ->
                            prepare(
                                    ToolExecutionRequest.builder()
                                            .id(request.id())
                                            .name(toolName)
                                            .arguments(
                                                    JsonValues.write(
                                                            TextProjection.object(effective)))
                                            .build()),
                    effective ->
                            AsyncSources.normalize(
                                    delegate.executeAsync(effective, context), "tool"),
                    GovernedToolExecutor::project,
                    GovernedToolExecutor::replace);
        } catch (RuntimeException e) {
            if (captured != null && !captured.budgetSnapshot().failed()) {
                try {
                    e =
                            captured.fail(
                                    InterventionPoint.PRE_TOOL_CALL,
                                    e instanceof GovernanceException g ? g.code() : "tool_failed");
                } catch (GovernanceException failure) {
                    e = failure;
                }
            }
            return CompletableFuture.failedFuture(e);
        }
    }
}
