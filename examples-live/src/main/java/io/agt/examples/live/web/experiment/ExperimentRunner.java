package io.agt.examples.live.web.experiment;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.approval.ApprovalResponse;
import io.agt.examples.live.web.WebAgentRuntimeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;

/** 共用接线：原生与 Starter 两条路径分别创建业务状态，真实框架负责调用工具。 网页运行始终使用已配置的真实模型；所有证据来自实际执行，不预设模型是否请求工具。 */
public abstract class ExperimentRunner {
    public interface Assistant {
        String chat(String message);
    }

    public record Audit(
            String point, String verdict, String reason, String approval, String execution) {}

    public record Observation(
            String status,
            String code,
            String answer,
            int modelCalls,
            Map<String, Object> before,
            Map<String, Object> after,
            List<ExperimentCase.Call> requestedCalls,
            List<Audit> audit,
            List<ExperimentTrace.Step> trace) {}

    public record Comparison(
            String caseId, String approval, Observation original, Observation guarded) {}

    private final WebAgentRuntimeFactory runtimes;
    private final ObjectProvider<ChatModel> liveModels;

    protected ExperimentRunner(
            WebAgentRuntimeFactory runtimes, ObjectProvider<ChatModel> liveModels) {
        this.runtimes = runtimes;
        this.liveModels = liveModels;
    }

    protected abstract void validate(String id);

    protected abstract ExperimentCase create(String id, boolean guarded) throws Exception;

    protected abstract String systemMessage();

    /** 个别教学案例可显式使用脆弱 Agent 配置；两条路径仍接收相同提示。 */
    protected String systemMessage(String caseId) {
        return systemMessage();
    }

    public Comparison compare(String id, String message, String approval) {
        validate(id);
        return new Comparison(
                id, approval, run(id, message, approval, false), run(id, message, approval, true));
    }

    private Observation run(String id, String message, String approval, boolean guarded) {
        var trace = new ExperimentTrace();
        try (var scope = trace.bind()) {
            trace.add(
                    "experiment_request",
                    Map.of(
                            "caseId",
                            id,
                            "message",
                            message,
                            "approval",
                            approval,
                            "guarded",
                            guarded));
            var result = runObserved(id, message, approval, guarded, trace);
            trace.add(
                    "run_end",
                    Map.of(
                            "status",
                            result.status(),
                            "code",
                            result.code(),
                            "answer",
                            result.answer(),
                            "after",
                            result.after()));
            return new Observation(
                    result.status(),
                    result.code(),
                    result.answer(),
                    result.modelCalls(),
                    result.before(),
                    result.after(),
                    result.requestedCalls(),
                    result.audit(),
                    trace.snapshot());
        }
    }

    private Observation runObserved(
            String id, String message, String approval, boolean guarded, ExperimentTrace trace) {
        var audits = new ArrayList<Audit>();
        var calls = new ArrayList<ExperimentCase.Call>();
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        Map<String, Object> before = Map.of();
        ExperimentCase scenario = null;
        try (var resource = create(id, guarded)) {
            scenario = resource;
            before = scenario.snapshot();
            try {
                ChatModel delegate = liveModels.getObject();
                ChatModel model =
                        new ChatModel() {
                            @Override
                            public ChatResponse doChat(ChatRequest request) {
                                trace.beginRound(modelCalls.incrementAndGet(), request);
                                ChatResponse response;
                                try {
                                    response = delegate.chat(request);
                                } catch (RuntimeException error) {
                                    trace.add(
                                            "model_error",
                                            Map.of("code", "provider_request_failed"));
                                    throw error;
                                }
                                trace.add("model_response", response);
                                response.aiMessage()
                                        .toolExecutionRequests()
                                        .forEach(
                                                call ->
                                                        calls.add(
                                                                new ExperimentCase.Call(
                                                                        call.name(),
                                                                        call.arguments())));
                                return response;
                            }
                        };
                var tools = scenario;
                Consumer<AiServices<Assistant>> configure =
                        builder ->
                                builder.chatModel(model)
                                        .systemMessageProvider(ignored -> systemMessage(id))
                                        .tools(tools)
                                        .beforeToolExecution(
                                                event -> trace.add("tool_start", event.request()))
                                        .afterToolExecution(
                                                event ->
                                                        trace.add(
                                                                "tool_result",
                                                                Map.of(
                                                                        "request",
                                                                        event.request(),
                                                                        "result",
                                                                        event.result(),
                                                                        "failed",
                                                                        event.hasFailed(),
                                                                        "state",
                                                                        tools.snapshot())))
                                        .maxSequentialToolsInvocations(12)
                                        .toolExecutionErrorHandler(
                                                (error, context) -> {
                                                    trace.add(
                                                            "tool_error",
                                                            Map.of(
                                                                    "request",
                                                                    context.toolExecutionRequest(),
                                                                    "code",
                                                                    error
                                                                                    instanceof
                                                                                    GovernanceException
                                                                                                    failure
                                                                            ? failure.code()
                                                                            : "tool_failed",
                                                                    "state",
                                                                    tools.snapshot()));
                                                    if (error instanceof RuntimeException failure) {
                                                        throw failure;
                                                    }
                                                    throw new GovernanceException("tool_failed");
                                                });
                if (!guarded) {
                    var builder = AiServices.builder(Assistant.class);
                    configure.accept(builder);
                    return observation(
                            "done",
                            "",
                            builder.build().chat(message),
                            modelCalls.get(),
                            before,
                            scenario,
                            calls,
                            audits);
                }
                var approvalUsed = new java.util.concurrent.atomic.AtomicBoolean();
                try (var runtime =
                                runtimes.open(
                                        scenario.policy(),
                                        scenario.identity(),
                                        record -> {
                                            var audit =
                                                    new Audit(
                                                            record.point().wireName(),
                                                            record.verdict(),
                                                            record.reasonCode(),
                                                            record.approvalOutcome(),
                                                            record.executionOutcome());
                                            audits.add(audit);
                                            trace.add(
                                                    "governance",
                                                    Map.of(
                                                            "audit",
                                                            audit,
                                                            "actionId",
                                                            record.actionId()));
                                        },
                                        request ->
                                                CompletableFuture.completedFuture(
                                                        approval.equals("allow")
                                                                        && approvalUsed
                                                                                .compareAndSet(
                                                                                        false, true)
                                                                ? ApprovalResponse.allow(request)
                                                                : ApprovalResponse.deny(request)));
                        var session = runtime.control().openSession(scenario.identity())) {
                    scenario.attach(runtime.control());
                    try {
                        var assistant = runtime.services().create(Assistant.class, configure);
                        String answer = assistant.chat(message);
                        return observation(
                                "done",
                                "",
                                answer,
                                modelCalls.get(),
                                before,
                                scenario,
                                calls,
                                audits);
                    } catch (Exception error) {
                        return failed(
                                error, session, modelCalls.get(), before, scenario, calls, audits);
                    }
                }
            } catch (Exception error) {
                return failed(error, null, modelCalls.get(), before, scenario, calls, audits);
            }
        } catch (Exception error) {
            return new Observation(
                    "error",
                    "case_setup_failed",
                    "实验资源初始化或清理失败。",
                    modelCalls.get(),
                    before,
                    Map.of(),
                    List.copyOf(calls),
                    List.copyOf(audits),
                    List.of());
        }
    }

