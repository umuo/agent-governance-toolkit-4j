package io.agt.delegation;

import io.agt.core.*;
import java.math.BigInteger;
import java.util.*;

/** 框架无关的接收端协议处理器；调用方必须先用宿主认证系统解析身份，不暴露签发/根权限。 */
public final class GrantStatusService {
    private final GrantRegistry registry;

    public GrantStatusService(GrantRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public record Response(int status, Map<String, Object> body) {
        public Response {
            body = Map.copyOf(body);
        }

        @Override
        public String toString() {
            return "GrantStatusResponse[redacted]";
        }
    }

    public Response handle(AuthenticatedAgent caller, String json) {
        Objects.requireNonNull(caller);
        Map<String, Object> body;
        GrantLease lease;
        String op;
        try {
            body = JsonValues.parseObject(json);
            op = (String) body.get("op");
            var expected = new HashSet<>(Set.of("op", "lease"));
            expected.addAll(
                    switch (op) {
                        case "claim" -> Set.of("grantId", "digest", "agent", "audience");
                        case "bind",
                                        "check",
                                        "complete",
                                        "usage_snapshot",
                                        "flow_snapshot",
                                        "work_status" ->
                                Set.of("sessionId");
                        case "guarded_commit" ->
                                Set.of(
                                        "sessionId",
                                        "actionId",
                                        "point",
                                        "tool",
                                        "projection",
                                        "digest");
                        case "flow_validate" -> Set.of("sessionId", "point", "tool", "projection");
                        case "flow_validate_action" ->
                                Set.of("sessionId", "actionId", "point", "tool", "projection");
                        case "flow_complete" -> Set.of("sessionId", "actionId", "tool");
                        case "reviewed_commit" ->
                                Set.of(
                                        "sessionId",
                                        "actionId",
                                        "tool",
                                        "binding",
                                        "projection",
                                        "digest");
                        case "commit" -> Set.of("sessionId", "actionId", "kind", "digest");
                        case "work_register" -> Set.of("sessionId", "executionId", "kind");
                        case "work_start", "work_cancel" -> Set.of("sessionId", "executionId");
                        case "work_finish" -> Set.of("sessionId", "executionId", "evidence");
                        case "work_page" -> Set.of("sessionId", "revision", "offset");
                        case "usage_reserve" ->
                                Set.of("sessionId", "reservationId", "model", "input", "output");
                        case "usage_dispatch", "usage_close" ->
                                Set.of("sessionId", "reservationId");
                        case "usage_settle" ->
                                Set.of(
                                        "sessionId",
                                        "reservationId",
                                        "model",
                                        "input",
                                        "output",
                                        "total");
                        default -> throw new IllegalArgumentException();
                    });
            if (!body.keySet().equals(expected)
                    || !(body.get("lease") instanceof Map<?, ?> value)
                    || !value.keySet().equals(Set.of("grantId", "secret")))
                throw new IllegalArgumentException();
            lease = new GrantLease((String) value.get("grantId"), (String) value.get("secret"));
        } catch (RuntimeException invalid) {
            return denied(400, "delegation_request_denied");
        }
        try {
            if (!"claim".equals(op)) registry.authorizeCaller(lease, caller.agentId());
            String session = (String) body.get("sessionId");
            String id = (String) body.get("reservationId");
            Map<String, Object> result = Map.of("ok", true);
            switch (op) {
                case "claim" -> {
                    if (!caller.agentId().equals(body.get("agent")))
                        throw new GovernanceException("delegation_actor_denied");
                    var document =
                            registry.claim(
                                    (String) body.get("grantId"),
                                    (String) body.get("digest"),
                                    caller.agentId(),
                                    (String) body.get("audience"),
                                    lease);
                    result = Map.of("document", GrantCodec.encode(document));
                }
                case "bind" -> registry.bind(lease, session);
                case "check" -> registry.check(lease, session);
                case "complete" -> registry.complete(lease, session);
                case "work_register" ->
                        registry.workRegister(
                                lease,
                                session,
                                (String) body.get("executionId"),
                                (String) body.get("kind"));
                case "work_start" ->
                        registry.workStart(lease, session, (String) body.get("executionId"));
                case "work_cancel" ->
                        registry.workCancel(lease, session, (String) body.get("executionId"));
                case "work_finish" ->
                        registry.workFinish(
                                lease,
                                session,
                                (String) body.get("executionId"),
                                io.agt.core.execution.TerminationEvidence.valueOf(
                                        (String) body.get("evidence")));
                case "work_status" ->
                        result =
                                Map.of(
                                        "summary",
                                        ExternalWorkCodec.encode(
                                                registry.workStatus(lease, session)));
                case "work_page" ->
                        result =
                                Map.of(
                                        "page",
                                        ExternalWorkCodec.encode(
                                                registry.workPage(
                                                        lease,
                                                        session,
                                                        ((java.math.BigInteger)
                                                                        body.get("revision"))
                                                                .longValueExact(),
                                                        ((java.math.BigInteger) body.get("offset"))
                                                                .intValueExact())));
                case "guarded_commit" ->
                        registry.guardedCommit(
                                lease,
                                session,
                                (String) body.get("actionId"),
                                InterventionPoint.valueOf((String) body.get("point")),
                                (String) body.get("tool"),
                                body.get("projection"),
                                (String) body.get("digest"));
                case "flow_validate" ->
                        registry.flowValidate(
                                lease,
                                session,
                                InterventionPoint.valueOf((String) body.get("point")),
                                (String) body.get("tool"),
                                body.get("projection"));
                case "flow_complete" ->
                        registry.flowComplete(
                                lease,
                                session,
                                (String) body.get("actionId"),
                                (String) body.get("tool"));
                case "flow_validate_action" ->
                        registry.flowValidate(
                                lease,
                                session,
                                (String) body.get("actionId"),
                                InterventionPoint.valueOf((String) body.get("point")),
                                (String) body.get("tool"),
                                body.get("projection"));
                case "flow_snapshot" ->
                        result =
                                Map.of(
                                        "snapshot",
                                        GrantCodec.encodeFlowSnapshot(
                                                registry.flowSnapshot(lease, session)));
                case "reviewed_commit" ->
                        registry.reviewedCommit(
                                lease,
                                session,
                                (String) body.get("actionId"),
                                (String) body.get("tool"),
                                (String) body.get("binding"),
                                body.get("projection"),
                                (String) body.get("digest"));
                case "commit" ->
                        registry.commit(
                                lease,
                                session,
                                (String) body.get("actionId"),
                                (String) body.get("kind"),
                                (String) body.get("digest"));
                case "usage_reserve" ->
                        registry.usageReserve(
                                lease,
                                session,
                                id,
                                (String) body.get("model"),
                                number(body.get("input")),
                                number(body.get("output")));
                case "usage_dispatch" -> registry.usageDispatch(lease, session, id);
                case "usage_settle" ->
                        registry.usageSettle(
                                lease,
                                session,
                                id,
                                (String) body.get("model"),
                                number(body.get("input")),
                                number(body.get("output")),
                                number(body.get("total")));
                case "usage_close" -> registry.usageClose(lease, session, id);
                case "usage_snapshot" ->
                        result =
                                Map.of(
                                        "snapshot",
                                        GrantCodec.encodeUsageSnapshot(
                                                registry.usageSnapshot(lease, session)));
                default -> throw new IllegalArgumentException();
            }
            return new Response(200, result);
        } catch (GovernanceException denied) {
            return denied(403, denied.code());
        } catch (IllegalArgumentException
                | ClassCastException
                | NullPointerException
                | ArithmeticException invalid) {
            return denied(400, "delegation_request_denied");
        } catch (RuntimeException unavailable) {
            return denied(503, "delegation_registry_unavailable");
        }
    }

    private static Integer number(Object value) {
        return value == null ? null : ((BigInteger) value).intValueExact();
    }

    private static Response denied(int status, String code) {
        return new Response(status, Map.of("code", code));
    }
}
