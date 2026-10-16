package io.agt.langchain4j.internal.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.tool.*;
import io.agt.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 原生补偿回执保留在内部，每个补偿使用独立工具名称重新授权。 */
final class CompensationRegistry {
    private final AgentControl control;
    private volatile boolean enabled;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private static final class State {
        final Map<String, ToolExecution> receipts = new ConcurrentHashMap<>();
        final List<Action> actions = new ArrayList<>();
        final Set<String> compensated = new HashSet<>();
    }

    private record Action(ToolExecution receipt, Consumer<ToolExecution> execute) {}

    CompensationRegistry(AgentControl control) {
        this.control = control;
    }

    boolean enabled() {
        return enabled;
    }

    void enabled(boolean value) {
        enabled = value;
    }

    void clear(InvocationContext context) {
        if (context != null) states.remove(context.invocationId());
    }

    void receipt(
            HostSession session,
            ToolExecutionRequest request,
            InvocationContext context,
            ToolExecutionResult result) {
        if (!enabled || context == null || result.isError()) return;
        var state =
                states.computeIfAbsent(
                        context.invocationId(),
                        id -> {
                            var value = new State();
                            var terminal = new CompletableFuture<Void>();
                            terminal.whenComplete((v, e) -> states.remove(id, value));
                            session.watchTermination(terminal);
                            return value;
                        });
        state.receipts.put(
                request.id(),
                ToolExecution.builder()
                        .request(request)
                        .result(result)
                        .invocationContext(context)
                        .build());
    }

    void record(ToolExecution checked, Consumer<ToolExecution> execute) {
        if (!enabled) return;
        var state = states.get(checked.invocationContext().invocationId());
        if (state == null) return;
        var raw = state.receipts.remove(checked.request().id());
        if (raw == null) throw control.currentSession().refuse("compensation_receipt_missing");
        synchronized (state) {
            state.actions.add(new Action(raw, execute));
        }
    }

    void compensate(
            InvocationContext context,
            dev.langchain4j.observability.api.AiServiceListenerRegistrar registrar) {
        if (!enabled) return;
        var state = states.get(context.invocationId());
        if (state == null) return;
        synchronized (state) {
            for (int i = state.actions.size() - 1; i >= 0; i--) {
                var action = state.actions.get(i);
                var receipt = action.receipt();
                var target = new LinkedHashMap<String, Object>();
                target.put("arguments", JsonValues.parseObject(receipt.request().arguments()));
                // 空字段不进入动作参数；可选字段可省略，策略仍可通过 required 拒绝缺失。
                if (receipt.resultObject() != null)
                    target.put("receipt", TypedValues.project(receipt.resultObject()));
                if (receipt.result() != null) target.put("text", receipt.result());
                control.currentSession()
                        .operationPrepared(
                                true,
                                "compensate_" + receipt.request().name(),
                                target,
                                effective -> {
                                    if (!TypedValues.equivalent(target, effective))
                                        throw new GovernanceException(
                                                "compensation_target_changed");
                                    return receipt;
                                },
                                original -> {
                                    action.execute().accept(original);
                                    return "compensated_due_to_tool_error";
                                },
                                value -> value,
                                (original, effective) ->
                                        io.agt.langchain4j.internal.message.TextProjection
                                                .requiredString(effective));
                state.compensated.add(receipt.request().id());
                state.actions.remove(i);
                registrar.fireEvent(
                        dev.langchain4j.observability.api.event.ToolCompensatedEvent.builder()
                                .request(receipt.request())
                                .resultText("compensated_due_to_tool_error")
                                .reason(
                                        dev.langchain4j.observability.api.event.CompensationReason
                                                .TOOL_EXECUTION_FAILED)
                                .invocationContext(context)
                                .build());
            }
        }
    }

    List<ToolExecutionResultMessage> rewrite(
            InvocationContext context, List<ToolExecutionResultMessage> messages) {
        var state = states.get(context.invocationId());
        if (state == null) return messages;
        synchronized (state) {
            return messages.stream()
                    .map(m -> state.compensated.contains(m.id()) ? replacement(m) : m)
                    .toList();
        }
    }

    void rewriteHistory(InvocationContext context, ChatMemory memory, List<ChatMessage> messages) {
        var state = states.get(context.invocationId());
        if (state == null) return;
        synchronized (state) {
            for (int i = 0; i < messages.size(); i++)
                if (messages.get(i) instanceof ToolExecutionResultMessage m
                        && state.compensated.contains(m.id())) messages.set(i, replacement(m));
            if (memory != null
                    && memory.messages().stream()
                            .anyMatch(
                                    m ->
                                            m instanceof ToolExecutionResultMessage t
                                                    && state.compensated.contains(t.id()))) {
                var prior = memory.messages();
                memory.clear();
                for (var message : prior)
                    memory.add(
                            message instanceof ToolExecutionResultMessage m
                                            && state.compensated.contains(m.id())
                                    ? replacement(m)
                                    : message);
            }
        }
    }

    private static ToolExecutionResultMessage replacement(ToolExecutionResultMessage original) {
        return ToolExecutionResultMessage.builder()
                .id(original.id())
                .toolName(original.toolName())
                .text("compensated_due_to_tool_error")
                .isError(true)
                .build();
    }
}
