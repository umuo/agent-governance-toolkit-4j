package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.agt.core.*;
import io.agt.core.approval.ApprovalResponse;
import io.agt.core.audit.AuditRecord;
import io.agt.core.policy.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 原生配置和自动会话的行为验收。
 *
 * <p>通过真实 AiServices 装配验证检查顺序、工具副作用、身份隔离及异步资源生命周期。
 */
class NativeIntegrationTest {
    interface Assistant {
        String chat(String message);
    }

    interface AsyncAssistant {
        CompletableFuture<String> chat(String message);
    }

    interface StreamAssistant {
        dev.langchain4j.service.TokenStream chat(String message);
    }

    static class NativeModel extends LangChain4jGovernanceTest.Model {
        NativeModel(java.util.function.Function<ChatRequest, ChatResponse> response) {
            super(response);
        }

        @Override
        public CompletableFuture<ChatResponse> doChatAsync(ChatRequest request) {
            return CompletableFuture.completedFuture(doChat(request));
        }
    }

    static final TrustedContext CTX = LangChain4jGovernanceTest.CONTEXT;

    static List<AuditRecord> audit() {
        return new CopyOnWriteArrayList<>();
    }

    static StructuredPolicy.Builder policy() {
        return LangChain4jGovernanceTest.policy();
    }

    static ContentRule redact(InterventionPoint point) {
        return new ContentRule("redact", Set.of(point), List.of(), Map.of("secret", "masked"));
    }

    static String code(Runnable action) {
        return assertThrows(GovernanceException.class, action::run).code();
    }

    static long shutdowns(List<AuditRecord> audit) {
        return audit.stream().filter(r -> r.point() == InterventionPoint.AGENT_SHUTDOWN).count();
    }

