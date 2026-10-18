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
import io.agt.langchain4j.internal.service.MediaValues;
import io.agt.langchain4j.internal.service.TypedValues;
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
    private final boolean typed;
    private final ToolCallbacks callbacks;
    private final io.agt.core.tools.ToolLease lease;

    GovernedToolExecutor(
            AgentControl control,
            String toolName,
            ToolExecutor delegate,
            boolean typed,
            ToolCallbacks callbacks) {
        this.control = control;
        this.toolName = Identifiers.require(toolName);
        this.delegate = delegate;
        this.typed = typed;
        this.callbacks = callbacks;
        this.lease = control.reviewedTool(toolName);
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return govern(
                        request,
                        null,
                        effective ->
                                ToolExecutionResult.builder()
                                        .resultText(delegate.execute(effective, memoryId))
                                        .build())
                .resultText();
    }

    @Override
    public ToolExecutionResult executeWithContext(
            ToolExecutionRequest request, InvocationContext context) {
        return govern(
                request, context, effective -> delegate.executeWithContext(effective, context));
    }

    private ToolExecutionResult govern(
            ToolExecutionRequest request,
            InvocationContext context,
            Function<ToolExecutionRequest, ToolExecutionResult> execute) {
        HostSession session = control.currentSession();
        try {
            if (!toolName.equals(request.name())) {
                throw new GovernanceException("tool_identity_mismatch");
            }
            var args = JsonValues.parseObject(request.arguments());
            var actual = new java.util.concurrent.atomic.AtomicReference<ToolExecutionRequest>();
            return operate(
                    session,
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
                    effective -> {
                        actual.set(effective);
                        callbacks.before(session, effective, context);
                        checkLease(session);
                        var result = execute.apply(effective);
                        checkLease(session);
                        callbacks.rawResult(session, effective, context, result);
                        return result;
                    },
                    this::project,
                    (original, effective) -> {
                        var checked = replace(original, effective);
                        checkLease(session);
                        callbacks.after(session, actual.get(), context, checked);
                        return checked;
                    });
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

    private Map<String, Object> project(ToolExecutionResult result) {
        var p = new LinkedHashMap<String, Object>();
        if (!typed && result.result() != null && !(result.result() instanceof String)) {
            throw new GovernanceException("complex_tool_result_unsupported");
        }
        p.put("text", result.resultText());
        p.put("result", typed ? TypedValues.project(result.result()) : result.result());
        p.put("attributes", result.attributes());
        if (result.resultContents().size() != 1
                || !(result.resultContents().get(0) instanceof TextContent)) {
            if (!typed) {
                throw new GovernanceException("multimodal_unsupported");
            }
            p.put("contents", MediaValues.project(result.resultContents()));
        }
        return TextProjection.object(JsonValues.freeze(p));
    }

    private ToolExecutionResult replace(ToolExecutionResult original, Object value) {
        var p = TextProjection.object(value);
        TextProjection.sameKeys(project(original), p);
        // resultText 和 resultContents 在 LC4j 中是互斥构造入口，不能同时设置。
        Object result =
                typed
                        ? TypedValues.restore(
                                original.result(),
                                p.get("result"),
                                original.result() == null
                                        ? Object.class
                                        : original.result().getClass())
                        : TextProjection.nullableString(p.get("result"));
        var builder =
                ToolExecutionResult.builder()
                        .isError(original.isError())
                        .result(result)
                        .attributes(TextProjection.nullableObject(p.get("attributes")));
        if (p.containsKey("contents")) {
            builder.resultContents(
                    MediaValues.restore(original.resultContents(), p.get("contents")));
        } else {
            String text = TextProjection.requiredString(p.get("text"));
            if (original.result() instanceof String old
                    && old.equals(original.resultText())
                    && !java.util.Objects.equals(text, result)) {
                throw new GovernanceException("tool_result_view_mismatch");
            }
            if (typed && original.result() != null && !(original.result() instanceof String)) {
                Object priorText = null;
                try {
                    priorText = JsonValues.parse(original.resultText());
                } catch (RuntimeException ignored) {
                }
                if (priorText != null
                        && TypedValues.equivalent(priorText, TypedValues.project(original.result()))
                        && !TypedValues.equivalent(
                                JsonValues.parse(text), TypedValues.project(result))) {
                    throw new GovernanceException("tool_result_view_mismatch");
                }
            }
            builder.resultText(text);
        }
        return builder.build();
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
            var actual = new java.util.concurrent.atomic.AtomicReference<ToolExecutionRequest>();
            return operateAsync(
                    session,
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
                    effective -> {
                        actual.set(effective);
                        callbacks.before(session, effective, context);
                        checkLease(session);
                        var source = delegate.executeAsync(effective, context);
                        var observed =
                                new CompletableFuture<ToolExecutionResult>() {
                                    public boolean cancel(boolean interrupt) {
                                        boolean changed = super.cancel(interrupt);
                                        if (changed) source.cancel(interrupt);
                                        return changed;
                                    }
                                };
                        source.whenComplete(
                                (value, error) -> {
                                    try (var scope = session.bind()) {
                                        if (error != null) observed.completeExceptionally(error);
                                        else {
                                            session.checkActive();
                                            checkLease(session);
                                            callbacks.rawResult(session, effective, context, value);
                                            observed.complete(value);
                                        }
                                    } catch (Throwable failure) {
                                        observed.completeExceptionally(failure);
                                    }
                                });
                        return AsyncSources.normalize(observed, "tool");
                    },
                    this::project,
                    (original, effective) -> {
                        var checked = replace(original, effective);
                        checkLease(session);
                        callbacks.after(session, actual.get(), context, checked);
                        return checked;
                    });
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

    private void checkLease(HostSession session) {
        if (lease != null) {
            session.checkActive();
            lease.validate(session.context());
        }
    }

    private <E, T> T operate(
            HostSession session,
            Object input,
            Function<Object, E> prepare,
            Function<E, T> execute,
            Function<T, Object> project,
            java.util.function.BiFunction<T, Object, T> replace) {
        return lease == null
                ? session.operationPrepared(
                        true, toolName, input, prepare, execute, project, replace)
                : session.reviewedToolOperation(lease, input, prepare, execute, project, replace);
    }

    private <E, T> CompletableFuture<T> operateAsync(
            HostSession session,
            Object input,
            Function<Object, E> prepare,
            Function<E, ? extends java.util.concurrent.CompletionStage<T>> execute,
            Function<T, Object> project,
            java.util.function.BiFunction<T, Object, T> replace) {
        return lease == null
                ? session.operationAsyncPrepared(
                        true, toolName, input, prepare, execute, project, replace)
                : session.reviewedToolOperationAsync(
                        lease, input, prepare, execute, project, replace);
    }
}
