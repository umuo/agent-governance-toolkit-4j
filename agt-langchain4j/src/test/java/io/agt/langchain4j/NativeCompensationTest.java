package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.*;
import io.agt.core.*;
import io.agt.core.policy.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 补偿复用原生注解发现，但每次执行须以独立名称通过策略。 */
class NativeCompensationTest {
    static class Tools {
        final List<String> effects = new ArrayList<>();

        @Tool
        String reserve() {
            effects.add("reserve");
            return "receipt-secret";
        }

        @Tool
        String fail() {
            return "unused";
        }

        @CompensateFor("reserve")
        void undo(ToolExecution execution) {
            assertEquals("receipt-secret", execution.resultObject());
            effects.add("undo");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void compensationRequiresItsOwnAuthorizationAndPreservesInternalReceipt(boolean authorized) {
        var tools = new Tools();
        var compensatedEvents = new java.util.concurrent.atomic.AtomicInteger();
        var n = new java.util.concurrent.atomic.AtomicInteger();
        var model =
                new NativeIntegrationTest.NativeModel(
                        r -> {
                            if (n.getAndIncrement() == 0)
                                return ChatResponse.builder()
                                        .aiMessage(
                                                AiMessage.from(
                                                        ToolExecutionRequest.builder()
                                                                .id("a")
                                                                .name("reserve")
                                                                .arguments("{}")
                                                                .build(),
                                                        ToolExecutionRequest.builder()
                                                                .id("b")
                                                                .name("failure")
                                                                .arguments("{}")
                                                                .build()))
                                        .build();
                            assertTrue(
                                    r.messages()
                                            .toString()
                                            .contains("compensated_due_to_tool_error"));
                            return LangChain4jGovernanceTest.text("done");
                        });
        var policy =
                NativeIntegrationTest.policy()
                        .tool(
                                "reserve",
                                new ToolRule(Set.of("read"), ParameterRule.object(Map.of()), false))
                        .tool(
                                "failure",
                                new ToolRule(
                                        Set.of("read"), ParameterRule.object(Map.of()), false));
        if (authorized)
            policy.tool(
                    "compensate_reserve",
                    new ToolRule(
                            Set.of("read"),
                            ParameterRule.object(
                                    Map.of(
                                            "arguments",
                                            ParameterRule.object(Map.of()),
                                            "receipt",
                                            ParameterRule.string(null),
                                            "text",
                                            ParameterRule.string(null)),
                                    "arguments",
                                    "receipt",
                                    "text"),
                            false));
        var failure = ToolSpecification.builder().name("failure").description("failure").build();
        ToolExecutor executor =
                new ToolExecutor() {
                    public String execute(ToolExecutionRequest r, Object id) {
                        return "failed";
                    }

                    public ToolExecutionResult executeWithContext(
                            ToolExecutionRequest r,
                            dev.langchain4j.invocation.InvocationContext c) {
                        return ToolExecutionResult.builder()
                                .isError(true)
                                .resultText("failed")
                                .build();
                    }
                };
        try (var control = AgentControl.builder(policy.build()).audit(r -> {}).build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    NativeIntegrationTest.Assistant.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.chatModel(model)
                                                    .tools(tools)
                                                    .tools(Map.of(failure, executor))
                                                    .compensateOnToolErrors(true)
                                                    .registerListener(
                                                            new dev.langchain4j.observability.api
                                                                            .listener
                                                                            .AiServiceListener<
                                                                    dev.langchain4j.observability
                                                                            .api.event
                                                                            .ToolCompensatedEvent>() {
                                                                public Class<
                                                                                dev.langchain4j
                                                                                        .observability
                                                                                        .api.event
                                                                                        .ToolCompensatedEvent>
                                                                        getEventClass() {
                                                                    return dev.langchain4j
                                                                            .observability.api.event
                                                                            .ToolCompensatedEvent
                                                                            .class;
                                                                }

                                                                public void onEvent(
                                                                        dev.langchain4j
                                                                                        .observability
                                                                                        .api.event
                                                                                        .ToolCompensatedEvent
                                                                                event) {
                                                                    assertEquals(
                                                                            "reserve",
                                                                            event.request().name());
                                                                    compensatedEvents
                                                                            .incrementAndGet();
                                                                }
                                                            }));
            if (authorized) assertEquals("done", service.chat("hello"));
            else assertThrows(GovernanceException.class, () -> service.chat("hello"));
            assertEquals(authorized ? 1 : 0, compensatedEvents.get());
            assertEquals(
                    authorized ? List.of("reserve", "undo") : List.of("reserve"), tools.effects);
        }
    }

