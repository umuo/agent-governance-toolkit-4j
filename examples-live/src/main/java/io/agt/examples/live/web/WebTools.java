package io.agt.examples.live.web;

import dev.langchain4j.agent.tool.*;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agt.core.GovernanceException;
import io.agt.examples.live.tools.WorkspaceTools;
import io.agt.langchain4j.LangChain4jGovernance;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** 在受控工具执行路径内发布真实开始、结果、异常事件。 */
final class WebTools {
    private WebTools() {}

    static Map<String, Object> checkedReport(String result) {
        try {
            var report = io.agt.core.JsonValues.parseObject(result);
            if (report.size() == 3
                    && report.get("exitCode") instanceof Number
                    && report.get("stdout") instanceof String
                    && report.get("stderr") instanceof String) {
                return report;
            }
        } catch (RuntimeException ignored) {
            // 输出策略可能将整个结果改写为普通文本，必须尊重改写。
        }
        return null;
    }

    static Map<ToolSpecification, ToolExecutor> register(
            Object object,
            AgentEvents events,
            LangChain4jGovernance governance,
            java.util.concurrent.atomic.AtomicReference<ToolFailure> firstFailure) {
        return register(object, events, governance, firstFailure, null);
    }

    /** 原生服务由 Starter 包装工具，结果事件延迟至受控 after 回调。 */
    static final class NativeRegistration {
        private final AgentEvents events;
        private final java.util.concurrent.atomic.AtomicReference<ToolFailure> failure;
        private final Map<String, java.util.function.Consumer<String>> deliveries =
                new LinkedHashMap<>();

        NativeRegistration(
                AgentEvents events,
                java.util.concurrent.atomic.AtomicReference<ToolFailure> failure) {
            this.events = events;
            this.failure = failure;
        }

        Map<ToolSpecification, ToolExecutor> register(Object object) {
            return WebTools.register(object, events, null, failure, this);
        }

        void after(dev.langchain4j.service.tool.ToolExecution execution) {
            var delivery = deliveries.remove(execution.request().id());
            if (delivery != null) {
                delivery.accept(execution.result());
            }
        }
    }