    private static Observation failed(
            Exception error,
            HostSession session,
            int modelCalls,
            Map<String, Object> before,
            ExperimentCase scenario,
            List<ExperimentCase.Call> calls,
            List<Audit> audits) {
        String code =
                session != null && session.failureCode() != null
                        ? session.failureCode()
                        : error instanceof GovernanceException denied
                                ? denied.code()
                                : "demo_failed";
        boolean unavailable =
                List.of(
                                "demo_platform_unavailable",
                                "sandbox_unavailable",
                                "sandbox_policy_unsupported",
                                "sandbox_cleanup_unconfirmed")
                        .contains(code);
        boolean blocked =
                java.util.Set.of(
                                "permission_denied",
                                "tool_not_allowed",
                                "tool_arguments_denied",
                                "context_binding_denied",
                                "tool_budget_exceeded",
                                "model_budget_exceeded",
                                "session_expired",
                                "approval_denied",
                                "approval_timeout",
                                "late_result_denied",
                                "tool_circuit_open",
                                "sandbox_exit_denied",
                                "command_integrity_denied",
                                "command_arguments_denied",
                                "sandbox_timeout",
                                "sandbox_output_limit",
                                "artifact_integrity_denied",
                                "plugin_manifest_denied",
                                "plugin_publisher_denied",
                                "evidence_mismatch_denied",
                                "agent_audience_denied",
                                "agent_signature_denied",
                                "agent_replay_denied",
                                "dependency_failed",
                                "memory_poison_denied",
                                "retrieval_poison_denied",
                                "session_revoked",
                                "data_permission_denied",
                                "data_tenant_denied",
                                "data_owner_denied",
                                "data_operation_denied")
                        .contains(code);
        String status = unavailable ? "unavailable" : blocked ? "blocked" : "error";
        // 不回传 Provider 的原始异常，以免响应中携带密钥或连接配置。
        String answer =
                unavailable
                        ? "当前环境无法完成真实隔离验证，不能视为防护成功。"
                        : status.equals("blocked")
                                ? "本次调用被拒绝，请同时检查业务状态是否已经改变。"
                                : "实验运行失败。真实模型模式请检查模型配置和工具调用支持。";
        return observation(status, code, answer, modelCalls, before, scenario, calls, audits);
    }

    private static Observation observation(
            String status,
            String code,
            String answer,
            int modelCalls,
            Map<String, Object> before,
            ExperimentCase scenario,
            List<ExperimentCase.Call> calls,
            List<Audit> audits) {
        return new Observation(
                status,
                code,
                answer,
                modelCalls,
                before,
                scenario == null ? Map.of() : scenario.snapshot(),
                List.copyOf(calls),
                List.copyOf(audits),
                List.of());
    }
}
