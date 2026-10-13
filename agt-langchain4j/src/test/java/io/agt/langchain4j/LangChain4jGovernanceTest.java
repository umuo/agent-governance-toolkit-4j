package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.invocation.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.*;
import io.agt.core.*;
import io.agt.core.audit.*;
import io.agt.core.policy.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;

class LangChain4jGovernanceTest {
  interface Assistant {
    String chat(String message);
  }

  interface WithMemory {
    String chat(@MemoryId String memoryId, @dev.langchain4j.service.UserMessage String message);
  }

  interface AiAssistant {
    AiMessage chat(String message);
  }

  interface AsyncAssistant {
    CompletableFuture<String> chat(String message);
  }

  interface StreamAssistant {
    TokenStream chat(String message);
  }

  interface ComplexAssistant {
    Map<String, String> chat(String message);
  }

  static final TrustedContext CONTEXT =
      new TrustedContext("agent", "tenant-a", "principal", Set.of("read"));
  static final ParameterRule PARAMS =
      ParameterRule.object(Map.of("tenant", ParameterRule.string("tenantId")), "tenant");

  static StructuredPolicy.Builder policy() {
    return StructuredPolicy.builder("v1")
        .tool("lookup", new ToolRule(Set.of("read"), PARAMS, false));
  }

  static AgentControl control(StructuredPolicy p, List<AuditRecord> audit) {
    return AgentControl.builder(p).audit(audit::add).build();
  }

  static ChatResponse text(String text) {
    return ChatResponse.builder()
        .aiMessage(AiMessage.from(text))
        .tokenUsage(new TokenUsage(1, 1))
        .build();
  }

  static ToolExecutionRequest call(String tenant) {
    return ToolExecutionRequest.builder()
        .id("call-1")
        .name("lookup")
        .arguments(JsonValues.write(Map.of("tenant", tenant)))
        .build();
  }

  static class Model implements ChatModel {
    final AtomicInteger calls = new AtomicInteger();
    final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());
    final Function<ChatRequest, ChatResponse> responder;

    Model(Function<ChatRequest, ChatResponse> responder) {
      this.responder = responder;
    }

