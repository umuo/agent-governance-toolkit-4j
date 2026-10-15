package io.agt.examples.live.web;

import dev.langchain4j.agent.tool.*;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agt.core.GovernanceException;
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
                        var guarded =
                                governance.guardTool(
                                        specification.name(),
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
                                                firstFailure.compareAndSet(null, failure);
                                                for (Throwable cause = error;
                                                        cause != null;
                                                        cause = cause.getCause()) {
                                                    if (cause instanceof GovernanceException g) {
                                                        throw g;
                                                    }
                                                }
                                                throw new GovernanceException(failure.code());
                                            }
                                        });
                        try {
                            String result = guarded.execute(request, memory);
                            // 只有 POST_TOOL_CALL 检查/改写后，工具结果才允许发送到浏览器。
                            if (object instanceof io.agt.examples.live.tools.BashTool) {
                                var report = checkedReport(result);
                                if (report != null) {
                                    int exitCode = ((Number) report.get("exitCode")).intValue();
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
                                        return result;
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
                                            (System.nanoTime() - start.get()) / 1_000_000));
                            return result;
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
