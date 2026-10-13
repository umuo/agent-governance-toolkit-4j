package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agt.core.AgentControl;
import io.agt.core.BudgetLimits;
import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.JsonValues;
import io.agt.core.PolicyDecision;
import io.agt.core.PolicyEvaluator;
import io.agt.core.PolicyRequest;
import io.agt.core.TrustedContext;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * LangChain4j 异步治理集成测试。
 *
 * <p>验证异步 AiServices、异步模型调用、异步工具执行在治理拦截下的上下文绑定、并发调度与错误传播。
 */
class AsyncLangChain4jTest {
    interface Assistant {
        CompletableFuture<String> chat(String message);
    }

    interface StageAssistant {
        CompletionStage<AiMessage> chat(String message);
    }

    interface MemoryAssistant {
        CompletableFuture<String> chat(
                @MemoryId String id, @dev.langchain4j.service.UserMessage String message);
    }

    interface SyncAssistant {
        String chat(String message);
    }

    final ExecutorService io = Executors.newFixedThreadPool(2, r -> new Thread(r, "provider-io"));

    @AfterEach
    void stop() {
        io.shutdownNow();
    }

    static final TrustedContext CTX = new TrustedContext("agent", "tenant", "user", Set.of("read"));

    static StructuredPolicy.Builder policy() {
        return StructuredPolicy.builder("v2")
                .tool(
                        "lookup",
                        new ToolRule(
                                Set.of("read"),
                                ParameterRule.object(
                                        Map.of("tenant", ParameterRule.string("tenantId")),
                                        "tenant"),
                                false));
    }

    static ChatResponse text(String value) {
        return ChatResponse.builder().aiMessage(AiMessage.from(value)).build();
    }

    static ToolExecutionRequest request(String tenant) {
        return ToolExecutionRequest.builder()
                .name("lookup")
                .id("call-id")
                .arguments(JsonValues.write(Map.of("tenant", tenant)))
                .build();
    }

    static String code(CompletableFuture<?> future) {
        Throwable e = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        while ((e instanceof ExecutionException || e instanceof CompletionException)
                && e.getCause() != null) {
            e = e.getCause();
        }
        return e instanceof GovernanceException g ? g.code() : e.getClass().getSimpleName();
    }

