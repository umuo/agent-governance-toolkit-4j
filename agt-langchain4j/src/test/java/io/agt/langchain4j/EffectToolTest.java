package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.*;
import io.agt.core.effects.*;
import io.agt.core.policy.*;
import io.agt.effects.jdbc.JdbcEffectLedger;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EffectToolTest {
    interface Assistant {
        String chat(String input);
    }

    static final TrustedContext ID = new TrustedContext("agent", "tenant", "user", Set.of("pay"));
    static final EffectIntent INTENT = new EffectIntent("host-order-7", "open", null);

    static final class Backend implements EffectBackend {
        final AtomicInteger changes = new AtomicInteger();
        final AtomicInteger recoveries = new AtomicInteger();
        volatile Call committed;

        public String currentState(Call call) {
            return changes.get() == 0 ? "open" : "paid";
        }

        public synchronized Reply execute(Call call) {
            assertNull(committed, "Mutation may not be invoked twice");
            committed = call;
            assertEquals(1, changes.incrementAndGet());
            return Reply.committed("receipt-7", "paid");
        }

        public Object recover(Call call, EffectLedger.Proof proof) {
            assertEquals(committed.idempotencyKey(), call.idempotencyKey());
            assertEquals(committed.requestDigest(), call.requestDigest());
            recoveries.incrementAndGet();
            return "paid";
        }
    }

    static JdbcEffectLedger ledger() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:native-effects-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var ledger = new JdbcEffectLedger(data, "orders", Clock.systemUTC(), 2);
        ledger.initializeSchema();
        return ledger;
    }

    static StructuredPolicy policy(JdbcEffectLedger ledger) {
        return StructuredPolicy.builder("native-effects-v1")
                .tool(
                        "pay",
                        new ToolRule(
                                Set.of("pay"),
                                ParameterRule.object(Map.of()),
                                false,
                                new EffectRule(
                                        "orders",
                                        "v1",
                                        EffectLedger.Phase.FORWARD,
                                        ledger.identity())))
                .build();
    }

    static ToolExecutionRequest request(String id) {
        return ToolExecutionRequest.builder().id(id).name("pay").arguments("{}").build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedModelCallIdAcrossSessionsCannotCauseDuplicateBusinessEffect(boolean async)
            throws Exception {
        var ledger = ledger();
        var backend = new Backend();
        try (var control = AgentControl.builder(policy(ledger)).audit(r -> {}).build()) {
            var tool =
                    new LangChain4jGovernance(control)
                            .guardEffectTool("pay", ledger, session -> INTENT, backend);
            for (int i = 0; i < 2; i++)
                try (var session = control.openSession(ID)) {
                    var call = request("model-generated-" + i);
                    String result =
                            async
                                    ? tool.executeAsync(call, null)
                                            .get(3, TimeUnit.SECONDS)
                                            .resultText()
                                    : tool.execute(call, null);
                    assertEquals("paid", result);
                }
        }
        assertEquals(1, backend.changes.get());
        assertEquals(1, backend.recoveries.get());
    }

    @Test
    void plainNativeToolCannotBypassPolicyRequiredReliability() {
        var ledger = ledger();
        var called = new AtomicInteger();
        try (var control = AgentControl.builder(policy(ledger)).audit(r -> {}).build();
                var session = control.openSession(ID)) {
            var tool =
                    new LangChain4jGovernance(control)
                            .guardTool(
                                    "pay",
                                    (request, memory) -> {
                                        called.incrementAndGet();
                                        return "paid";
                                    });
            assertEquals(
                    "effect_tool_binding_required",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> tool.execute(request("call"), null))
                            .code());
        }
        assertEquals(0, called.get());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void checkedNativeCallbackCannotMarkFailedOrRevokedStepSuccessful(boolean async, boolean revoke)
            throws Exception {
        var ledger = ledger();
        var backend = new Backend();
        var audit = new CopyOnWriteArrayList<io.agt.core.audit.AuditRecord>();
        try (var control = AgentControl.builder(policy(ledger)).audit(audit::add).build()) {
            var callbacks =
                    new io.agt.langchain4j.internal.tool.ToolCallbacks() {
                        public void after(
                                HostSession session,
                                ToolExecutionRequest request,
                                dev.langchain4j.invocation.InvocationContext context,
                                dev.langchain4j.service.tool.ToolExecutionResult result) {
                            assertEquals("paid", result.resultText());
                            if (revoke) session.revoke();
                            else throw new IllegalStateException("private-observer-detail");
                        }
                    };
            var tool =
                    io.agt.langchain4j.internal.tool.ToolAdapters.guard(
                            control,
                            "pay",
                            io.agt.langchain4j.internal.tool.ToolAdapters.effectRegistration(
                                    "pay", ledger, session -> INTENT, backend),
                            false,
                            callbacks);
            try (var session = control.openSession(ID)) {
                if (async)
                    assertInstanceOf(
                            GovernanceException.class,
                            assertThrows(
                                            ExecutionException.class,
                                            () ->
                                                    tool.executeAsync(
                                                                    request("callback-call"), null)
                                                            .get(3, TimeUnit.SECONDS))
                                    .getCause());
                else
                    assertThrows(
                            GovernanceException.class,
                            () -> tool.execute(request("callback-call"), null));
                assertThrows(GovernanceException.class, session::checkActive);
                assertFalse(
                        audit.stream()
                                .anyMatch(
                                        r ->
                                                r.point() == InterventionPoint.POST_TOOL_CALL
                                                        && "succeeded"
                                                                .equals(r.executionOutcome())));
            }
            try (var session = control.openSession(ID)) {
                var recovery =
                        new LangChain4jGovernance(control)
                                .guardEffectTool("pay", ledger, s -> INTENT, backend);
                assertEquals("paid", recovery.execute(request("retry-call"), null));
            }
        }
        assertEquals(1, backend.changes.get());
        assertEquals(1, backend.recoveries.get());
    }

    @Test
    void nativeAiServiceLoopUsesHostIntentAndRecoversCommittedResult() {
        var ledger = ledger();
        var backend = new Backend();
        var ids = new AtomicInteger();
        var model =
                new NativeIntegrationTest.NativeModel(
                        request -> {
                            if (request.messages().stream()
                                    .anyMatch(ToolExecutionResultMessage.class::isInstance))
                                return ChatResponse.builder()
                                        .aiMessage(AiMessage.from("done"))
                                        .build();
                            return ChatResponse.builder()
                                    .aiMessage(
                                            AiMessage.from(
                                                    request(
                                                            "native-call-"
                                                                    + ids.incrementAndGet())))
                                    .build();
                        });
        var specification =
                ToolSpecification.builder()
                        .name("pay")
                        .description("Pay the host-selected order")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        try (var control = AgentControl.builder(policy(ledger)).audit(r -> {}).build()) {
            var assistant =
                    new LangChain4jGovernance(control)
                            .aiServices(Assistant.class)
                            .chatModel(model)
                            .effectTool(specification, ledger, session -> INTENT, backend)
                            .build();
            for (int i = 0; i < 2; i++)
                try (var session = control.openSession(ID)) {
                    assertEquals("done", assistant.chat("Pay my order"));
                }
        }
        assertEquals(2, ids.get());
        assertEquals(1, backend.changes.get());
        assertEquals(1, backend.recoveries.get());
    }
}
