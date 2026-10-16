package io.agt.langchain4j.internal.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.*;
import io.agt.core.GovernanceException;
import io.agt.langchain4j.internal.concurrent.AsyncSources;
import java.util.concurrent.CompletableFuture;

/** 在治理边界内应用原生业务错误处理器，治理拒绝不可恢复。 */
public final class NativeToolErrors {
    private NativeToolErrors() {}

    public static ToolExecutor wrap(
            ToolExecutor delegate, ToolService owner, io.agt.core.AgentControl control) {
        return new ToolExecutor() {
            public String execute(ToolExecutionRequest request, Object memoryId) {
                return delegate.execute(request, memoryId);
            }

            public ToolExecutionResult executeWithContext(
                    ToolExecutionRequest request, InvocationContext context) {
                return ToolService.executeWithErrorHandling(
                        request,
                        delegate,
                        context,
                        (error, ctx) -> {
                            rejectGovernance(error);
                            return owner.argumentsErrorHandler().handle(error, ctx);
                        },
                        (error, ctx) -> {
                            rejectGovernance(error);
                            return owner.executionErrorHandler().handle(error, ctx);
                        });
            }

            public CompletableFuture<ToolExecutionResult> executeAsync(
                    ToolExecutionRequest request, InvocationContext context) {
                var session = control.currentSession();
                final CompletableFuture<ToolExecutionResult> source;
                try {
                    source = delegate.executeAsync(request, context);
                } catch (RuntimeException error) {
                    return CompletableFuture.failedFuture(error);
                }
                var result =
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
                                session.checkActive();
                                if (error == null) {
                                    result.complete(value);
                                    return;
                                }
                                var cause = AsyncSources.unwrap(error);
                                try {
                                    rejectGovernance(cause);
                                    if (cause
                                                    instanceof
                                                    dev.langchain4j.exception
                                                            .AsyncNotSupportedException
                                            || cause
                                                    instanceof
                                                    java.util.concurrent.CancellationException)
                                        throw new GovernanceException("tool_async_unsupported");
                                    ToolExecutor failed =
                                            new ToolExecutor() {
                                                public String execute(
                                                        ToolExecutionRequest r, Object id) {
                                                    throw new IllegalStateException(cause);
                                                }

                                                public ToolExecutionResult executeWithContext(
                                                        ToolExecutionRequest r,
                                                        InvocationContext c) {
                                                    if (cause instanceof RuntimeException e)
                                                        throw e;
                                                    throw new IllegalStateException(cause);
                                                }
                                            };
                                    result.complete(
                                            ToolService.executeWithErrorHandling(
                                                    request,
                                                    failed,
                                                    context,
                                                    (e, c) -> {
                                                        rejectGovernance(e);
                                                        return owner.asyncArgumentsErrorHandler()
                                                                .handle(e, c);
                                                    },
                                                    (e, c) -> {
                                                        rejectGovernance(e);
                                                        return owner.asyncExecutionErrorHandler()
                                                                .handle(e, c);
                                                    }));
                                } catch (Throwable failure) {
                                    result.completeExceptionally(failure);
                                }
                            } catch (Throwable failure) {
                                result.completeExceptionally(failure);
                            }
                        });
                return result;
            }
        };
    }

    private static void rejectGovernance(Throwable error) {
        for (int i = 0; error != null && i < 32; i++, error = error.getCause())
            if (error instanceof GovernanceException g) throw g;
    }
}