    private static Map<ToolSpecification, ToolExecutor> register(
            Object object,
            AgentEvents events,
            LangChain4jGovernance governance,
            java.util.concurrent.atomic.AtomicReference<ToolFailure> firstFailure,
            NativeRegistration nativeRegistration) {
        var tools = new LinkedHashMap<ToolSpecification, ToolExecutor>();
        for (var method : object.getClass().getMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            var specification = ToolSpecifications.toolSpecificationFrom(method);
            var executor =
                    DefaultToolExecutor.builder()
                            .object(object)
                            .originalMethod(method)
                            .methodToInvoke(method)
                            .propagateToolExecutionExceptions(true)
                            .build();
            tools.put(
                    specification,
                    (request, memory) -> {
                        var start = new java.util.concurrent.atomic.AtomicLong();
                        var recovered =
                                new java.util.concurrent.atomic.AtomicReference<ToolFailure>();
                        ToolExecutor raw =
                                (effective, id) -> {
                                    start.set(System.nanoTime());
                                    events.send(
                                            "tool_start",
                                            Map.of(
                                                    "id",
                                                    effective.id(),
                                                    "name",
                                                    effective.name(),
                                                    "arguments",
                                                    effective.arguments()));
                                    try {
                                        return executor.execute(effective, id);
                                    } catch (RuntimeException error) {
                                        // 必须在 guardTool 转成 tool_failed 前保存反射执行的根因。
                                        LoggerFactory.getLogger(WebTools.class)
                                                .error(
                                                        "Raw tool {} failed",
                                                        effective.name(),
                                                        error);
                                        var failure = ToolFailure.from(error);
                                        for (Throwable cause = error;
                                                cause != null;
                                                cause = cause.getCause()) {
                                            if (cause instanceof GovernanceException g) {
                                                firstFailure.compareAndSet(null, failure);
                                                throw g;
                                            }
                                        }
                                        // 普通文件错误作为结果继续接受 POST_TOOL_CALL 检查；治理异常仍终止。
                                        if (object instanceof WorkspaceTools
                                                && failure.recoverableFileError()) {
                                            recovered.set(failure);
                                            return failure.result();
                                        }
                                        firstFailure.compareAndSet(null, failure);
                                        throw new GovernanceException(failure.code());
                                    }
                                };
                        var guarded =
                                nativeRegistration == null
                                        ? governance.guardTool(specification.name(), raw)
                                        : raw;
                        try {
                            String rawResult = guarded.execute(request, memory);
                            java.util.function.Consumer<String> deliver =
                                    result -> {
                                        // 只有 POST_TOOL_CALL 检查/改写后，工具结果才允许发送到浏览器。
                                        var recovery = recovered.get();
                                        // 若输出策略改写了结果，只发布改写后的 tool_result，不能泄漏原始错误。
                                        if (recovery != null && result.equals(recovery.result())) {
                                            events.send(
                                                    "tool_error",
                                                    Map.of(
                                                            "id", request.id(),
                                                            "name", request.name(),
                                                            "arguments", request.arguments(),
                                                            "code", recovery.code(),
                                                            "message", recovery.message(),
                                                            "hint", recovery.hint(),
                                                            "checked", true,
                                                            "recoverable", true));
                                            return;
                                        }
                                        if (object instanceof io.agt.examples.live.tools.BashTool) {
                                            var report = checkedReport(result);
                                            if (report != null) {
                                                int exitCode =
                                                        ((Number) report.get("exitCode"))
                                                                .intValue();
                                                String stdout = (String) report.get("stdout");
                                                String stderr = (String) report.get("stderr");
                                                if (exitCode != 0) {
                                                    String message =
                                                            stderr.isBlank()
                                                                    ? "命令失败，但没有捕获到 stderr。若脚本使用 2>/dev/null，错误已被脚本丢弃；可移除该重定向后重新执行。"
                                                                    : stderr;
                                                    events.send(
                                                            "tool_error",
                                                            Map.of(
                                                                    "id",
                                                                    request.id(),
                                                                    "name",
                                                                    request.name(),
                                                                    "arguments",
                                                                    request.arguments(),
                                                                    "code",
                                                                    "bash_command_failed",
                                                                    "message",
                                                                    message,
                                                                    "exitCode",
                                                                    exitCode,
                                                                    "stdout",
                                                                    stdout,
                                                                    "stderr",
                                                                    stderr,
                                                                    "checked",
                                                                    true,
                                                                    "recoverable",
                                                                    true));
                                                    // 普通命令失败是可供模型处理的工具结果，不将会话标记为失败。
                                                    return;
                                                }
                                            }
                                        }
                                        events.send(
                                                "tool_result",
                                                Map.of(
                                                        "id",
                                                        request.id(),
                                                        "name",
                                                        request.name(),
                                                        "result",
                                                        result,
                                                        "durationMs",
                                                        (System.nanoTime() - start.get())
                                                                / 1_000_000));
                                    };
                            if (nativeRegistration == null) {
                                deliver.accept(rawResult);
                            } else {
                                nativeRegistration.deliveries.put(request.id(), deliver);
                            }
                            return rawResult;
                        } catch (RuntimeException error) {
                            LoggerFactory.getLogger(WebTools.class)
                                    .error("Tool {} failed", request.name(), error);
                            var failure = firstFailure.get();
                            if (failure == null) {
                                failure = ToolFailure.from(error);
                                firstFailure.compareAndSet(null, failure);
                            }
                            events.send(
                                    "tool_error",
                                    Map.of(
                                            "id",
                                            request.id(),
                                            "name",
                                            request.name(),
                                            "arguments",
                                            request.arguments(),
                                            "code",
                                            failure.code(),
                                            "message",
                                            failure.message(),
                                            "hint",
                                            failure.hint()));
                            throw error;
                        }
                    });
        }
        return tools;
    }
}
