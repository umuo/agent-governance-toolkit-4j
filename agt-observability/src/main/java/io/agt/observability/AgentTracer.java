package io.agt.observability;

import io.agt.core.AgentControl;
import io.agt.core.ExecutionContext;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.Identifiers;
import io.agt.core.TrustedContext;
import io.agt.core.audit.AuditSink;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

/** 标准 OTel 链路桥接。实例与 run 由宿主生成，身份和权限仍显式提供。 */
public final class AgentTracer {
    private static final ContextKey<ExecutionContext> RUN = ContextKey.named("agt.execution");
    private static final ContextKey<String> AGENT = ContextKey.named("agt.agent.id");
    private static final ContextKey<String> REMOTE_PARENT_RUN =
            ContextKey.named("agt.remote.parent.run.id");
    private final OpenTelemetry telemetry;

    public AgentTracer(OpenTelemetry telemetry) {
        this.telemetry = java.util.Objects.requireNonNull(telemetry);
    }

    public ExecutionContext currentExecution() {
        var run = Context.current().get(RUN);
        if (run == null) throw new IllegalStateException("No agent execution is bound");
        var span = Span.current().getSpanContext();
        return new ExecutionContext(
                span.getTraceId(),
                span.getSpanId(),
                run.parentSpanId(),
                run.runId(),
                run.parentRunId(),
                run.agentInstanceId());
    }

    public <T> T invokeAgent(String agentId, Supplier<T> task) {
        return invokeAgent(agentId, UUID.randomUUID().toString(), task);
    }

    public <T> T invokeAgent(String agentId, String instanceId, Supplier<T> task) {
        Identifiers.require(agentId);
        Identifiers.require(instanceId);
        var parent = Context.current().get(RUN);
        String parentRunId =
                parent == null ? Context.current().get(REMOTE_PARENT_RUN) : parent.runId();
        String runId = UUID.randomUUID().toString();
        SpanBuilder builder =
                telemetry
                        .getTracer("io.agt.observability")
                        .spanBuilder("invoke_agent " + agentId)
                        .setAttribute("agt.kind", "agent")
                        .setAttribute("agt.agent.id", agentId)
                        .setAttribute("agt.agent.instance.id", instanceId)
                        .setAttribute("agt.run.id", runId);
        if (parentRunId != null) builder.setAttribute("agt.parent.run.id", parentRunId);
        var parentSpan = Span.current().getSpanContext();
        var span = builder.startSpan();
        if (!span.getSpanContext().isValid()
                || (parentSpan.isValid()
                        && span.getSpanContext().getSpanId().equals(parentSpan.getSpanId()))) {
            span.end();
            throw new IllegalStateException("Configure an OpenTelemetry SDK before tracing agents");
        }
        var execution =
                new ExecutionContext(
                        span.getSpanContext().getTraceId(),
                        span.getSpanContext().getSpanId(),
                        parentSpan.isValid() ? parentSpan.getSpanId() : null,
                        runId,
                        parentRunId,
                        instanceId);
        try (var scope =
                Context.current()
                        .with(span)
                        .with(RUN, execution)
                        .with(AGENT, agentId)
                        .makeCurrent()) {
            return finish(span, task);
        } finally {
            span.end();
        }
    }

    /** 在已有 Agent span 下打开独立治理会话，子身份不从父身份推导。 */
    public <T> T governed(
            AgentControl control, TrustedContext identity, Function<HostSession, T> task) {
        try (var session = control.openSession(identity, currentExecution())) {
            return task.apply(session);
        }
    }

    public <T> T operation(String kind, String name, Supplier<T> task) {
        Identifiers.require(kind);
        Identifiers.require(name);
        var run = Context.current().get(RUN);
        if (run == null) throw new IllegalStateException("No agent execution is bound");
        var span =
                telemetry
                        .getTracer("io.agt.observability")
                        .spanBuilder(kind + " " + name)
                        .setAttribute("agt.kind", kind)
                        .setAttribute("agt.agent.id", Context.current().get(AGENT))
                        .setAttribute("agt.run.id", run.runId())
                        .setAttribute("agt.parent.run.id", run.parentRunId())
                        .setAttribute("agt.agent.instance.id", run.agentInstanceId())
                        .startSpan();
        var parentSpan = Span.current().getSpanContext();
        var execution =
                new ExecutionContext(
                        span.getSpanContext().getTraceId(),
                        span.getSpanContext().getSpanId(),
                        parentSpan.getSpanId(),
                        run.runId(),
                        run.parentRunId(),
                        run.agentInstanceId());
        try (var scope = Context.current().with(span).with(RUN, execution).makeCurrent()) {
            return finish(span, task);
        } finally {
            span.end();
        }
    }

    /** 治理异常也可能表示 Provider/执行故障，不能一律标为安全拦截。 */
    public static boolean isDenied(Throwable error) {
        if (!(error instanceof GovernanceException failure)) return false;
        String code = failure.code();
        return code.endsWith("_denied")
                || java.util.Set.of(
                                "tool_not_allowed",
                                "session_revoked",
                                "session_expired",
                                "tool_budget_exceeded",
                                "model_budget_exceeded",
                                "data_budget_exceeded",
                                "approval_timeout",
                                "tool_circuit_open")
                        .contains(code);
    }

    private static <T> T finish(Span span, Supplier<T> task) {
        try {
            T result = task.get();
            span.setAttribute("agt.outcome", "completed");
            return result;
        } catch (RuntimeException | Error error) {
            String code = error instanceof GovernanceException g ? g.code() : "execution_failed";
            span.setStatus(StatusCode.ERROR);
            span.setAttribute("agt.outcome", isDenied(error) ? "blocked" : "error");
            span.setAttribute("agt.reason.code", code);
            // 不自动采集异常消息、堆栈或用户数据。
            throw error;
        }
    }

    public Runnable wrap(Runnable task) {
        return Context.current().wrap(task);
    }

    public <T> Callable<T> wrap(Callable<T> task) {
        return Context.current().wrap(task);
    }

    /** 使审计指向当前工具/模型 span；无活动 scope 时保留会话内的执行关联。 */
    public AuditSink correlate(AuditSink sink) {
        return record -> {
            if (Context.current().get(RUN) != null)
                sink.write(record.withExecution(currentExecution()));
            else sink.write(record);
        };
    }

    /** 标准 traceparent/tracestate 传播；不传播权限或信任传入的 run/Agent 身份。 */
    public void inject(Map<String, String> carrier) {
        var run = Context.current().get(RUN);
        if (run != null) carrier.put("agt-parent-run-id", run.runId());
        else carrier.remove("agt-parent-run-id");
        telemetry
                .getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), carrier, Map::put);
    }

    public Context extract(Map<String, String> carrier) {
        Context extracted =
                telemetry
                        .getPropagators()
                        .getTextMapPropagator()
                        .extract(
                                Context.root(),
                                carrier,
                                new TextMapGetter<Map<String, String>>() {
                                    public Iterable<String> keys(Map<String, String> value) {
                                        return value.keySet();
                                    }

                                    public String get(Map<String, String> value, String key) {
                                        return value.get(key);
                                    }
                                });
        String parentRunId = carrier.get("agt-parent-run-id");
        if (parentRunId != null && Span.fromContext(extracted).getSpanContext().isValid())
            extracted = extracted.with(REMOTE_PARENT_RUN, Identifiers.require(parentRunId));
        return extracted;
    }
}