    static class VoidTools {
        final List<String> effects = new ArrayList<>();

        @Tool
        void reserveVoid() {
            effects.add("reserve");
        }

        @CompensateFor("reserveVoid")
        void undo(ToolExecution receipt) {
            assertNull(receipt.resultObject());
            assertEquals("Success", receipt.result());
            effects.add("undo");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"allow", "deny", "required", "budget", "approval", "approval-deny"})
    void voidCompensationOmitsNullReceiptWithoutRelaxingAuthorization(String mode) {
        var tools = new VoidTools();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var approvals = new java.util.concurrent.atomic.AtomicInteger();
        var model =
                new NativeIntegrationTest.NativeModel(
                        r -> {
                            if (calls.getAndIncrement() == 0)
                                return ChatResponse.builder()
                                        .aiMessage(
                                                AiMessage.from(
                                                        ToolExecutionRequest.builder()
                                                                .id("v")
                                                                .name("reserveVoid")
                                                                .arguments("{}")
                                                                .build(),
                                                        ToolExecutionRequest.builder()
                                                                .id("f")
                                                                .name("failure")
                                                                .arguments("{}")
                                                                .build()))
                                        .build();
                            assertTrue(
                                    r.messages()
                                            .toString()
                                            .contains("compensated_due_to_tool_error"));
                            return LangChain4jGovernanceTest.text("done");
                        });
        var policy =
                NativeIntegrationTest.policy()
                        .tool(
                                "reserveVoid",
                                new ToolRule(Set.of("read"), ParameterRule.object(Map.of()), false))
                        .tool(
                                "failure",
                                new ToolRule(
                                        Set.of("read"), ParameterRule.object(Map.of()), false));
        if (!mode.equals("deny"))
            policy.tool(
                    "compensate_reserveVoid",
                    new ToolRule(
                            Set.of("read"),
                            ParameterRule.object(
                                    Map.of(
                                            "arguments",
                                            ParameterRule.object(Map.of()),
                                            "receipt",
                                            ParameterRule.string(null),
                                            "text",
                                            ParameterRule.string(null)),
                                    mode.equals("required")
                                            ? new String[] {"arguments", "receipt", "text"}
                                            : new String[] {"arguments", "text"}),
                            mode.startsWith("approval")));
        ToolExecutor fail =
                new ToolExecutor() {
                    public String execute(ToolExecutionRequest request, Object id) {
                        return "failed";
                    }

                    public ToolExecutionResult executeWithContext(
                            ToolExecutionRequest request,
                            dev.langchain4j.invocation.InvocationContext context) {
                        return ToolExecutionResult.builder()
                                .isError(true)
                                .resultText("failed")
                                .build();
                    }
                };
        try (var control =
                AgentControl.builder(policy.build())
                        .budget(new BudgetLimits(10, mode.equals("budget") ? 2 : 10, 5000))
                        .approvalResolver(
                                request -> {
                                    approvals.incrementAndGet();
                                    var args =
                                            JsonValues.parseObject(
                                                    JsonValues.write(request.action().target()));
                                    assertFalse(args.containsKey("receipt"));
                                    return java.util.concurrent.CompletableFuture.completedFuture(
                                            mode.equals("approval-deny")
                                                    ? io.agt.core.approval.ApprovalResponse.deny(
                                                            request)
                                                    : io.agt.core.approval.ApprovalResponse.allow(
                                                            request));
                                })
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    NativeIntegrationTest.Assistant.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b ->
                                            b.chatModel(model)
                                                    .tools(tools)
                                                    .tools(
                                                            Map.of(
                                                                    ToolSpecification.builder()
                                                                            .name("failure")
                                                                            .description("failure")
                                                                            .build(),
                                                                    fail))
                                                    .compensateOnToolErrors(true));
            boolean allowed = mode.equals("allow") || mode.equals("approval");
            if (allowed) assertEquals("done", service.chat("hello"));
            else assertThrows(GovernanceException.class, () -> service.chat("hello"));
            assertEquals(allowed ? List.of("reserve", "undo") : List.of("reserve"), tools.effects);
            assertEquals(mode.startsWith("approval") ? 1 : 0, approvals.get());
        }
    }
}
