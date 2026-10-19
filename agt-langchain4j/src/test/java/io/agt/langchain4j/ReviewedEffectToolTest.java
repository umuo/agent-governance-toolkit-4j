package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.*;
import io.agt.core.*;
import io.agt.core.approval.*;
import io.agt.core.effects.*;
import io.agt.core.policy.*;
import io.agt.core.storage.StorageCrypto;
import io.agt.core.tools.*;
import io.agt.effects.jdbc.JdbcEffectLedger;
import io.agt.langchain4j.internal.tool.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 受审版本与持久副作用组合：重放不绕过版本、审批和结果交付检查。 */
class ReviewedEffectToolTest {
    static final byte[] ARTIFACT =
            "reviewed-effect".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static final TrustedContext ID = EffectToolTest.ID;
    static final TrustedContext INSPECTOR =
            new TrustedContext("inspector", "tenant", "user", Set.of("governance:effects:inspect"));
    static final EffectIntent INTENT = EffectToolTest.INTENT;
    static final EffectLedger.Key KEY =
            new EffectLedger.Key("tenant", "user", "orders", INTENT.id());

    static ToolSpecification spec() {
        return ToolSpecification.builder()
                .name("pay")
                .description("Pay the host-selected order")
                .parameters(JsonObjectSchema.builder().additionalProperties(false).build())
                .build();
    }

    static ToolDescriptor descriptor(String version) {
        return new ToolDescriptor(
                "pay",
                "host",
                version,
                "sha256:" + StorageCrypto.hash(ARTIFACT),
                JsonValues.parseObject(spec().toJson()),
                Set.of("pay"));
    }

    static ReviewedToolCatalog catalog(String version) {
        return new ReviewedToolCatalog(List.of(descriptor(version)), 1024);
    }

    static ToolLease register(ReviewedToolCatalog catalog) {
        return catalog.register("pay", ARTIFACT, JsonValues.parseObject(spec().toJson()));
    }

    static AgentControl control(
            ToolLease lease,
            JdbcEffectLedger ledger,
            java.util.function.Consumer<ApprovalRequest> approval) {
        var rule = EffectToolTest.policy(ledger).document().tools().get("pay");
        return AgentControl.builder(
                        StructuredPolicy.builder("reviewed-effects-v1")
                                .tool(
                                        "pay",
                                        new ToolRule(
                                                rule.permissions(),
                                                rule.parameters(),
                                                true,
                                                rule.effect(),
                                                lease.identity()))
                                .build())
                .reviewedTools(List.of(lease))
                .audit(r -> {})
                .approvalResolver(
                        r -> {
                            assertEquals(lease.identity(), r.action().toolBinding());
                            assertNotNull(r.action().effect());
                            assertEquals(INTENT.id(), r.action().effect().intent().id());
                            approval.accept(r);
                            return CompletableFuture.completedFuture(ApprovalResponse.allow(r));
                        })
                .build();
    }

    static String invoke(ToolExecutor tool, boolean async) throws Exception {
        return async
                ? tool.executeAsync(EffectToolTest.request(UUID.randomUUID().toString()), null)
                        .get(5, TimeUnit.SECONDS)
                        .resultText()
                : tool.execute(EffectToolTest.request("sync-call"), null);
    }