    @Test
    void nativeProvidersTransformersAndGuardrailsRunInsideGovernance() {
        var records = audit();
        var model = new NativeModel(request -> LangChain4jGovernanceTest.text("secret"));
        var transformed = new AtomicInteger();
        var guarded = new AtomicInteger();
        try (var control =
                AgentControl.builder(policy().content(redact(InterventionPoint.OUTPUT)).build())
                        .audit(records::add)
                        .build()) {
            var assistant =
                    new LangChain4jGovernance(control)
                            .create(
                                    Assistant.class,
                                    () -> CTX,
                                    builder ->
                                            builder.chatModel(model)
                                                    .systemMessageProvider(id -> "native-system")
                                                    .systemMessageTransformer(
                                                            text -> text + "-transformed")
                                                    .chatRequestTransformer(
                                                            request -> {
                                                                transformed.incrementAndGet();
                                                                return request;
                                                            })
                                                    .inputGuardrails(
                                                            new InputGuardrail() {
                                                                public InputGuardrailResult
                                                                        validate(
                                                                                UserMessage input) {
                                                                    assertEquals(
                                                                            CTX,
                                                                            control.currentSession()
                                                                                    .context());
                                                                    return successWith(
                                                                            "guarded-input");
                                                                }
                                                            })
                                                    .outputGuardrails(
                                                            new OutputGuardrail() {
                                                                public OutputGuardrailResult
                                                                        validate(AiMessage output) {
                                                                    guarded.incrementAndGet();
                                                                    return successWith(
                                                                            output.text()
                                                                                    + "-checked");
                                                                }
                                                            }));
            assertEquals("masked-checked", assistant.chat("hello"));
            assertEquals(1, transformed.get());
            assertEquals(1, guarded.get());
            assertEquals(
                    "native-system-transformed",
                    ((SystemMessage) model.requests.get(0).messages().get(0)).text());
            assertEquals(
                    "guarded-input",
                    ((UserMessage) model.requests.get(0).messages().get(1)).singleText());
            assertEquals("missing_session", code(control::currentSession));
            assertEquals(1, shutdowns(records));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "object",
                "collection",
                "map",
                "immediate-map",
                "list",
                "provider",
                "providers",
                "dynamic"
            })
    void everyNativeToolRegistrationIsAuthorizedAndRewritten(String registration) {
        var records = audit();
        var tool = new LangChain4jGovernanceTest.Tools();
        var model = LangChain4jGovernanceTest.loop("tenant-a");
        try (var control =
                AgentControl.builder(
                                policy().content(redact(InterventionPoint.POST_TOOL_CALL)).build())
                        .audit(records::add)
                        .build()) {
            var spec = ToolSpecifications.toolSpecificationsFrom(tool).get(0);
            ToolExecutor executor =
                    dev.langchain4j.service.tool.ToolService.findTools(tool).get(0).toolExecutor();
            var described =
                    AiServiceTool.builder().toolSpecification(spec).toolExecutor(executor).build();
            var providerCalls = new AtomicInteger();
            ToolProvider provider =
                    new ToolProvider() {
                        public boolean isDynamic() {
                            return registration.equals("dynamic");
                        }

                        public ToolProviderResult provideTools(
                                dev.langchain4j.service.tool.ToolProviderRequest request) {
                            providerCalls.incrementAndGet();
                            return new ToolProviderResult(List.of(described));
                        }
                    };
            var assistant =
                    new LangChain4jGovernance(control)
                            .create(
                                    Assistant.class,
                                    () -> CTX,
                                    builder -> {
                                        builder.chatModel(model).maxToolCallingRoundTrips(3);
                                        switch (registration) {
                                            case "object" -> builder.tools(tool);
                                            case "collection" ->
                                                    builder.tools(
                                                            (Collection<Object>)
                                                                    List.<Object>of(tool));
                                            case "map" -> builder.tools(Map.of(spec, executor));
                                            case "immediate-map" ->
                                                    builder.tools(
                                                            Map.of(spec, executor),
                                                            Set.of("lookup"));
                                            case "list" ->
                                                    builder.tools(
                                                            List.<AiServiceTool>of(
                                                                    described.toBuilder()
                                                                            .returnBehavior(
                                                                                    ReturnBehavior
                                                                                            .IMMEDIATE)
                                                                            .build()));
                                            case "provider", "dynamic" ->
                                                    builder.toolProvider(provider);
                                            case "providers" ->
                                                    builder.toolProviders(List.of(provider));
                                            default -> fail(registration);
                                        }
                                    });
            assertEquals("masked-answer", assistant.chat("lookup"));
            assertEquals(1, tool.effects.get());
            if (registration.equals("dynamic")) {
                assertTrue(providerCalls.get() > 1);
            }
            assertEquals(
                    Set.of("list", "immediate-map").contains(registration) ? 1 : 2,
                    model.calls.get());
            assertTrue(
                    records.stream().anyMatch(r -> r.point() == InterventionPoint.PRE_TOOL_CALL));
            assertTrue(
                    records.stream()
                            .anyMatch(
                                    r ->
                                            r.point() == InterventionPoint.POST_TOOL_CALL
                                                    && r.transformApplied()));
            assertEquals(1, shutdowns(records));
        }
    }

    @Test
    void deniedToolNeverRunsAndApprovalStillBindsTheAction() {
        var tool = new LangChain4jGovernanceTest.Tools();
        var approvals = new AtomicInteger();
        var protectedPolicy =
                StructuredPolicy.builder("approval-native")
                        .tool(
                                "lookup",
                                new ToolRule(
                                        Set.of("read"), LangChain4jGovernanceTest.PARAMS, true))
                        .build();
        try (var control =
                AgentControl.builder(protectedPolicy)
                        .audit(r -> {})
                        .approvalResolver(
                                request -> {
                                    approvals.incrementAndGet();
                                    assertEquals("lookup", request.action().toolName());
                                    return CompletableFuture.completedFuture(
                                            ApprovalResponse.allow(request));
                                })
                        .build()) {
            var governance = new LangChain4jGovernance(control);
            var denied =
                    governance.create(
                            Assistant.class,
                            () -> CTX,
                            b ->
                                    b.chatModel(LangChain4jGovernanceTest.loop("tenant-b"))
                                            .tools(tool));
            assertThrows(GovernanceException.class, () -> denied.chat("lookup"));
            assertEquals(0, tool.effects.get());
            assertEquals(0, approvals.get());
            var allowed =
                    governance.create(
                            Assistant.class,
                            () -> CTX,
                            b ->
                                    b.chatModel(LangChain4jGovernanceTest.loop("tenant-a"))
                                            .tools(tool));
            assertEquals("secret-answer", allowed.chat("lookup"));
            assertEquals(1, tool.effects.get());
            assertEquals(1, approvals.get());
        }
    }

