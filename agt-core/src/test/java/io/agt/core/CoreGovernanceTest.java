package io.agt.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agt.core.approval.ApprovalResponse;
import io.agt.core.audit.AuditRecord;
import io.agt.core.audit.JsonlAuditSink;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 核心治理功能的单元测试。
 *
 * <p>验证会话生命周期、参数校验、改写、审批流、预算控制及审计记录派发等核心能力。
 */
class CoreGovernanceTest {
    @TempDir Path temporary;

    static final TrustedContext CONTEXT =
            new TrustedContext("agent", "tenant-a", "user-a", Set.of("read"));
    static final ParameterRule PARAMS =
            ParameterRule.object(Map.of("tenant", ParameterRule.string("tenantId")), "tenant");

    static StructuredPolicy policy(boolean approval) {
        return StructuredPolicy.builder("v1")
                .tool("lookup", new ToolRule(Set.of("read"), PARAMS, approval))
                .build();
    }

    static AgentControl runtime(PolicyEvaluator p, List<AuditRecord> audit) {
        return AgentControl.builder(p).audit(audit::add).build();
    }

    static Object tool(HostSession s, Object input, AtomicInteger effects) {
        return s.operation(
                true,
                "lookup",
                input,
                v -> {
                    effects.incrementAndGet();
                    return "ok";
                },
                v -> v,
                (v, e) -> (String) e);
    }

