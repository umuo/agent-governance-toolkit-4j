package io.agt.langchain4j.internal.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.*;
import io.agt.core.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** 原生工具的持久副作用入口；每次重试仍执行正常授权、审批与结果检查。 */
final class GovernedEffectToolExecutor implements ToolExecutor {
    private final AgentControl control;
    private final EffectToolRegistration registration;
    private final ToolCallbacks callbacks;

    GovernedEffectToolExecutor(
            AgentControl control, EffectToolRegistration registration, ToolCallbacks callbacks) {
        this.control = control;
        this.registration = registration;
        this.callbacks = callbacks;
    }

    private String text(Object value) {
        if (!(value instanceof String text))
            throw new GovernanceException("effect_tool_text_required");
        return text;
    }

    private void identity(ToolExecutionRequest request) {
        if (!registration.name().equals(request.name()))
            throw new GovernanceException("tool_identity_mismatch");
    }

    public String execute(ToolExecutionRequest request, Object memory) {
        return executeWithContext(request, null).resultText();
    }

    public ToolExecutionResult executeWithContext(
            ToolExecutionRequest request, InvocationContext context) {
        var session = control.currentSession();
        try {
            identity(request);
            var actual = new AtomicReference<ToolExecutionRequest>();
            Consumer<Object> before =
                    parameters -> {
                        var effective =
                                ToolExecutionRequest.builder()
                                        .id(request.id())
                                        .name(registration.name())
                                        .arguments(JsonValues.write(parameters))
                                        .build();
                        actual.set(effective);
                        callbacks.before(session, effective, context);
                    };
            var value =
                    session.effectOperation(
                            registration.name(),
                            JsonValues.parseObject(request.arguments()),
                            registration.ledger(),
                            registration.intent().apply(session),
                            registration.backend(),
                            before,
                            raw ->
                                    callbacks.rawResult(
                                            session,
                                            actual.get(),
                                            context,
                                            ToolExecutionResult.builder()
                                                    .resultText(text(raw))
                                                    .build()),
                            checked ->
                                    callbacks.after(
                                            session,
                                            actual.get(),
                                            context,
                                            ToolExecutionResult.builder()
                                                    .resultText(text(checked))
                                                    .build()));
            var result = ToolExecutionResult.builder().resultText(text(value)).build();
            return result;
        } catch (RuntimeException failure) {
            throw session.refuse(
                    failure instanceof GovernanceException denied
                            ? denied.code()
                            : "effect_tool_failed");
        }
    }

    public CompletableFuture<ToolExecutionResult> executeAsync(
            ToolExecutionRequest request, InvocationContext context) {
        HostSession captured = null;
        try {
            var session = control.currentSession();
            captured = session;
            identity(request);
            var actual = new AtomicReference<ToolExecutionRequest>();
            var source =
                    session.effectOperationAsync(
                            registration.name(),
                            JsonValues.parseObject(request.arguments()),
                            registration.ledger(),
                            registration.intent().apply(session),
                            registration.backend(),
                            parameters -> {
                                var effective =
                                        ToolExecutionRequest.builder()
                                                .id(request.id())
                                                .name(registration.name())
                                                .arguments(JsonValues.write(parameters))
                                                .build();
                                actual.set(effective);
                                callbacks.before(session, effective, context);
                            },
                            raw ->
                                    callbacks.rawResult(
                                            session,
                                            actual.get(),
                                            context,
                                            ToolExecutionResult.builder()
                                                    .resultText(text(raw))
                                                    .build()),
                            checked ->
                                    callbacks.after(
                                            session,
                                            actual.get(),
                                            context,
                                            ToolExecutionResult.builder()
                                                    .resultText(text(checked))
                                                    .build()));
            var delivered =
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
                            if (error != null) {
                                delivered.completeExceptionally(error);
                                return;
                            }
                            var result =
                                    ToolExecutionResult.builder().resultText(text(value)).build();
                            session.checkActive();
                            delivered.complete(result);
                        } catch (Throwable failure) {
                            session.refuse(
                                    failure instanceof GovernanceException denied
                                            ? denied.code()
                                            : "effect_tool_failed");
                            delivered.completeExceptionally(failure);
                        }
                    });
            return delivered;
        } catch (RuntimeException failure) {
            if (captured != null)
                captured.refuse(
                        failure instanceof GovernanceException denied
                                ? denied.code()
                                : "effect_tool_failed");
            return CompletableFuture.failedFuture(failure);
        }
    }
}