    @Test
    void manualNativeEntryAndManagedEntryRespectExistingSessionAndBudget() {
        var resolutions = new AtomicInteger();
        var model = new NativeModel(r -> LangChain4jGovernanceTest.text("ok"));
        try (var control =
                AgentControl.builder(policy().build())
                        .budget(new BudgetLimits(1, 10, 5000))
                        .audit(r -> {})
                        .build()) {
            var governance = new LangChain4jGovernance(control);
            var manual = governance.aiServices(Assistant.class, b -> b.chatModel(model));
            assertEquals("missing_session", code(() -> manual.chat("hello")));
            var managed =
                    governance.create(
                            Assistant.class,
                            () -> {
                                resolutions.incrementAndGet();
                                return CTX;
                            },
                            b -> b.chatModel(model));
            try (var session = control.openSession(CTX)) {
                assertEquals("ok", manual.chat("hello"));
                assertSame(session, control.currentSession());
                assertThrows(GovernanceException.class, () -> managed.chat("exhausted"));
                assertThrows(GovernanceException.class, () -> managed.chat("still failed"));
                assertEquals(0, resolutions.get());
                assertEquals(1, model.calls.get());
            }
            assertEquals("ok", managed.chat("fresh top-level"));
            assertEquals(1, resolutions.get());
            assertEquals("missing_session", code(control::currentSession));
        }
    }