    @Test
    void fullLifecycleAndNoDuplicateCount() {
        var audit = new ArrayList<AuditRecord>();
        try (var c = runtime(policy(false), audit);
                var s = c.openSession(CONTEXT)) {
            assertEquals(
                    "ok",
                    s.run(
                            "hi",
                            v -> tool(s, Map.of("tenant", "tenant-a"), new AtomicInteger()),
                            v -> v,
                            (v, e) -> (String) e));
            assertEquals(1, s.budgetSnapshot().toolCalls());
        }
        var points =
                audit.stream()
                        .filter(r -> !r.verdict().equals("EXECUTION"))
                        .map(AuditRecord::point)
                        .toList();
        assertEquals(
                List.of(
                        InterventionPoint.AGENT_STARTUP,
                        InterventionPoint.INPUT,
                        InterventionPoint.PRE_TOOL_CALL,
                        InterventionPoint.POST_TOOL_CALL,
                        InterventionPoint.OUTPUT,
                        InterventionPoint.AGENT_SHUTDOWN),
                points);
        assertTrue(
                audit.stream()
                        .filter(r -> r.point() == InterventionPoint.PRE_TOOL_CALL)
                        .allMatch(r -> r.inputIdentity().startsWith("sha256:")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"tenant\":\"tenant-b\"}",
                "{}",
                "{\"tenant\":1}",
                "{\"tenant\":\"tenant-a\",\"extra\":1}"
            })
    void deniesArgumentsBeforeEffect(String json) {
        var effects = new AtomicInteger();
        try (var c = runtime(policy(false), new ArrayList<>());
                var s = c.openSession(CONTEXT)) {
            assertThrows(
                    GovernanceException.class,
                    () -> tool(s, JsonValues.parseObject(json), effects));
            assertEquals(0, effects.get());
            assertEquals(0, s.budgetSnapshot().toolCalls());
            assertEquals(
                    "session_failed",
                    assertThrows(GovernanceException.class, s::checkActive).code());
        }
    }

    @Test
    void missingPermission() {
        try (var c = runtime(policy(false), new ArrayList<>());
                var s = c.openSession(new TrustedContext("agent", "tenant-a", "u", Set.of()))) {
            assertEquals(
                    "permission_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            tool(
                                                    s,
                                                    Map.of("tenant", "tenant-a"),
                                                    new AtomicInteger()))
                            .code());
        }
    }

    @Test
    void transformRevalidatedAndApplied() {
        var p =
                StructuredPolicy.builder("v1")
                        .tool("lookup", new ToolRule(Set.of(), PARAMS, false))
                        .content(
                                new ContentRule(
                                        "redact",
                                        Set.of(InterventionPoint.POST_TOOL_CALL),
                                        List.of(),
                                        Map.of("secret", "masked")))
                        .build();
        var audit = new ArrayList<AuditRecord>();
        try (var c = runtime(p, audit);
                var s = c.openSession(CONTEXT)) {
            Object result =
                    s.operation(
                            true,
                            "lookup",
                            Map.of("tenant", "tenant-a"),
                            v -> "secret",
                            v -> v,
                            (v, e) -> (String) e);
            assertEquals("masked", result);
        }
        assertTrue(
                audit.stream()
                        .anyMatch(
                                r ->
                                        r.transformApplied()
                                                && !r.inputIdentity()
                                                        .equals(r.enforcedIdentity())));
    }

    @Test
    void transformCannotChangeTenant() {
        var p =
                StructuredPolicy.builder("v1")
                        .tool("lookup", new ToolRule(Set.of(), PARAMS, false))
                        .content(
                                new ContentRule(
                                        "wrong_tenant",
                                        Set.of(InterventionPoint.PRE_TOOL_CALL),
                                        List.of(),
                                        Map.of("tenant-a", "tenant-b")))
                        .build();
        try (var c = runtime(p, new ArrayList<>());
                var s = c.openSession(CONTEXT)) {
            var n = new AtomicInteger();
            assertThrows(GovernanceException.class, () -> tool(s, Map.of("tenant", "tenant-a"), n));
            assertEquals(0, n.get());
        }
    }

    @Test
    void evaluateOnlyDoesNotBlockOrTransform() {
        var p =
                StructuredPolicy.builder("v1")
                        .content(
                                new ContentRule(
                                        "mask",
                                        Set.of(InterventionPoint.INPUT),
                                        List.of(),
                                        Map.of("secret", "mask")))
                        .build();
        var audit = new ArrayList<AuditRecord>();
        try (var c =
                        AgentControl.builder(p)
                                .mode(EnforcementMode.EVALUATE_ONLY)
                                .audit(audit::add)
                                .build();
                var s = c.openSession(CONTEXT)) {
            assertEquals("secret", s.run("secret", v -> v, v -> v, (v, e) -> (String) e));
            assertEquals("ok", tool(s, Map.of(), new AtomicInteger()));
        }
        assertTrue(
                audit.stream()
                        .anyMatch(r -> r.verdict().equals("TRANSFORM") && !r.transformApplied()));
        assertTrue(audit.stream().anyMatch(r -> r.verdict().equals("DENY")));
    }

    @Test
    void approvalAllowDenyAndMissing() {
        for (int mode = 0; mode < 3; mode++) {
            var n = new AtomicInteger();
            final int outcome = mode;
            var b = AgentControl.builder(policy(true)).audit(r -> {});
            if (mode != 2) {
                b.approvalResolver(
                        r ->
                                CompletableFuture.completedFuture(
                                        outcome == 0
                                                ? ApprovalResponse.allow(r)
                                                : ApprovalResponse.deny(r)));
            }
            try (var c = b.build();
                    var s = c.openSession(CONTEXT)) {
                if (mode == 0) {
                    assertEquals("ok", tool(s, Map.of("tenant", "tenant-a"), n));
                } else {
                    assertThrows(
                            GovernanceException.class,
                            () -> tool(s, Map.of("tenant", "tenant-a"), n));
                }
                assertEquals(mode == 0 ? 1 : 0, n.get());
            }
        }
    }

    @Test
    void approvalTimeoutIncludesBlockingResolver() {
        try (var c =
                        AgentControl.builder(policy(true))
                                .audit(r -> {})
                                .approvalTimeout(Duration.ofMillis(40))
                                .approvalResolver(
                                        r -> {
                                            try {
                                                Thread.sleep(10_000);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            return CompletableFuture.completedFuture(
                                                    ApprovalResponse.allow(r));
                                        })
                                .build();
                var s = c.openSession(CONTEXT)) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () ->
                            assertEquals(
                                    "approval_timeout",
                                    assertThrows(
                                                    GovernanceException.class,
                                                    () ->
                                                            tool(
                                                                    s,
                                                                    Map.of("tenant", "tenant-a"),
                                                                    new AtomicInteger()))
                                            .code()));
        }
    }

    @Test
    void approvalExceptionAndIdentityMismatch() {
        for (boolean exception : List.of(true, false)) {
            try (var c =
                            AgentControl.builder(policy(true))
                                    .audit(r -> {})
                                    .approvalResolver(
                                            r -> {
                                                if (exception) {
                                                    throw new IllegalStateException("secret");
                                                }
                                                return CompletableFuture.completedFuture(
                                                        new ApprovalResponse(
                                                                r.requestId(), "wrong", true));
                                            })
                                    .build();
                    var s = c.openSession(CONTEXT)) {
                assertEquals(
                        exception ? "approval_failed" : "approval_identity_mismatch",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                tool(
                                                        s,
                                                        Map.of("tenant", "tenant-a"),
                                                        new AtomicInteger()))
                                .code());
            }
        }
    }

    @Test
    void approvalResponseCannotBeReplayedAcrossActions() {
        var response = new AtomicReference<ApprovalResponse>();
        try (var c =
                        AgentControl.builder(policy(true))
                                .audit(r -> {})
                                .approvalResolver(
                                        r -> {
                                            response.compareAndSet(null, ApprovalResponse.allow(r));
                                            return CompletableFuture.completedFuture(
                                                    response.get());
                                        })
                                .build();
                var s = c.openSession(CONTEXT)) {
            var n = new AtomicInteger();
            tool(s, Map.of("tenant", "tenant-a"), n);
            assertEquals(
                    "approval_identity_mismatch",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> tool(s, Map.of("tenant", "tenant-a"), n))
                            .code());
            assertEquals(1, n.get());
        }
    }

    @Test
    void transformStillRequiresApproval() {
        var p =
                StructuredPolicy.builder("v1")
                        .tool("lookup", new ToolRule(Set.of(), PARAMS, true))
                        .content(
                                new ContentRule(
                                        "rewrite",
                                        Set.of(InterventionPoint.PRE_TOOL_CALL),
                                        List.of(),
                                        Map.of("tenant-a", "tenant-a")))
                        .build();
        // 自定义目标的改写与审批通过同一 validateEffective / requiresApproval 契约。
        PolicyEvaluator evaluator =
                new PolicyEvaluator() {
                    @Override
                    public String version() {
                        return "v1";
                    }

                    @Override
                    public Set<InterventionPoint> interventionPoints() {
                        return EnumSet.allOf(InterventionPoint.class);
                    }

                    @Override
                    public PolicyDecision evaluate(PolicyRequest r) {
                        return r.point() == InterventionPoint.PRE_TOOL_CALL
                                ? PolicyDecision.transform("rewrite", Map.of("tenant", "tenant-a"))
                                : PolicyDecision.allow();
                    }

                    @Override
                    public boolean requiresApproval(PolicyRequest r) {
                        return r.point() == InterventionPoint.PRE_TOOL_CALL;
                    }

                    @Override
                    public void validateEffective(PolicyRequest r) {
                        p.validateEffective(r);
                    }
                };
        try (var c =
                        AgentControl.builder(evaluator)
                                .audit(r -> {})
                                .approvalResolver(
                                        r -> {
                                            assertEquals(
                                                    Map.of("tenant", "tenant-a"),
                                                    r.action().target());
                                            return CompletableFuture.completedFuture(
                                                    ApprovalResponse.allow(r));
                                        })
                                .build();
                var s = c.openSession(CONTEXT)) {
            assertEquals("ok", tool(s, Map.of("tenant", "tenant-b"), new AtomicInteger()));
        }
    }

    @Test
    void revokeCancelsPendingApproval() throws Exception {
        var entered = new CountDownLatch(1);
        var future = new CompletableFuture<ApprovalResponse>();
        try (var c =
                        AgentControl.builder(policy(true))
                                .audit(r -> {})
                                .approvalResolver(
                                        r -> {
                                            entered.countDown();
                                            return future;
                                        })
                                .build();
                var s = c.openSession(CONTEXT)) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                var call =
                        executor.submit(
                                () -> tool(s, Map.of("tenant", "tenant-a"), new AtomicInteger()));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                s.revoke();
                assertThrows(ExecutionException.class, () -> call.get(2, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void executionFailureConsumesBudget() {
        try (var c = runtime(policy(false), new ArrayList<>());
                var s = c.openSession(CONTEXT)) {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            s.operation(
                                    true,
                                    "lookup",
                                    Map.of("tenant", "tenant-a"),
                                    v -> {
                                        throw new IllegalStateException();
                                    },
                                    v -> v,
                                    (v, e) -> (String) e));
            assertEquals(1, s.budgetSnapshot().toolCalls());
        }
    }

    @Test
    void concurrentReservationNeverExceedsLimit() throws Exception {
        try (var c =
                        AgentControl.builder(policy(false))
                                .budget(new BudgetLimits(2, 1, 300_000))
                                .audit(r -> {})
                                .build();
                var s = c.openSession(CONTEXT)) {
            var effects = new AtomicInteger();
            var pool = Executors.newFixedThreadPool(8);
            try {
                var tasks = new ArrayList<Future<?>>();
                for (int i = 0; i < 24; i++) {
                    tasks.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            tool(s, Map.of("tenant", "tenant-a"), effects);
                                        } catch (GovernanceException ignored) {
                                        }
                                    }));
                }
                for (var t : tasks) {
                    t.get();
                }
                assertTrue(effects.get() <= 1);
                assertTrue(s.budgetSnapshot().toolCalls() <= 1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void modelBudgetTokensAndExpiration() throws Exception {
        try (var c =
                        AgentControl.builder(policy(false))
                                .budget(new BudgetLimits(1, 1, 1000))
                                .audit(r -> {})
                                .build();
                var s = c.openSession(CONTEXT)) {
            s.operation(false, null, "x", v -> "y", v -> v, (v, e) -> (String) e);
            s.recordTokens(4);
            assertEquals(4, s.budgetSnapshot().tokens());
            s.recordTokens(null);
            assertNull(s.budgetSnapshot().tokens());
            assertEquals(
                    "model_budget_exceeded",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            s.operation(
                                                    false,
                                                    null,
                                                    "x",
                                                    v -> "y",
                                                    v -> v,
                                                    (v, e) -> (String) e))
                            .code());
        }
        try (var c =
                        AgentControl.builder(policy(false))
                                .budget(new BudgetLimits(1, 1, 5))
                                .audit(r -> {})
                                .build();
                var s = c.openSession(CONTEXT)) {
            Thread.sleep(15);
            assertEquals(
                    "session_expired",
                    assertThrows(GovernanceException.class, s::checkActive).code());
        }
    }

    @Test
    void closedSessionAndMissingSession() {
        try (var c = runtime(policy(false), new ArrayList<>())) {
            assertEquals(
                    "missing_session",
                    assertThrows(GovernanceException.class, c::currentSession).code());
            var s = c.openSession(CONTEXT);
            s.close();
            assertEquals(
                    "session_closed",
                    assertThrows(GovernanceException.class, s::checkActive).code());
            assertEquals(
                    "missing_session",
                    assertThrows(GovernanceException.class, c::currentSession).code());
        }
    }

    @Test
    void auditFailurePreventsSideEffectsAndIsSticky() {
        var count = new AtomicInteger();
        var effects = new AtomicInteger();
        try (var c =
                AgentControl.builder(policy(false))
                        .audit(
                                r -> {
                                    if (count.incrementAndGet() > 1) {
                                        throw new IllegalStateException();
                                    }
                                })
                        .build()) {
            var s = c.openSession(CONTEXT);
            assertThrows(
                    GovernanceException.class,
                    () -> tool(s, Map.of("tenant", "tenant-a"), effects));
            assertEquals(0, effects.get());
            assertThrows(GovernanceException.class, () -> c.openSession(CONTEXT));
            assertThrows(GovernanceException.class, s::close);
        }
    }

    @Test
    void jsonlDoesNotContainContentOrPrincipal() throws Exception {
        Path path = temporary.resolve("audit.jsonl");
        try (var sink = new JsonlAuditSink(path);
                var c = AgentControl.builder(policy(false)).audit(sink).build();
                var s = c.openSession(CONTEXT)) {
            s.run("very-private-secret", v -> "very-private-secret", v -> v, (v, e) -> (String) e);
        }
        String text = Files.readString(path);
        assertFalse(text.contains("very-private-secret"));
        assertFalse(text.contains("user-a"));
        for (String line : text.lines().toList()) {
            assertNotNull(JsonValues.parseObject(line).get("actionId"));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"x\":1,\"x\":2}",
                "{\"x\":1,\"\\u0078\":2}",
                "{}{}",
                "{bad}",
                "{\"s\":\"\\uD800\"}",
                "[]"
            })
    void strictJson(String json) {
        assertThrows(GovernanceException.class, () -> JsonValues.parseObject(json));
    }

    @Test
    void immutableSnapshotAndDigest() {
        var mutable = new HashMap<String, Object>();
        mutable.put("x", new ArrayList<>(List.of("a")));
        var request = new PolicyRequest(InterventionPoint.INPUT, CONTEXT, "s", "a", null, mutable);
        mutable.put("x", "changed");
        assertEquals(Map.of("x", List.of("a")), request.target());
        assertEquals(
                JsonValues.digest(Map.of("a", 1, "b", 2)),
                JsonValues.digest(Map.of("b", 2, "a", 1)));
    }

    static final String YAML =
            """
        apiVersion: agt.java/v1
        version: v1
        interventionPoints: [agent_startup, input, pre_model_call, post_model_call, pre_tool_call, post_tool_call, output, agent_shutdown]
        budget: {maxModelCalls: 2, maxToolCalls: 2, maxDurationMillis: 1000}
        tools:
          lookup:
            permissions: [read]
            parameters:
              type: OBJECT
              properties:
                tenant: {type: STRING, equalsContext: tenantId}
              required: [tenant]
            approvalRequired: false
        """;

    @Test
    void yamlLoadsAndMatchesProgrammaticRules() {
        try (var c = runtime(StructuredPolicy.fromYaml(YAML), new ArrayList<>());
                var s = c.openSession(CONTEXT)) {
            assertEquals("ok", tool(s, Map.of("tenant", "tenant-a"), new AtomicInteger()));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unknown: true",
                "apiVersion: bad",
                "version: v2",
                "contentRules: [{id: x, points: [input], script: hello}]",
                "budget: {maxModelCalls: '2'}"
            })
    void rejectsUnknownDuplicateAndIncompleteYaml(String extra) {
        assertThrows(
                GovernanceException.class, () -> StructuredPolicy.fromYaml(YAML + extra + "\n"));
    }

    @Test
    void missingPointRejectedAtConstruction() {
        var p = StructuredPolicy.builder("v1").points(Set.of(InterventionPoint.INPUT)).build();
        try (var c = runtime(p, new ArrayList<>())) {
            assertThrows(GovernanceException.class, () -> c.openSession(CONTEXT));
        }
    }

    @Test
    void oversizedAggregateAndRewriteExpansionRefused() {
        assertThrows(
                GovernanceException.class,
                () -> JsonValues.freeze(List.of("a".repeat(600000), "b".repeat(600000))));
        var rule =
                new ContentRule(
                        "expansion",
                        Set.of(InterventionPoint.INPUT),
                        List.of(),
                        Map.of("a", "x".repeat(100000)));
        assertThrows(GovernanceException.class, () -> rule.transform("a".repeat(100)));
    }

    @Test
    void policyFailureIsSanitizedAndStopsSession() {
        PolicyEvaluator broken =
                new PolicyEvaluator() {
                    @Override
                    public String version() {
                        return "v1";
                    }

                    @Override
                    public Set<InterventionPoint> interventionPoints() {
                        return EnumSet.allOf(InterventionPoint.class);
                    }

                    @Override
                    public PolicyDecision evaluate(PolicyRequest r) {
                        if (r.point() == InterventionPoint.INPUT) {
                            throw new IllegalStateException("private-secret");
                        }
                        return PolicyDecision.allow();
                    }
                };
        try (var c = AgentControl.builder(broken).audit(r -> {}).build();
                var session = c.openSession(CONTEXT)) {
            var e = assertThrows(GovernanceException.class, () -> c.run("x", v -> v));
            assertEquals("policy_evaluation_failed", e.code());
            assertFalse(e.getMessage().contains("private-secret"));
            assertEquals("policy_evaluation_failed", session.failureCode());
        }
    }

    @Test
    void prepareFailureReleasesBudgetWithoutExecution() {
        try (var c = runtime(policy(false), new ArrayList<>());
                var session = c.openSession(CONTEXT)) {
            var effects = new AtomicInteger();
            assertThrows(
                    GovernanceException.class,
                    () ->
                            session.operationPrepared(
                                    true,
                                    "lookup",
                                    Map.of("tenant", "tenant-a"),
                                    v -> {
                                        throw new GovernanceException("transform_not_applicable");
                                    },
                                    v -> {
                                        effects.incrementAndGet();
                                        return "ok";
                                    },
                                    v -> v,
                                    (v, e) -> (String) e));
            assertEquals(0, effects.get());
            assertEquals(0, session.budgetSnapshot().toolCalls());
        }
    }

    @Test
    void auditFailureAfterExecutionCannotUndoEffect() {
        var effects = new AtomicInteger();
        try (var c =
                AgentControl.builder(policy(false))
                        .audit(
                                r -> {
                                    if (r.point() == InterventionPoint.POST_TOOL_CALL) {
                                        throw new IllegalStateException();
                                    }
                                })
                        .build()) {
            var session = c.openSession(CONTEXT);
            assertEquals(
                    "audit_failed",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> tool(session, Map.of("tenant", "tenant-a"), effects))
                            .code());
            assertEquals(1, effects.get());
            assertThrows(GovernanceException.class, session::close);
        }
    }

    @Test
    void parameterNumbersArraysAndEnumsUseNoCoercion() {
        var integer =
                new ParameterRule(
                        ParameterRule.Type.INTEGER,
                        null,
                        null,
                        List.of(1, 2),
                        new java.math.BigDecimal("1"),
                        new java.math.BigDecimal("2"),
                        null,
                        null,
                        null,
                        null);
        integer.validate(JsonValues.parse("1"), CONTEXT);
        assertThrows(GovernanceException.class, () -> integer.validate("1", CONTEXT));
        assertThrows(
                GovernanceException.class,
                () -> integer.validate(JsonValues.parse("1.5"), CONTEXT));
        assertThrows(GovernanceException.class, () -> integer.validate(3, CONTEXT));
        var array =
                new ParameterRule(
                        ParameterRule.Type.ARRAY,
                        null,
                        null,
                        null,
                        null,
                        null,
                        1,
                        2,
                        null,
                        integer);
        array.validate(List.of(1, 2), CONTEXT);
        assertThrows(GovernanceException.class, () -> array.validate(List.of(), CONTEXT));
        assertThrows(GovernanceException.class, () -> array.validate(List.of(1, 2, 1), CONTEXT));
    }
}
