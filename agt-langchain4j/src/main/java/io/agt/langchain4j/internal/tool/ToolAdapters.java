package io.agt.langchain4j.internal.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.InterventionPoint;
import io.agt.core.execution.FixedCommand;
import java.util.Objects;
import java.util.Set;

/**
 * 工具内部装配入口。
 *
 * <p>不属于稳定公开 API；具体包装实现保持包级可见，治理组件经检查后组装。
 */
public final class ToolAdapters {
    private ToolAdapters() {}

    public static dev.langchain4j.agent.tool.ToolSpecification reviewedSpecification(
            AgentControl control, dev.langchain4j.agent.tool.ToolSpecification specification) {
        if (control.reviewedTool(specification.name()) == null) return specification;
        control.validateToolSpecification(
                specification.name(), io.agt.core.JsonValues.parseObject(specification.toJson()));
        return control.reviewedTool(specification.name()) == null
                ? specification
                : dev.langchain4j.agent.tool.ToolSpecification.fromJson(specification.toJson());
    }

    public static void validateModelTools(
            HostSession session, dev.langchain4j.model.chat.request.ChatRequest request) {
        var tools = request.parameters().toolSpecifications();
        if (tools == null) return;
        var names = new java.util.HashSet<String>();
        for (var specification : tools) {
            if (!session.requiresToolVerification(specification.name())) continue;
            if (names.size() >= 128) throw new GovernanceException("tool_catalog_capacity");
            if (!names.add(specification.name())) throw new GovernanceException("duplicate_tool");
            session.validateToolSpecification(
                    specification.name(),
                    io.agt.core.JsonValues.parseObject(specification.toJson()));
        }
    }

    public static ToolExecutor guard(AgentControl control, String name, ToolExecutor executor) {
        return guard(control, name, executor, false);
    }

    public static ToolExecutor guard(
            AgentControl control, String name, ToolExecutor executor, boolean typed) {
        return guard(control, name, executor, typed, ToolCallbacks.NONE);
    }

    public static ToolExecutor guard(
            AgentControl control,
            String name,
            ToolExecutor executor,
            boolean typed,
            ToolCallbacks callbacks) {
        control.requirePoints(
                Set.of(InterventionPoint.PRE_TOOL_CALL, InterventionPoint.POST_TOOL_CALL));
        if (executor instanceof GovernedToolExecutor
                || executor instanceof GovernedEffectToolExecutor) {
            throw new GovernanceException("already_governed");
        }
        if (executor instanceof EffectToolRegistration effect) {
            control.reviewedTool(name);
            if (!name.equals(effect.name()))
                throw new GovernanceException("tool_identity_mismatch");
            return new GovernedEffectToolExecutor(control, effect, callbacks);
        }
        if (executor instanceof CommandRegistration registration) {
            if (!name.equals(registration.command().name())) {
                throw new GovernanceException("command_tool_name_mismatch");
            }
            executor = new CommandToolExecutor(control, registration.command());
        }
        return new GovernedToolExecutor(
                control, name, Objects.requireNonNull(executor), typed, callbacks);
    }

    public static ToolExecutor command(AgentControl control, FixedCommand command) {
        Objects.requireNonNull(command);
        return guard(control, command.name(), registration(command));
    }

    /** 仅供 Builder 延迟装配；注册占位符不能直接执行真实命令。 */
    public static ToolExecutor registration(FixedCommand command) {
        return new CommandRegistration(Objects.requireNonNull(command));
    }

    public static ToolExecutor effectRegistration(
            String name,
            io.agt.core.effects.EffectLedger ledger,
            java.util.function.Function<HostSession, io.agt.core.effects.EffectIntent> intent,
            io.agt.core.effects.EffectBackend backend) {
        return new EffectToolRegistration(name, ledger, intent, backend);
    }

    /** 固定命令注册描述；真实执行器只在 guard 内部建立。 */
    private record CommandRegistration(FixedCommand command) implements ToolExecutor {
        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            throw new GovernanceException("command_registration_not_executable");
        }
    }
}