    static GovernanceException denied(ToolExecutor tool, boolean async) {
        Exception error = assertThrows(Exception.class, () -> invoke(tool, async));
        return assertInstanceOf(
                GovernanceException.class,
                error instanceof ExecutionException ? error.getCause() : error);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameVersionReplaysOnlyAfterFreshVersionBoundApproval(boolean async) throws Exception {
        var ledger = EffectToolTest.ledger();
        var backend = new EffectToolTest.Backend();
        var approvals = new AtomicInteger();
        try (var catalog = catalog("v1");
                var control =
                        control(register(catalog), ledger, r -> approvals.incrementAndGet())) {
            var tool =
                    new LangChain4jGovernance(control)
                            .guardEffectTool("pay", ledger, s -> INTENT, backend);
            for (int i = 0; i < 2; i++)
                try (var session = control.openSession(ID)) {
                    assertEquals("paid", invoke(tool, async));
                }
        }
        assertEquals(2, approvals.get());
        assertEquals(1, backend.changes.get());
        assertEquals(1, backend.recoveries.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedVersionCannotRecoverOrExecutePriorIntent(boolean async) throws Exception {
        var ledger = EffectToolTest.ledger();
        var backend = new EffectToolTest.Backend();
        try (var catalog = catalog("v1");
                var control = control(register(catalog), ledger, r -> {});
                var session = control.openSession(ID)) {
            assertEquals(
                    "paid",
                    invoke(
                            new LangChain4jGovernance(control)
                                    .guardEffectTool("pay", ledger, s -> INTENT, backend),
                            async));
        }
        try (var catalog = catalog("v2");
                var control = control(register(catalog), ledger, r -> {});
                var session = control.openSession(ID)) {
            assertEquals(
                    "effect_request_mismatch",
                    denied(
                                    new LangChain4jGovernance(control)
                                            .guardEffectTool("pay", ledger, s -> INTENT, backend),
                                    async)
                            .code());
        }
        assertEquals(1, backend.changes.get());
        assertEquals(0, backend.recoveries.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revocationDuringApprovalNeverCallsBackend(boolean async) {
        var ledger = EffectToolTest.ledger();
        var backend = new EffectToolTest.Backend();
        try (var catalog = catalog("v1");
                var control = control(register(catalog), ledger, r -> catalog.revoke("pay"));
                var session = control.openSession(ID)) {
            assertEquals(
                    "tool_version_revoked",
                    denied(
                                    new LangChain4jGovernance(control)
                                            .guardEffectTool("pay", ledger, s -> INTENT, backend),
                                    async)
                            .code());
        }
        assertEquals(0, backend.changes.get());
        assertNull(ledger.inspect(KEY, INSPECTOR).forward());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revocationDuringStateCheckNeverExecutesBusinessOperation(boolean async) {
        var ledger = EffectToolTest.ledger();
        var calls = new AtomicInteger();
        try (var catalog = catalog("v1");
                var control = control(register(catalog), ledger, r -> {});
                var session = control.openSession(ID)) {
            var backend =
                    new EffectBackend() {
                        public String currentState(Call call) {
                            catalog.revoke("pay");
                            return "open";
                        }

                        public Reply execute(Call call) {
                            calls.incrementAndGet();
                            return Reply.committed("r", "paid");
                        }

                        public Object recover(Call call, EffectLedger.Proof proof) {
                            fail("No replay");
                            return null;
                        }
                    };
            assertEquals(
                    "tool_version_revoked",
                    denied(
                                    new LangChain4jGovernance(control)
                                            .guardEffectTool("pay", ledger, s -> INTENT, backend),
                                    async)
                            .code());
        }
        assertEquals(0, calls.get());
        assertEquals(
                EffectLedger.Status.NOT_APPLIED, ledger.inspect(KEY, INSPECTOR).forward().status());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void committedResultAndRecoveredResultAreNotDisclosedAfterRevocation(boolean async)
            throws Exception {
        for (boolean replay : List.of(false, true)) {
            var ledger = EffectToolTest.ledger();
            var backend = new EffectToolTest.Backend();
            var raw = new AtomicInteger();
            var after = new AtomicInteger();
            if (replay)
                try (var catalog = catalog("v1");
                        var control = control(register(catalog), ledger, r -> {});
                        var session = control.openSession(ID)) {
                    assertEquals(
                            "paid",
                            invoke(
                                    new LangChain4jGovernance(control)
                                            .guardEffectTool("pay", ledger, s -> INTENT, backend),
                                    async));
                }
            try (var catalog = catalog("v1");
                    var control = control(register(catalog), ledger, r -> {});
                    var session = control.openSession(ID)) {
                var revokedBackend =
                        new EffectBackend() {
                            public String currentState(Call call) {
                                return backend.currentState(call);
                            }

                            public Reply execute(Call call) {
                                var value = backend.execute(call);
                                catalog.revoke("pay");
                                return value;
                            }

                            public Object recover(Call call, EffectLedger.Proof proof) {
                                var value = backend.recover(call, proof);
                                catalog.revoke("pay");
                                return value;
                            }
                        };
                var callbacks =
                        new ToolCallbacks() {
                            public void rawResult(
                                    HostSession session,
                                    ToolExecutionRequest request,
                                    dev.langchain4j.invocation.InvocationContext context,
                                    ToolExecutionResult value) {
                                raw.incrementAndGet();
                            }

                            public void after(
                                    HostSession session,
                                    ToolExecutionRequest request,
                                    dev.langchain4j.invocation.InvocationContext context,
                                    ToolExecutionResult value) {
                                after.incrementAndGet();
                            }
                        };
                var tool =
                        ToolAdapters.guard(
                                control,
                                "pay",
                                ToolAdapters.effectRegistration(
                                        "pay", ledger, s -> INTENT, revokedBackend),
                                false,
                                callbacks);
                assertEquals("tool_version_revoked", denied(tool, async).code());
            }
            assertEquals(1, backend.changes.get());
            assertEquals(
                    EffectLedger.Status.COMMITTED,
                    ledger.inspect(KEY, INSPECTOR).forward().status());
            assertEquals(0, raw.get());
            assertEquals(0, after.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeCallbacksCannotRevokeAndStillReportSuccess(boolean async) {
        for (String point : List.of("before", "raw", "after")) {
            var ledger = EffectToolTest.ledger();
            var backend = new EffectToolTest.Backend();
            var raw = new AtomicInteger();
            var after = new AtomicInteger();
            try (var catalog = catalog("v1");
                    var control = control(register(catalog), ledger, r -> {});
                    var session = control.openSession(ID)) {
                var callbacks =
                        new ToolCallbacks() {
                            public void before(
                                    HostSession session,
                                    ToolExecutionRequest request,
                                    dev.langchain4j.invocation.InvocationContext context) {
                                if (point.equals("before")) catalog.revoke("pay");
                            }

                            public void rawResult(
                                    HostSession session,
                                    ToolExecutionRequest request,
                                    dev.langchain4j.invocation.InvocationContext context,
                                    ToolExecutionResult value) {
                                raw.incrementAndGet();
                                if (point.equals("raw")) catalog.revoke("pay");
                            }

                            public void after(
                                    HostSession session,
                                    ToolExecutionRequest request,
                                    dev.langchain4j.invocation.InvocationContext context,
                                    ToolExecutionResult value) {
                                after.incrementAndGet();
                                if (point.equals("after")) catalog.revoke("pay");
                            }
                        };
                var tool =
                        ToolAdapters.guard(
                                control,
                                "pay",
                                ToolAdapters.effectRegistration(
                                        "pay", ledger, s -> INTENT, backend),
                                false,
                                callbacks);
                assertEquals("tool_version_revoked", denied(tool, async).code());
            }
            assertEquals(point.equals("before") ? 0 : 1, backend.changes.get());
            assertEquals(point.equals("before") ? 0 : 1, raw.get());
            assertEquals(point.equals("after") ? 1 : 0, after.get());
            if (point.equals("before")) assertNull(ledger.inspect(KEY, INSPECTOR).forward());
            else
                assertEquals(
                        EffectLedger.Status.COMMITTED,
                        ledger.inspect(KEY, INSPECTOR).forward().status());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compensationHasIndependentVersionAndRetainsForwardProof(boolean async) throws Exception {
        var ledger = EffectToolTest.ledger();
        var backend = new EffectToolTest.Backend();
        var refundSpec =
                ToolSpecification.builder()
                        .name("refund")
                        .description("Refund the approved order")
                        .parameters(JsonObjectSchema.builder().additionalProperties(false).build())
                        .build();
        var refundDescriptor =
                new ToolDescriptor(
                        "refund",
                        "host",
                        "v1",
                        "sha256:" + StorageCrypto.hash(ARTIFACT),
                        JsonValues.parseObject(refundSpec.toJson()),
                        Set.of("pay"));
        var refundCalls = new AtomicInteger();
        var refundApprovals = new AtomicInteger();
        try (var catalog =
                new ReviewedToolCatalog(List.of(descriptor("v1"), refundDescriptor), 1024)) {
            var pay = register(catalog);
            var refund =
                    catalog.register(
                            "refund", ARTIFACT, JsonValues.parseObject(refundSpec.toJson()));
            try (var control = control(pay, ledger, r -> {});
                    var session = control.openSession(ID)) {
                assertEquals(
                        "paid",
                        invoke(
                                new LangChain4jGovernance(control)
                                        .guardEffectTool("pay", ledger, s -> INTENT, backend),
                                async));
            }
            var proof = ledger.inspect(KEY, INSPECTOR).forward().proof();
            var intent = new EffectIntent(INTENT.id(), "paid", proof);
            var policy =
                    StructuredPolicy.builder("refund-v1")
                            .tool(
                                    "refund",
                                    new ToolRule(
                                            Set.of("pay"),
                                            ParameterRule.object(Map.of()),
                                            true,
                                            new EffectRule(
                                                    "orders",
                                                    "v1",
                                                    EffectLedger.Phase.COMPENSATION,
                                                    ledger.identity()),
                                            refund.identity()))
                            .build();
            var refundBackend =
                    new EffectBackend() {
                        public String currentState(Call call) {
                            return "paid";
                        }

                        public Reply execute(Call call) {
                            assertEquals(EffectLedger.Phase.COMPENSATION, call.phase());
                            refundCalls.incrementAndGet();
                            return Reply.committed("refund-7", "refunded");
                        }

                        public Object recover(Call call, EffectLedger.Proof proof) {
                            return "refunded";
                        }
                    };
            try (var control =
                    AgentControl.builder(policy)
                            .reviewedTools(List.of(refund))
                            .audit(r -> {})
                            .approvalResolver(
                                    r -> {
                                        assertEquals(refund.identity(), r.action().toolBinding());
                                        assertEquals(proof, r.action().effect().intent().parent());
                                        refundApprovals.incrementAndGet();
                                        return CompletableFuture.completedFuture(
                                                ApprovalResponse.allow(r));
                                    })
                            .build()) {
                var tool =
                        new LangChain4jGovernance(control)
                                .guardEffectTool("refund", ledger, s -> intent, refundBackend);
                for (int i = 0; i < 2; i++)
                    try (var session = control.openSession(ID)) {
                        var request =
                                ToolExecutionRequest.builder()
                                        .id("refund-" + i)
                                        .name("refund")
                                        .arguments("{}")
                                        .build();
                        assertEquals(
                                "refunded",
                                async
                                        ? tool.executeAsync(request, null)
                                                .get(5, TimeUnit.SECONDS)
                                                .resultText()
                                        : tool.execute(request, null));
                    }
            }
        }
        assertEquals(1, backend.changes.get());
        assertEquals(1, refundCalls.get());
        assertEquals(2, refundApprovals.get());
        assertEquals(
                EffectLedger.Status.COMMITTED,
                ledger.inspect(KEY, INSPECTOR).compensation().status());
    }

    @Test
    void actualAiServiceToolLoopUsesReviewedReliableRegistration() {
        var ledger = EffectToolTest.ledger();
        var backend = new EffectToolTest.Backend();
        var model =
                new LangChain4jGovernanceTest.Model(
                        r ->
                                r.messages().get(r.messages().size() - 1)
                                                instanceof ToolExecutionResultMessage result
                                        ? LangChain4jGovernanceTest.text(result.text())
                                        : ChatResponse.builder()
                                                .aiMessage(
                                                        AiMessage.from(
                                                                EffectToolTest.request(
                                                                        "model-call")))
                                                .build());
        try (var catalog = catalog("v1");
                var control = control(register(catalog), ledger, r -> {})) {
            var assistant =
                    new LangChain4jGovernance(control)
                            .aiServices(EffectToolTest.Assistant.class)
                            .chatModel(model)
                            .effectTool(spec(), ledger, s -> INTENT, backend)
                            .build();
            for (int i = 0; i < 2; i++)
                try (var session = control.openSession(ID)) {
                    assertEquals("paid", assistant.chat("Pay"));
                }
        }
        assertEquals(1, backend.changes.get());
        assertEquals(1, backend.recoveries.get());
        assertEquals(4, model.calls.get());
    }
}