    @Override
    public ChatResponse doChat(ChatRequest r) {
      calls.incrementAndGet();
      requests.add(r);
      return responder.apply(r);
    }
  }

  static Model loop(String tenant) {
    return new Model(
        r ->
            r.messages().get(r.messages().size() - 1) instanceof ToolExecutionResultMessage m
                ? text(m.text())
                : ChatResponse.builder().aiMessage(AiMessage.from(call(tenant))).build());
  }

  static class Tools {
    final AtomicInteger effects = new AtomicInteger();
    String received;

    @Tool
    String lookup(String tenant) {
      effects.incrementAndGet();
      received = tenant;
      return "secret-answer";
    }
  }

  @Test
  void eightPointsThroughRealAiServicesAndTool() {
    var audit = new ArrayList<AuditRecord>();
    var tools = new Tools();
    var model = loop("tenant-a");
    try (var c = control(policy().build(), audit);
        var s = c.openSession(CONTEXT)) {
      var assistant =
          new LangChain4jGovernance(c)
              .aiServices(Assistant.class)
              .chatModel(model)
              .tools(tools)
              .build();
      assertEquals("secret-answer", assistant.chat("lookup"));
      assertEquals(1, tools.effects.get());
      assertEquals(2, s.budgetSnapshot().modelCalls());
      assertEquals(1, s.budgetSnapshot().toolCalls());
      assertNull(s.budgetSnapshot().tokens());
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
            InterventionPoint.PRE_MODEL_CALL,
            InterventionPoint.POST_MODEL_CALL,
            InterventionPoint.PRE_TOOL_CALL,
            InterventionPoint.POST_TOOL_CALL,
            InterventionPoint.PRE_MODEL_CALL,
            InterventionPoint.POST_MODEL_CALL,
            InterventionPoint.OUTPUT,
            InterventionPoint.AGENT_SHUTDOWN),
        points);
  }

  @Test
  void permissionTenantAndUnknownToolsProduceZeroEffects() {
    for (boolean foreign : List.of(true, false)) {
      var tools = new Tools();
      var model = loop(foreign ? "tenant-b" : "tenant-a");
      var p = foreign ? policy().build() : StructuredPolicy.builder("v1").build();
      try (var c = control(p, new ArrayList<>());
          var s = c.openSession(CONTEXT)) {
        var assistant =
            new LangChain4jGovernance(c)
                .aiServices(Assistant.class)
                .chatModel(model)
                .tools(tools)
                .build();
        assertThrows(RuntimeException.class, () -> assistant.chat("lookup"));
        assertEquals(0, tools.effects.get());
        assertEquals(1, model.calls.get());
      }
    }
  }

  @Test
  void swallowedDenialCannotResumeModelOrImmediateReturn() {
    for (boolean immediate : List.of(true, false)) {
      var effects = new AtomicInteger();
      var model = loop("tenant-b");
      var spec = ToolSpecifications.toolSpecificationsFrom(Tools.class).get(0);
      var tool =
          AiServiceTool.builder()
              .toolSpecification(spec)
              .toolExecutor(
                  (r, id) -> {
                    effects.incrementAndGet();
                    return "unsafe";
                  })
              .returnBehavior(immediate ? ReturnBehavior.IMMEDIATE : ReturnBehavior.TO_LLM)
              .build();
      try (var c = control(policy().build(), new ArrayList<>());
          var s = c.openSession(CONTEXT)) {
        var assistant =
            new LangChain4jGovernance(c)
                .aiServices(Assistant.class)
                .chatModel(model)
                .tools(List.of(tool))
                .toolExecutionErrorHandler((e, ctx) -> ToolErrorHandlerResult.text("ignored error"))
                .build();
        assertThrows(RuntimeException.class, () -> assistant.chat("lookup"));
        assertEquals(0, effects.get());
        assertEquals(1, model.calls.get());
        assertThrows(GovernanceException.class, s::checkActive);
      }
    }
  }

  @Test
  void transformedToolResultNeverReachesNextModelInOriginalForm() {
    var tools = new Tools();
    var model = loop("tenant-a");
    var p =
        policy()
            .content(
                new ContentRule(
                    "redact",
                    Set.of(InterventionPoint.POST_TOOL_CALL),
                    List.of(),
                    Map.of("secret", "masked")))
            .build();
    try (var c = control(p, new ArrayList<>());
        var s = c.openSession(CONTEXT)) {
      var assistant =
          new LangChain4jGovernance(c)
              .aiServices(Assistant.class)
              .chatModel(model)
              .tools(tools)
              .build();
      assertEquals("masked-answer", assistant.chat("lookup"));
      assertEquals(
          "masked-answer",
          ((ToolExecutionResultMessage) model.requests.get(1).messages().get(2)).text());
    }
  }

  @Test
  void effectiveArgumentsReachDelegateWithoutChangingCallId() {
    ParameterRule params =
        ParameterRule.object(
            Map.of("tenant", ParameterRule.string("tenantId"), "query", ParameterRule.string(null)),
            "tenant",
            "query");
    var p =
        StructuredPolicy.builder("v1")
            .tool("lookup", new ToolRule(Set.of(), params, false))
            .content(
                new ContentRule(
                    "rewrite",
                    Set.of(InterventionPoint.PRE_TOOL_CALL),
                    List.of(),
                    Map.of("old", "new")))
            .build();
    try (var c = control(p, new ArrayList<>());
        var s = c.openSession(CONTEXT)) {
      var original =
          ToolExecutionRequest.builder()
              .id("original-id")
              .name("lookup")
              .arguments("{\"tenant\":\"tenant-a\",\"query\":\"old\"}")
              .build();
      var tool =
          new LangChain4jGovernance(c)
              .guardTool(
                  "lookup",
                  (r, id) -> {
                    assertEquals("original-id", r.id());
                    assertEquals("new", JsonValues.parseObject(r.arguments()).get("query"));
                    return "ok";
                  });
      assertEquals("ok", tool.execute(original, "memory"));
      assertEquals(1, s.budgetSnapshot().toolCalls());
    }
  }

  @Test
  void bothToolEntryPointsAndContextPreserved() {
    var count = new AtomicInteger();
    var params = InvocationParameters.from("trusted", "host-value");
    var context =
        InvocationContext.builder().chatMemoryId("memory").invocationParameters(params).build();
    ToolExecutor delegate =
        new ToolExecutor() {
          public String execute(ToolExecutionRequest r, Object id) {
            assertEquals("memory", id);
            count.incrementAndGet();
            return "text";
          }

          public ToolExecutionResult executeWithContext(
              ToolExecutionRequest r, InvocationContext ctx) {
            assertSame(context, ctx);
            assertSame(params, ctx.invocationParameters());
            count.incrementAndGet();
            return ToolExecutionResult.builder()
                .resultText("text")
                .result("text")
                .attributes(Map.of("safe", "metadata"))
                .build();
          }
        };
    try (var c = control(policy().build(), new ArrayList<>());
        var s = c.openSession(CONTEXT)) {
      var tool = new LangChain4jGovernance(c).guardTool("lookup", delegate);
      assertEquals("text", tool.execute(call("tenant-a"), "memory"));
      var result = tool.executeWithContext(call("tenant-a"), context);
      assertEquals("text", result.result());
      assertEquals(Map.of("safe", "metadata"), result.attributes());
      assertEquals(2, count.get());
      assertEquals(2, s.budgetSnapshot().toolCalls());
    }
  }

  @Test
  void toolResultSideChannelsAlsoRewritten() {
    var p =
        policy()
            .content(
                new ContentRule(
                    "mask",
                    Set.of(InterventionPoint.POST_TOOL_CALL),
                    List.of(),
                    Map.of("secret", "masked")))
            .build();
    ToolExecutor raw =
        new ToolExecutor() {
          public String execute(ToolExecutionRequest r, Object id) {
            throw new AssertionError();
          }

          public ToolExecutionResult executeWithContext(
              ToolExecutionRequest r, InvocationContext ctx) {
            return ToolExecutionResult.builder()
                .resultText("secret")
                .result("secret")
                .attributes(Map.of("note", "secret"))
                .build();
          }
        };
    try (var c = control(p, new ArrayList<>());
        var s = c.openSession(CONTEXT)) {
      var result =
          new LangChain4jGovernance(c)
              .guardTool("lookup", raw)
              .executeWithContext(call("tenant-a"), null);
      assertEquals("masked", result.resultText());
      assertEquals("masked", result.result());
      assertEquals("masked", result.attributes().get("note"));
    }
  }

  static class InjectedTools {
    @Tool
    String lookup(String tenant, @ToolMemoryId String memoryId) {
      return tenant + ":" + memoryId;
    }
  }

  @Test
  void annotatedToolKeepsTrustedInjectedMemoryId() {
    try (var c = control(policy().build(), new ArrayList<>());
        var s = c.openSession(CONTEXT)) {
      var assistant =
          new LangChain4jGovernance(c)
              .aiServices(WithMemory.class)
              .chatModel(loop("tenant-a"))
              .chatMemoryProvider(
                  id -> dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(10))
              .tools(new InjectedTools())
              .build();
      assertEquals("tenant-a:trusted-memory", assistant.chat("trusted-memory", "lookup"));
    }
  }

  @Test
  void dynamicAndImmediateToolsAreGoverned() {
    for (boolean immediate : List.of(true, false)) {
      var tools = new Tools();
      var model = loop("tenant-a");
      var spec = ToolSpecifications.toolSpecificationsFrom(Tools.class).get(0);
      try (var c = control(policy().build(), new ArrayList<>());
          var s = c.openSession(CONTEXT)) {
        var assistant =
            new LangChain4jGovernance(c)
                .aiServices(Assistant.class)
                .chatModel(model)
                .toolProvider(
                    r ->
                        ToolProviderResult.builder()
                            .add(
                                spec,
                                new ToolExecutor() {
                                  public String execute(ToolExecutionRequest request, Object id) {
                                    return tools.lookup("tenant-a");
                                  }

                                  public ToolExecutionResult executeWithContext(
                                      ToolExecutionRequest request, InvocationContext context) {
                                    String value = tools.lookup("tenant-a");
                                    return ToolExecutionResult.builder()
                                        .result(value)
                                        .resultText(value)
                                        .build();
                                  }
                                },
                                immediate ? ReturnBehavior.IMMEDIATE : ReturnBehavior.TO_LLM)
                            .build())
                .build();
        assertEquals("secret-answer", assistant.chat("lookup"));
        assertEquals(1, tools.effects.get());
        assertEquals(immediate ? 1 : 2, model.calls.get());
      }
    }
  }

  @Test
  void inputRewritingAndOutputGuardBeforeDisclosure() {
    var p =
        policy()
            .content(
                new ContentRule(
                    "input_mask",
                    Set.of(InterventionPoint.INPUT),
                    List.of(),
                    Map.of("original", "replaced")))
            .content(
                new ContentRule(
                    "output_mask",
                    Set.of(InterventionPoint.OUTPUT),
                    List.of(),
                    Map.of("secret", "masked")))
            .build();
    var model =
        new Model(
            r -> {
              assertEquals("replaced", ((UserMessage) r.messages().get(0)).singleText());
              return text("secret");
            });
    try (var c = control(p, new ArrayList<>());
        var s = c.openSession(CONTEXT)) {
      var a = new LangChain4jGovernance(c).aiServices(Assistant.class).chatModel(model).build();
      assertEquals("masked", a.chat("original"));
    }
  }

  @Test
  void systemHistoryThinkingAndToolArgumentsAreChecked() {
    var p =
        policy()
            .content(
                new ContentRule(
                    "blocked",
                    Set.of(InterventionPoint.PRE_MODEL_CALL, InterventionPoint.POST_MODEL_CALL),
                    List.of("blocked-text"),
                    Map.of()))
            .build();
    for (int mode = 0; mode < 4; mode++) {
      final int m = mode;
      var model =
          new Model(
              r ->
                  m == 2
                      ? ChatResponse.builder()
                          .aiMessage(
                              AiMessage.builder().text("ok").thinking("blocked-text").build())
                          .build()
                      : m == 3
                          ? ChatResponse.builder()
                              .aiMessage(
                                  AiMessage.from(
                                      ToolExecutionRequest.builder()
                                          .name("lookup")
                                          .id("id")
                                          .arguments("{\"tenant\":\"blocked-text\"}")
                                          .build()))
                              .build()
                          : text("ok"));
      try (var c = control(p, new ArrayList<>());
          var s = c.openSession(CONTEXT)) {
        var guarded = new LangChain4jGovernance(c).guardModel(model);
        List<ChatMessage> messages =
            m == 0
                ? List.of(SystemMessage.from("blocked-text"), UserMessage.from("hi"))
                : m == 1
                    ? List.of(UserMessage.from("blocked-text"), UserMessage.from("hi"))
                    : List.of(UserMessage.from("hi"));
        assertThrows(GovernanceException.class, () -> guarded.chat(messages));
        assertEquals(m < 2 ? 0 : 1, model.calls.get());
      }
    }
  }

  @Test
  void postToolDenyHidesResultButCannotUndoEffect() {
    var p =
        policy()
            .content(
                new ContentRule(
                    "blocked",
                    Set.of(InterventionPoint.POST_TOOL_CALL),
                    List.of("secret"),
                    Map.of()))
            .build();
    var tools = new Tools();
    var model = loop("tenant-a");
    try (var c = control(p, new ArrayList<>());
        var s = c.openSession(CONTEXT)) {
      var a =
          new LangChain4jGovernance(c)
              .aiServices(Assistant.class)
              .chatModel(model)
              .tools(tools)
              .build();
      assertThrows(RuntimeException.class, () -> a.chat("lookup"));
      assertEquals(1, tools.effects.get());
      assertEquals(1, model.calls.get());
    }
  }

  @Test
  void modelSyncOverloadsNoDuplicateCountsAndAiMessageOutput() {
    var model = new Model(r -> text("ok"));
    try (var c = control(policy().build(), new ArrayList<>());
        var s = c.openSession(CONTEXT)) {
      var g = new LangChain4jGovernance(c);
      var m = g.guardModel(model);
      assertEquals("ok", m.chat("x"));
      m.chat(List.of(UserMessage.from("x")));
      m.chat(UserMessage.from("x"));
      m.doChat(ChatRequest.builder().messages(UserMessage.from("x")).build());
      m.chat(
          ChatRequest.builder().messages(UserMessage.from("x")).build(), ChatRequestOptions.EMPTY);
      var a = g.aiServices(AiAssistant.class).chatModel(model).build();
      assertEquals("ok", a.chat("x").text());
      assertEquals(6, s.budgetSnapshot().modelCalls());
    }
  }

  @Test
  void missingSessionAndUnsupportedAiServiceShapes() {
    try (var c = control(policy().build(), new ArrayList<>())) {
      var g = new LangChain4jGovernance(c);
      var m = new Model(r -> text("ok"));
      var a = g.aiServices(Assistant.class).chatModel(m).build();
      assertThrows(GovernanceException.class, () -> a.chat("x"));
      assertEquals(0, m.calls.get());
      assertNotNull(g.aiServices(AsyncAssistant.class).chatModel(m).build());
      assertThrows(
          GovernanceException.class,
          () -> g.aiServices(StreamAssistant.class).chatModel(m).build());
      assertThrows(
          GovernanceException.class,
          () -> g.aiServices(ComplexAssistant.class).chatModel(m).build());
    }
  }

  @Test
  void multimodalAndAsyncRefusedWithoutDelegateCalls() {
    var model = new Model(r -> text("ok"));
    try (var c = control(policy().build(), new ArrayList<>());
        var s = c.openSession(CONTEXT)) {
      var m = new LangChain4jGovernance(c).guardModel(model);
      assertThrows(
          GovernanceException.class,
          () -> m.chat(UserMessage.from(ImageContent.from("https://example.invalid/image.png"))));
      assertEquals(0, model.calls.get());
    }
    try (var c = control(policy().build(), new ArrayList<>());
        var s = c.openSession(CONTEXT)) {
      var m = new LangChain4jGovernance(c).guardModel(model);
      assertThrows(CompletionException.class, () -> m.chatAsync("x").join());
      assertEquals(0, model.calls.get());
    }
  }

  @Test
  void builderRequiresAllPoints() {
    try (var c =
        control(
            StructuredPolicy.builder("v1")
                .points(Set.of(InterventionPoint.PRE_MODEL_CALL, InterventionPoint.POST_MODEL_CALL))
                .build(),
            new ArrayList<>())) {
      assertThrows(
          GovernanceException.class,
          () ->
              new LangChain4jGovernance(c)
                  .aiServices(Assistant.class)
                  .chatModel(new Model(r -> text("ok")))
                  .build());
    }
  }

  @Test
  void concurrentTenantsHaveIndependentSessions() throws Exception {
    var audit = Collections.synchronizedList(new ArrayList<AuditRecord>());
    try (var c = control(policy().build(), audit)) {
      var a =
          new LangChain4jGovernance(c)
              .aiServices(Assistant.class)
              .chatModel(new Model(r -> text("ok")))
              .build();
      var pool = Executors.newFixedThreadPool(6);
      try {
        var calls = new ArrayList<Future<?>>();
        for (int i = 0; i < 30; i++) {
          final int tenant = i;
          calls.add(
              pool.submit(
                  () -> {
                    try (var s =
                        c.openSession(
                            new TrustedContext("agent", "tenant-" + tenant, "u", Set.of()))) {
                      assertEquals("tenant-" + tenant, s.context().tenantId());
                      assertEquals("ok", a.chat("x"));
                      assertEquals(1, s.budgetSnapshot().modelCalls());
                    }
                    assertThrows(GovernanceException.class, c::currentSession);
                  }));
        }
        for (var call : calls) call.get();
      } finally {
        pool.shutdownNow();
      }
    }
    assertEquals(30, audit.stream().map(AuditRecord::sessionId).distinct().count());
  }

  @Test
  void finalOutputDenyNeverReturnsToCaller() {
    var p =
        policy()
            .content(
                new ContentRule(
                    "final_denied", Set.of(InterventionPoint.OUTPUT), List.of("secret"), Map.of()))
            .build();
    var model = new Model(r -> text("secret"));
    try (var c = control(p, new ArrayList<>());
        var session = c.openSession(CONTEXT)) {
      var a = new LangChain4jGovernance(c).aiServices(Assistant.class).chatModel(model).build();
      assertEquals(
          "final_denied", assertThrows(GovernanceException.class, () -> a.chat("x")).code());
      assertEquals(1, model.calls.get());
    }
  }

  @Test
  void modelTransformCannotAlterProtectedRoleAndReleasesBudget() {
    var p =
        policy()
            .content(
                new ContentRule(
                    "bad_role",
                    Set.of(InterventionPoint.PRE_MODEL_CALL),
                    List.of(),
                    Map.of("USER", "SYSTEM")))
            .build();
    var model = new Model(r -> text("ok"));
    try (var c = control(p, new ArrayList<>());
        var session = c.openSession(CONTEXT)) {
      assertThrows(
          GovernanceException.class,
          () -> new LangChain4jGovernance(c).guardModel(model).chat("x"));
      assertEquals(0, model.calls.get());
      assertEquals(0, session.budgetSnapshot().modelCalls());
    }
  }

  @Test
  void rawModelListenersAndDoubleWrappingRefused() {
    var model =
        new Model(r -> text("ok")) {
          public List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() {
            return List.of(new dev.langchain4j.model.chat.listener.ChatModelListener() {});
          }
        };
    try (var c = control(policy().build(), new ArrayList<>())) {
      var g = new LangChain4jGovernance(c);
      assertThrows(GovernanceException.class, () -> g.guardModel(model));
      var guarded = g.guardModel(new Model(r -> text("ok")));
      assertThrows(GovernanceException.class, () -> g.guardModel(guarded));
    }
  }

  @Test
  void malformedToolJsonIsAuditedBeforeEffects() {
    var audit = new ArrayList<AuditRecord>();
    var count = new AtomicInteger();
    try (var c = control(policy().build(), audit);
        var session = c.openSession(CONTEXT)) {
      var tool =
          new LangChain4jGovernance(c)
              .guardTool(
                  "lookup",
                  (r, id) -> {
                    count.incrementAndGet();
                    return "ok";
                  });
      assertThrows(
          GovernanceException.class,
          () ->
              tool.execute(
                  ToolExecutionRequest.builder()
                      .id("id")
                      .name("lookup")
                      .arguments("{\"tenant\":\"tenant-a\",\"tenant\":\"tenant-b\"}")
                      .build(),
                  null));
      assertEquals(0, count.get());
      assertTrue(audit.stream().anyMatch(r -> r.reasonCode().equals("invalid_json")));
    }
  }

  @Test
  void directToolAsyncAndComplexResultRefused() {
    try (var c = control(policy().build(), new ArrayList<>());
        var session = c.openSession(CONTEXT)) {
      var tool = new LangChain4jGovernance(c).guardTool("lookup", (r, id) -> "ok");
      assertThrows(
          CompletionException.class, () -> tool.executeAsync(call("tenant-a"), null).join());
    }
    try (var c = control(policy().build(), new ArrayList<>());
        var session = c.openSession(CONTEXT)) {
      var tool =
          new LangChain4jGovernance(c)
              .guardTool(
                  "lookup",
                  new ToolExecutor() {
                    public String execute(ToolExecutionRequest r, Object id) {
                      return "ok";
                    }

                    public ToolExecutionResult executeWithContext(
                        ToolExecutionRequest r, InvocationContext ctx) {
                      return ToolExecutionResult.builder()
                          .resultText("ok")
                          .result(Map.of("secret", "private"))
                          .build();
                    }
                  });
      assertEquals(
          "complex_tool_result_unsupported",
          assertThrows(
                  GovernanceException.class, () -> tool.executeWithContext(call("tenant-a"), null))
              .code());
    }
  }
}