    @Test
    void identityIsResolvedPerCallerAndThreadsRemainIsolated() throws Exception {
        var records = audit();
        ThreadLocal<TrustedContext> identities = new ThreadLocal<>();
        try (var control = AgentControl.builder(policy().build()).audit(records::add).build()) {
            var model =
                    new NativeModel(
                            r ->
                                    LangChain4jGovernanceTest.text(
                                            control.currentSession().context().tenantId()));
            var assistant =
                    new LangChain4jGovernance(control)
                            .create(Assistant.class, identities::get, b -> b.chatModel(model));
            var pool = Executors.newFixedThreadPool(2);
            try {
                var tasks = new ArrayList<Future<String>>();
                for (String tenant : List.of("tenant-a", "tenant-b")) {
                    tasks.add(
                            pool.submit(
                                    () -> {
                                        identities.set(
                                                new TrustedContext(
                                                        "agent", tenant, "user", Set.of()));
                                        try {
                                            String answer = assistant.chat("hello");
                                            assertEquals(
                                                    "missing_session",
                                                    code(control::currentSession));
                                            return answer;
                                        } finally {
                                            identities.remove();
                                        }
                                    }));
                }
                assertEquals("tenant-a", tasks.get(0).get(5, TimeUnit.SECONDS));
                assertEquals("tenant-b", tasks.get(1).get(5, TimeUnit.SECONDS));
                assertEquals(2, shutdowns(records));
            } finally {
                pool.shutdownNow();
            }
            assertEquals("missing_trusted_context", code(() -> assistant.chat("missing identity")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void asyncOwnsSessionUntilCompletionAndPropagatesCancellation(boolean cancel) throws Exception {
        var records = audit();
        var started = new CountDownLatch(1);
        var source = new CompletableFuture<ChatResponse>();
        var captured = new AtomicReference<HostSession>();
        try (var control = AgentControl.builder(policy().build()).audit(records::add).build()) {
            ChatModel model =
                    new ChatModel() {
                        public ChatResponse doChat(ChatRequest request) {
                            throw new AssertionError("async required");
                        }

                        public CompletableFuture<ChatResponse> doChatAsync(ChatRequest request) {
                            captured.set(control.currentSession());
                            started.countDown();
                            return source;
                        }
                    };
            var assistant =
                    new LangChain4jGovernance(control)
                            .create(AsyncAssistant.class, () -> CTX, b -> b.chatModel(model));
            var result = assistant.chat("hello");
            assertEquals("missing_session", code(control::currentSession));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(0, shutdowns(records));
            if (cancel) {
                assertTrue(result.cancel(true));
                assertTrue(source.isCancelled());
            } else {
                source.complete(LangChain4jGovernanceTest.text("ok"));
                assertEquals("ok", result.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, shutdowns(records));
            assertThrows(GovernanceException.class, captured.get()::checkActive);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "cancel", "expire"})
    void strictStreamsCloseAfterDeliveryCancellationOrUnstartedExpiry(String mode)
            throws Exception {
        var records = audit();
        var started = new CountDownLatch(1);
        var source = new AtomicReference<StreamingChatResponseHandler>();
        var model =
                new StreamingGovernanceTest.Model(
                        (request, handler) -> {
                            source.set(handler);
                            handler.onPartialResponse("secret");
                            started.countDown();
                        });
        try (var control =
                AgentControl.builder(policy().content(redact(InterventionPoint.OUTPUT)).build())
                        .budget(new BudgetLimits(20, 50, mode.equals("expire") ? 100 : 5000))
                        .audit(records::add)
                        .build()) {
            var assistant =
                    new LangChain4jGovernance(control, StreamDelivery.STRICT)
                            .create(
                                    StreamAssistant.class,
                                    () -> CTX,
                                    b -> b.streamingChatModel(model));
            var stream = LangChain4jGovernance.buffered(assistant.chat("hello"));
            var fragments = new CopyOnWriteArrayList<String>();
            assertEquals("missing_session", code(control::currentSession));
            if (mode.equals("expire")) {
                assertEquals("session_expired", StreamingGovernanceTest.code(stream.completion()));
                assertEquals(0, model.calls.get());
            } else {
                stream.onPartialResponse(fragments::add).onError(error -> {});
                stream.start();
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertTrue(fragments.isEmpty());
                if (mode.equals("cancel")) {
                    assertTrue(stream.cancel());
                } else {
                    source.get().onCompleteResponse(LangChain4jGovernanceTest.text("secret"));
                    assertEquals(
                            "masked",
                            stream.completion().get(5, TimeUnit.SECONDS).aiMessage().text());
                    assertEquals(List.of("masked"), fragments);
                }
            }
            assertEquals(1, shutdowns(records));
        }
    }

    @Test
    void nativeMemoryProviderAndRagRetainAsyncContextAndExplicitDataAuthorization()
            throws Exception {
        var records = audit();
        var memories = new ConcurrentHashMap<Object, dev.langchain4j.memory.ChatMemory>();
        try (var control =
                AgentControl.builder(DataGovernanceTest.policy().build())
                        .audit(records::add)
                        .build()) {
            var governance = new LangChain4jGovernance(control);
            var retriever =
                    governance.guardRetriever(
                            DataGovernanceTest.SOURCE,
                            query -> {
                                assertEquals(
                                        DataGovernanceTest.CTX, control.currentSession().context());
                                return List.of(dev.langchain4j.rag.content.Content.from("context"));
                            });
            var assistant =
                    governance.create(
                            DataGovernanceTest.AsyncAssistant.class,
                            () -> DataGovernanceTest.CTX,
                            b ->
                                    b.chatModel(
                                                    new NativeModel(
                                                            r ->
                                                                    LangChain4jGovernanceTest.text(
                                                                            "ok")))
                                            .chatMemoryProvider(
                                                    id ->
                                                            memories.computeIfAbsent(
                                                                    id,
                                                                    key ->
                                                                            governance.guardMemory(
                                                                                    DataGovernanceTest
                                                                                            .MEMORY,
                                                                                    MessageWindowChatMemory
                                                                                            .builder()
                                                                                            .id(
                                                                                                    DataGovernanceTest
                                                                                                            .MEMORY
                                                                                                            .instanceId())
                                                                                            .maxMessages(
                                                                                                    20)
                                                                                            .build())))
                                            .contentRetriever(retriever));
            assertEquals("ok", assistant.chat("hello").get(5, TimeUnit.SECONDS));
            assertEquals("ok", assistant.chat("again").get(5, TimeUnit.SECONDS));
            assertEquals(1, memories.size());
            assertEquals(2, shutdowns(records));
            assertTrue(
                    records.stream().anyMatch(r -> r.point() == InterventionPoint.PRE_DATA_ACCESS));
        }
    }

    @Test
    void failuresCloseSessionsAndDoNotExposeIdentityExceptions() {
        var records = audit();
        try (var control = AgentControl.builder(policy().build()).audit(records::add).build()) {
            var governance = new LangChain4jGovernance(control);
            var model =
                    new NativeModel(
                            r -> {
                                throw new IllegalStateException("private-provider-message");
                            });
            var assistant = governance.create(Assistant.class, () -> CTX, b -> b.chatModel(model));
            assertThrows(GovernanceException.class, () -> assistant.chat("hello"));
            assertEquals(1, shutdowns(records));
            assertEquals("missing_session", code(control::currentSession));
            var missing =
                    governance.create(
                            AsyncAssistant.class,
                            () -> {
                                throw new IllegalStateException("private-identity");
                            },
                            b -> b.chatModel(model));
            assertEquals(
                    "identity_resolution_failed",
                    StreamingGovernanceTest.code(missing.chat("hello")));
            assertEquals(1, shutdowns(records));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "async", "stream"})
    void shutdownAuditFailureCannotCompleteSuccessfully(String kind) {
        try (var control =
                AgentControl.builder(policy().build())
                        .audit(
                                record -> {
                                    if (record.point() == InterventionPoint.AGENT_SHUTDOWN)
                                        throw new IllegalStateException("disk-failed");
                                })
                        .build()) {
            var governance = new LangChain4jGovernance(control);
            var model = new NativeModel(r -> LangChain4jGovernanceTest.text("ok"));
            if (kind.equals("sync")) {
                var assistant =
                        governance.create(Assistant.class, () -> CTX, b -> b.chatModel(model));
                assertEquals("audit_failed", code(() -> assistant.chat("hello")));
            } else if (kind.equals("async")) {
                var assistant =
                        governance.create(AsyncAssistant.class, () -> CTX, b -> b.chatModel(model));
                assertEquals("audit_failed", StreamingGovernanceTest.code(assistant.chat("hello")));
            } else {
                var streaming =
                        new StreamingGovernanceTest.Model(
                                (request, handler) ->
                                        handler.onCompleteResponse(
                                                LangChain4jGovernanceTest.text("ok")));
                var assistant =
                        governance.create(
                                StreamAssistant.class,
                                () -> CTX,
                                b -> b.streamingChatModel(streaming));
                var stream = LangChain4jGovernance.buffered(assistant.chat("hello"));
                stream.onError(error -> {}).start();
                assertEquals("audit_failed", StreamingGovernanceTest.code(stream.completion()));
            }
        }
    }

    @Test
    void unsupportedBypassesFailAtConfigurationTime() {
        try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build()) {
            var governance = new LangChain4jGovernance(control);
            assertEquals(
                    "native_builder_build_managed",
                    code(() -> governance.aiServices(Assistant.class, AiServices::build)));
            assertEquals(
                    "missing_model",
                    code(
                            () ->
                                    governance.aiServices(
                                            Assistant.class, b -> b.compensateOnToolErrors(true))));
            assertEquals(
                    "missing_model",
                    code(
                            () ->
                                    governance.aiServices(
                                            LangChain4jGovernanceTest.ComplexAssistant.class,
                                            b -> {})));
        }
    }

    @Test
    void asyncAnnotatedToolsUseTheManagedSessionAndSharedBudget() throws Exception {
        var fixture = new AsyncLangChain4jTest();
        var records = audit();
        try (var control =
                AgentControl.builder(
                                AsyncLangChain4jTest.policy()
                                        .content(redact(InterventionPoint.POST_TOOL_CALL))
                                        .build())
                        .audit(records::add)
                        .build()) {
            var tools = fixture.new FutureTools(control);
            var assistant =
                    new LangChain4jGovernance(control)
                            .create(
                                    AsyncAssistant.class,
                                    () -> AsyncLangChain4jTest.CTX,
                                    b -> b.chatModel(fixture.loop("tenant")).tools(tools));
            assertEquals("masked-result", assistant.chat("lookup").get(5, TimeUnit.SECONDS));
            assertEquals(1, tools.effects.get());
            assertEquals(1, shutdowns(records));
        } finally {
            fixture.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeConcurrentExecutorReceivesTheSessionContext(boolean custom) {
        var executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "native-tool"));
        var observed = new AtomicReference<TrustedContext>();
        var thread = new AtomicReference<String>();
        var effects = new AtomicInteger();
        var model =
                new NativeModel(
                        request -> {
                            if (request.messages().get(request.messages().size() - 1)
                                    instanceof
                                    dev.langchain4j.data.message.ToolExecutionResultMessage
                                                    result) {
                                return LangChain4jGovernanceTest.text(result.text());
                            }
                            var second =
                                    dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                            .id("second")
                                            .name("lookup")
                                            .arguments("{\"tenant\":\"tenant-a\"}")
                                            .build();
                            return ChatResponse.builder()
                                    .aiMessage(
                                            AiMessage.from(
                                                    List.of(
                                                            LangChain4jGovernanceTest.call(
                                                                    "tenant-a"),
                                                            second)))
                                    .build();
                        });
        try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build()) {
            var tool = new LangChain4jGovernanceTest.Tools();
            var spec = ToolSpecifications.toolSpecificationsFrom(tool).get(0);
            ToolExecutor raw =
                    (request, memory) -> {
                        observed.set(control.currentSession().context());
                        thread.set(Thread.currentThread().getName());
                        effects.incrementAndGet();
                        return "executor-ok";
                    };
            var assistant =
                    new LangChain4jGovernance(control)
                            .create(
                                    Assistant.class,
                                    () -> CTX,
                                    b -> {
                                        b.chatModel(model).tools(Map.of(spec, raw));
                                        if (custom) {
                                            b.executeToolsConcurrently(executor);
                                        } else {
                                            b.executeToolsConcurrently();
                                        }
                                    });
            assertEquals("executor-ok", assistant.chat("lookup"));
            assertEquals(CTX, observed.get());
            assertEquals(custom ? "native-tool" : "agt-worker", thread.get());
            assertEquals(2, effects.get());
            assertEquals("missing_session", code(control::currentSession));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void requestTransformerCannotBypassModelPolicy() {
        var records = audit();
        var model = new NativeModel(r -> LangChain4jGovernanceTest.text("should not execute"));
        var policy =
                policy().content(
                                new ContentRule(
                                        "blocked-transformation",
                                        Set.of(InterventionPoint.PRE_MODEL_CALL),
                                        List.of("BLOCK"),
                                        Map.of()))
                        .build();
        try (var control = AgentControl.builder(policy).audit(records::add).build()) {
            var assistant =
                    new LangChain4jGovernance(control)
                            .create(
                                    Assistant.class,
                                    () -> CTX,
                                    b ->
                                            b.chatModel(model)
                                                    .chatRequestTransformer(
                                                            request ->
                                                                    request.toBuilder()
                                                                            .messages(
                                                                                    List.of(
                                                                                            UserMessage
                                                                                                    .from(
                                                                                                            "BLOCK")))
                                                                            .build()));
            assertEquals("blocked-transformation", code(() -> assistant.chat("safe input")));
            assertEquals(0, model.calls.get());
            assertEquals(1, shutdowns(records));
        }
    }

    @Test
    void dynamicProviderCannotAuthorizeUnregisteredTools() {
        var tools = new LangChain4jGovernanceTest.Tools();
        var described = dev.langchain4j.service.tool.ToolService.findTools(tools);
        try (var control =
                AgentControl.builder(StructuredPolicy.builder("deny-all").build())
                        .audit(r -> {})
                        .build()) {
            var assistant =
                    new LangChain4jGovernance(control)
                            .create(
                                    Assistant.class,
                                    () -> CTX,
                                    b ->
                                            b.chatModel(LangChain4jGovernanceTest.loop("tenant-a"))
                                                    .toolProvider(
                                                            request ->
                                                                    new ToolProviderResult(
                                                                            described)));
            assertThrows(GovernanceException.class, () -> assistant.chat("lookup"));
            assertEquals(0, tools.effects.get());
        }
    }

    @Test
    void dynamicProviderMetadataFailuresAreNormalizedAndCloseTheSession() {
        var records = audit();
        var tools = new LangChain4jGovernanceTest.Tools();
        var described = dev.langchain4j.service.tool.ToolService.findTools(tools);
        ToolProvider provider =
                new ToolProvider() {
                    public boolean isDynamic() {
                        throw new IllegalStateException("private-provider-metadata");
                    }

                    public ToolProviderResult provideTools(
                            dev.langchain4j.service.tool.ToolProviderRequest request) {
                        return new ToolProviderResult(described);
                    }
                };
        try (var control = AgentControl.builder(policy().build()).audit(records::add).build()) {
            var assistant =
                    new LangChain4jGovernance(control)
                            .create(
                                    Assistant.class,
                                    () -> CTX,
                                    b ->
                                            b.chatModel(LangChain4jGovernanceTest.loop("tenant-a"))
                                                    .toolProvider(provider));
            assertEquals("tool_provider_failed", code(() -> assistant.chat("lookup")));
            assertEquals(0, tools.effects.get());
            assertEquals(1, shutdowns(records));
            assertEquals("missing_session", code(control::currentSession));
        }
    }
}