    class Model implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());
        final Function<ChatRequest, ChatResponse> response;

        Model(Function<ChatRequest, ChatResponse> response) {
            this.response = response;
        }

        public ChatResponse doChat(ChatRequest r) {
            calls.incrementAndGet();
            requests.add(r);
            return response.apply(r);
        }

        public CompletableFuture<ChatResponse> doChatAsync(ChatRequest r) {
            return CompletableFuture.supplyAsync(() -> doChat(r), io);
        }
    }

    Model loop(String tenant) {
        return new Model(
                r ->
                        r.messages().get(r.messages().size() - 1)
                                        instanceof ToolExecutionResultMessage m
                                ? text(m.text())
                                : ChatResponse.builder()
                                        .aiMessage(AiMessage.from(request(tenant)))
                                        .build());
    }

    class FutureTools {
        final AtomicInteger effects = new AtomicInteger();
        final AgentControl control;

        FutureTools(AgentControl control) {
            this.control = control;
        }

        @Tool
        CompletableFuture<String> lookup(String tenant) {
            assertEquals(tenant, control.currentSession().context().tenantId());
            assertEquals("agt-worker", Thread.currentThread().getName());
            effects.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> "secret-result", io);
        }
    }

    @Test
    void realAsyncServicesAndFutureToolRedactBeforeNextModel() throws Exception {
        var audit = Collections.synchronizedList(new ArrayList<io.agt.core.audit.AuditRecord>());
        var p =
                policy().content(
                                new ContentRule(
                                        "redact",
                                        Set.of(InterventionPoint.POST_TOOL_CALL),
                                        List.of(),
                                        Map.of("secret", "masked")))
                        .build();
        try (var c = AgentControl.builder(p).audit(audit::add).build();
                var s = c.openSession(CTX)) {
            var tools = new FutureTools(c);
            var model = loop("tenant");
            var assistant =
                    new LangChain4jGovernance(c)
                            .aiServices(Assistant.class)
                            .chatModel(model)
                            .tools(tools)
                            .build();
            assertEquals("masked-result", assistant.chat("query").get(5, TimeUnit.SECONDS));
            assertEquals(1, tools.effects.get());
            assertEquals(2, s.budgetSnapshot().modelCalls());
            assertEquals(1, s.budgetSnapshot().toolCalls());
            assertEquals(
                    "masked-result",
                    ((ToolExecutionResultMessage) model.requests.get(1).messages().get(2)).text());
            assertTrue(
                    audit.stream()
                            .anyMatch(
                                    r ->
                                            r.point() == InterventionPoint.POST_TOOL_CALL
                                                    && r.transformApplied()));
        }
    }

    @Test
    void stagesAiMessageAndMissingSessionUseFutureFailure() throws Exception {
        try (var c = AgentControl.builder(policy().build()).audit(r -> {}).build()) {
            var assistant =
                    new LangChain4jGovernance(c)
                            .aiServices(StageAssistant.class)
                            .chatModel(new Model(r -> text("ok")))
                            .build();
            assertEquals("missing_session", code(assistant.chat("x").toCompletableFuture()));
            try (var s = c.openSession(CTX)) {
                assertEquals(
                        "ok",
                        assistant.chat("x").toCompletableFuture().get(5, TimeUnit.SECONDS).text());
            }
        }
    }

    @Test
    void deniedAsyncToolCannotBeRestoredByErrorHandler() throws Exception {
        try (var c = AgentControl.builder(policy().build()).audit(r -> {}).build();
                var s = c.openSession(CTX)) {
            var model = loop("foreign");
            var tools = new FutureTools(c);
            var a =
                    new LangChain4jGovernance(c)
                            .aiServices(Assistant.class)
                            .chatModel(model)
                            .tools(tools)
                            .toolExecutionErrorHandler(
                                    (e, ctx) -> ToolErrorHandlerResult.text("ignored"))
                            .build();
            assertNotNull(code(a.chat("query")));
            assertEquals(0, tools.effects.get());
            assertEquals(1, model.calls.get());
            assertEquals("context_binding_denied", s.failureCode());
        }
    }

    @Test
    void asyncInputAndFinalOutputTransformsApplied() throws Exception {
        var p =
                policy().content(
                                new ContentRule(
                                        "input",
                                        Set.of(InterventionPoint.INPUT),
                                        List.of(),
                                        Map.of("old", "new")))
                        .content(
                                new ContentRule(
                                        "output",
                                        Set.of(InterventionPoint.OUTPUT),
                                        List.of(),
                                        Map.of("secret", "masked")))
                        .build();
        var model =
                new Model(
                        r -> {
                            assertEquals("new", ((UserMessage) r.messages().get(0)).singleText());
                            return text("secret");
                        });
        try (var c = AgentControl.builder(p).audit(r -> {}).build();
                var s = c.openSession(CTX)) {
            var a =
                    new LangChain4jGovernance(c)
                            .aiServices(Assistant.class)
                            .chatModel(model)
                            .build();
            assertEquals("masked", a.chat("old").get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nativeModelFutureReceivesCallerCancellation() throws Exception {
        var source = new CompletableFuture<ChatResponse>();
        var started = new CountDownLatch(1);
        var requests = new AtomicInteger();
        ChatModel raw =
                new ChatModel() {
                    public CompletableFuture<ChatResponse> chatAsync(
                            ChatRequest r, dev.langchain4j.model.chat.ChatRequestOptions o) {
                        requests.incrementAndGet();
                        started.countDown();
                        return source;
                    }
                };
        try (var c = AgentControl.builder(policy().build()).audit(r -> {}).build();
                var s = c.openSession(CTX)) {
            var a = new LangChain4jGovernance(c).aiServices(Assistant.class).chatModel(raw).build();
            var result = a.chat("x");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(result.cancel(true));
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
            assertEquals(1, requests.get());
            assertThrows(GovernanceException.class, s::checkActive);
        }
    }

    @Test
    void explicitAsyncExecutorKeepsNativeContextAndCancelSource() throws Exception {
        var source = new CompletableFuture<ToolExecutionResult>();
        var ready = new CountDownLatch(1);
        try (var c = AgentControl.builder(policy().build()).audit(r -> {}).build();
                var s = c.openSession(CTX)) {
            var context = InvocationContext.builder().chatMemoryId("trusted-memory").build();
            var tool =
                    new LangChain4jGovernance(c)
                            .guardTool(
                                    "lookup",
                                    new ToolExecutor() {
                                        public String execute(ToolExecutionRequest r, Object id) {
                                            throw new AssertionError();
                                        }

                                        public CompletableFuture<ToolExecutionResult> executeAsync(
                                                ToolExecutionRequest r, InvocationContext ctx) {
                                            assertSame(context, ctx);
                                            assertSame(s, c.currentSession());
                                            ready.countDown();
                                            return source;
                                        }
                                    });
            var result = tool.executeAsync(request("tenant"), context);
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            result.cancel(true);
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
            assertEquals(1, s.budgetSnapshot().toolCalls());
        }
    }

    @Test
    void asyncMemoryFallbackPropagatesContextAndChecksHistory() throws Exception {
        try (var c = AgentControl.builder(policy().build()).audit(r -> {}).build();
                var s = c.openSession(CTX)) {
            var model = loop("tenant");
            var tools = new FutureTools(c);
            var a =
                    new LangChain4jGovernance(c)
                            .aiServices(MemoryAssistant.class)
                            .chatModel(model)
                            .tools(tools)
                            .chatMemoryProvider(
                                    id ->
                                            MessageWindowChatMemory.builder()
                                                    .id(id)
                                                    .maxMessages(20)
                                                    .build())
                            .build();
            assertEquals("secret-result", a.chat("memory", "query").get(5, TimeUnit.SECONDS));
            assertEquals(2, s.budgetSnapshot().modelCalls());
            assertEquals(1, s.budgetSnapshot().toolCalls());
        }
    }

    @Test
    void parallelSyncToolsShareSessionWithHostExecutor() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var effects = new AtomicInteger();
        var host = Executors.newFixedThreadPool(2);
        var model =
                new Model(
                        r ->
                                r.messages().stream()
                                                .anyMatch(
                                                        m ->
                                                                m
                                                                        instanceof
                                                                        ToolExecutionResultMessage)
                                        ? text("ok")
                                        : ChatResponse.builder()
                                                .aiMessage(
                                                        AiMessage.from(
                                                                List.of(
                                                                        request("tenant"),
                                                                        ToolExecutionRequest
                                                                                .builder()
                                                                                .id("call-2")
                                                                                .name("lookup")
                                                                                .arguments(
                                                                                        "{\"tenant\":\"tenant\"}")
                                                                                .build())))
                                                .build());
        try (var c = AgentControl.builder(policy().build()).audit(r -> {}).build()) {
            ToolExecutor tool =
                    (r, id) -> {
                        assertEquals("tenant", c.currentSession().context().tenantId());
                        effects.incrementAndGet();
                        entered.countDown();
                        try {
                            assertTrue(release.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        return "ok";
                    };
            var spec =
                    ToolSpecifications.toolSpecificationFrom(
                            FutureTools.class.getDeclaredMethod("lookup", String.class));
            var a =
                    new LangChain4jGovernance(c)
                            .aiServices(SyncAssistant.class)
                            .chatModel(model)
                            .tools(Map.of(spec, tool))
                            .executeToolsConcurrently(host)
                            .build();
            var caller = Executors.newSingleThreadExecutor();
            try {
                var result =
                        caller.submit(
                                () -> {
                                    try (var s = c.openSession(CTX)) {
                                        var out = a.chat("x");
                                        assertEquals(2, s.budgetSnapshot().toolCalls());
                                        return out;
                                    }
                                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                release.countDown();
                assertEquals("ok", result.get(5, TimeUnit.SECONDS));
                assertEquals(2, effects.get());
                assertEquals(
                        "missing_session",
                        host.submit(
                                        () -> {
                                            try {
                                                c.currentSession();
                                                return "leak";
                                            } catch (GovernanceException e) {
                                                return e.code();
                                            }
                                        })
                                .get());
            } finally {
                release.countDown();
                caller.shutdownNow();
            }
        } finally {
            host.shutdownNow();
        }
    }

    @Test
    void parallelAsyncToolsCannotExceedSharedBudget() throws Exception {
        var model =
                new Model(
                        r ->
                                ChatResponse.builder()
                                        .aiMessage(
                                                AiMessage.from(
                                                        List.of(
                                                                request("tenant"),
                                                                ToolExecutionRequest.builder()
                                                                        .id("two")
                                                                        .name("lookup")
                                                                        .arguments(
                                                                                "{\"tenant\":\"tenant\"}")
                                                                        .build())))
                                        .build());
        try (var c =
                        AgentControl.builder(policy().build())
                                .budget(new BudgetLimits(5, 1, 10000))
                                .audit(r -> {})
                                .build();
                var s = c.openSession(CTX)) {
            var tools = new FutureTools(c);
            var a =
                    new LangChain4jGovernance(c)
                            .aiServices(Assistant.class)
                            .chatModel(model)
                            .tools(tools)
                            .build();
            assertNotNull(code(a.chat("x")));
            assertTrue(tools.effects.get() <= 1);
            assertTrue(s.budgetSnapshot().toolCalls() <= 1);
            assertEquals(1, model.calls.get());
        }
    }

    @Test
    void concurrentAsyncTenantsDoNotShareIdentities() throws Exception {
        var checked = new AtomicInteger();
        var base = policy().build();
        PolicyEvaluator policy =
                new PolicyEvaluator() {
                    public String version() {
                        return base.version();
                    }

                    public Set<InterventionPoint> interventionPoints() {
                        return base.interventionPoints();
                    }

                    public PolicyDecision evaluate(PolicyRequest r) {
                        if (r.point() == InterventionPoint.PRE_MODEL_CALL) {
                            @SuppressWarnings("unchecked")
                            var message = (Map<String, Object>) ((List<?>) r.target()).get(0);
                            assertEquals(r.context().tenantId(), message.get("text"));
                            checked.incrementAndGet();
                        }
                        return base.evaluate(r);
                    }
                };
        var callers = Executors.newFixedThreadPool(8);
        try (var c = AgentControl.builder(policy).audit(r -> {}).build()) {
            var a =
                    new LangChain4jGovernance(c)
                            .aiServices(Assistant.class)
                            .chatModel(new Model(r -> text("ok")))
                            .build();
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 24; i++) {
                final String tenant = "tenant-" + i;
                jobs.add(
                        callers.submit(
                                () -> {
                                    try (var s =
                                            c.openSession(
                                                    new TrustedContext(
                                                            "agent", tenant, "u", Set.of()))) {
                                        assertEquals("ok", a.chat(tenant).get(5, TimeUnit.SECONDS));
                                        assertEquals(1, s.budgetSnapshot().modelCalls());
                                    }
                                    return null;
                                }));
            }
            for (var job : jobs) {
                job.get(10, TimeUnit.SECONDS);
            }
            assertEquals(24, checked.get());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void asyncFinalOutputDenyIsTerminal() throws Exception {
        var p =
                policy().content(
                                new ContentRule(
                                        "output-denied",
                                        Set.of(InterventionPoint.OUTPUT),
                                        List.of("secret"),
                                        Map.of()))
                        .build();
        try (var c = AgentControl.builder(p).audit(r -> {}).build();
                var session = c.openSession(CTX)) {
            var a =
                    new LangChain4jGovernance(c)
                            .aiServices(Assistant.class)
                            .chatModel(new Model(r -> text("secret")))
                            .build();
            assertEquals("output-denied", code(a.chat("x")));
            assertThrows(GovernanceException.class, session::checkActive);
        }
    }

    @Test
    void asyncRagPreservesContextBeforeModelCheck() throws Exception {
        var p =
                policy().content(
                                new ContentRule(
                                        "context-denied",
                                        Set.of(InterventionPoint.PRE_MODEL_CALL),
                                        List.of("FORBIDDEN"),
                                        Map.of()))
                        .build();
        try (var c = AgentControl.builder(p).audit(r -> {}).build();
                var session = c.openSession(CTX)) {
            var model = new Model(r -> text("bad"));
            dev.langchain4j.rag.RetrievalAugmentor rag =
                    new dev.langchain4j.rag.RetrievalAugmentor() {
                        public dev.langchain4j.rag.AugmentationResult augment(
                                dev.langchain4j.rag.AugmentationRequest r) {
                            throw new AssertionError();
                        }

                        public CompletableFuture<dev.langchain4j.rag.AugmentationResult>
                                augmentAsync(dev.langchain4j.rag.AugmentationRequest r) {
                            assertSame(session, c.currentSession());
                            return CompletableFuture.supplyAsync(
                                    () ->
                                            dev.langchain4j.rag.AugmentationResult.builder()
                                                    .chatMessage(UserMessage.from("FORBIDDEN"))
                                                    .build(),
                                    io);
                        }
                    };
            var a =
                    new LangChain4jGovernance(c)
                            .aiServices(Assistant.class)
                            .chatModel(model)
                            .retrievalAugmentor(rag)
                            .build();
            assertEquals("context-denied", code(a.chat("x")));
            assertEquals(0, model.calls.get());
            assertEquals(0, session.budgetSnapshot().modelCalls());
        }
    }
}
