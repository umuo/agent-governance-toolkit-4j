package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.*;
import io.agt.core.*;
import io.agt.core.audit.AuditRecord;
import io.agt.core.policy.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class StreamingGovernanceTest {
  interface Assistant {
    TokenStream chat(String message);
  }

  interface Mixed {
    TokenStream stream(String message);

    String chat(String message);
  }

  static final TrustedContext CTX = new TrustedContext("agent", "tenant", "user", Set.of("read"));

  static class Model implements StreamingChatModel {
    final AtomicInteger calls = new AtomicInteger();
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    final BiConsumer<ChatRequest, StreamingChatResponseHandler> respond;

    Model(BiConsumer<ChatRequest, StreamingChatResponseHandler> respond) {
      this.respond = respond;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
      calls.incrementAndGet();
      requests.add(request);
      respond.accept(request, handler);
    }
  }

  static class Tools {
    final AtomicInteger effects = new AtomicInteger();

    @Tool
    String lookup(String tenant) {
      effects.incrementAndGet();
      return "secret-tool";
    }
  }

  static class ImmediateTools {
    final AtomicInteger effects = new AtomicInteger();

    @Tool(returnBehavior = ReturnBehavior.IMMEDIATE)
    String lookup(String tenant) {
      effects.incrementAndGet();
      return "secret-tool";
    }
  }

  static StructuredPolicy.Builder policy() {
    return StructuredPolicy.builder("stream-v3")
        .tool(
            "lookup",
            new ToolRule(
                Set.of("read"),
                ParameterRule.object(Map.of("tenant", ParameterRule.string("tenantId")), "tenant"),
                false));
  }

  static ChatResponse text(String value) {
    return ChatResponse.builder().aiMessage(AiMessage.from(value)).build();
  }

  static ChatResponse call(String tenant) {
    return ChatResponse.builder()
        .aiMessage(
            AiMessage.from(
                ToolExecutionRequest.builder()
                    .id("id-1")
                    .name("lookup")
                    .arguments(JsonValues.write(Map.of("tenant", tenant)))
                    .build()))
        .build();
  }

  static String code(CompletableFuture<?> future) {
    Throwable error = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    while (error.getCause() != null
        && (error instanceof ExecutionException || error instanceof CompletionException))
      error = error.getCause();
    return error instanceof GovernanceException g ? g.code() : error.getClass().getSimpleName();
  }

  static BufferedTokenStream stream(AgentControl control, Model model) {
    return LangChain4jGovernance.buffered(
        new LangChain4jGovernance(control)
            .aiServices(Assistant.class)
            .streamingChatModel(model)
            .build()
            .chat("hello"));
  }

  @Test
  void bufferUntilFinalOutputAndRewriteSplitSecret() throws Exception {
    AtomicReference<StreamingChatResponseHandler> source = new AtomicReference<>();
    CountDownLatch ready = new CountDownLatch(1);
    var model =
        new Model(
            (r, h) -> {
              source.set(h);
              ready.countDown();
            });
    var p =
        policy()
            .content(
                new ContentRule(
                    "redact",
                    Set.of(InterventionPoint.OUTPUT),
                    List.of(),
                    Map.of("secret", "masked")))
            .build();
    try (var control = AgentControl.builder(p).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var values = new CopyOnWriteArrayList<String>();
      var stream = stream(control, model);
      stream
          .onPartialResponse(values::add)
          .onCompleteResponse(r -> values.add(r.aiMessage().text()));
      assertEquals(0, model.calls.get());
      stream.start();
      assertTrue(ready.await(3, TimeUnit.SECONDS));
      source.get().onPartialResponse("sec");
      source.get().onPartialResponse("ret");
      assertTrue(values.isEmpty());
      source.get().onCompleteResponse(text("secret"));
      assertEquals("masked", stream.completion().get(5, TimeUnit.SECONDS).aiMessage().text());
      assertEquals(List.of("masked", "masked"), values);
      source.get().onCompleteResponse(text("late-secret"));
      assertEquals(2, values.size());
      assertEquals(1, session.budgetSnapshot().modelCalls());
      assertThrows(GovernanceException.class, stream::start);
    }
  }

  @Test
  void postModelDenyPreventsEarlyToolExecution() throws Exception {
    var tools = new Tools();
    CountDownLatch early = new CountDownLatch(1);
    AtomicReference<StreamingChatResponseHandler> source = new AtomicReference<>();
    var model =
        new Model(
            (r, h) -> {
              source.set(h);
              h.onCompleteToolCall(
                  new CompleteToolCall(
                      0, call("tenant").aiMessage().toolExecutionRequests().get(0)));
              early.countDown();
            });
    var p =
        policy()
            .content(
                new ContentRule(
                    "deny", Set.of(InterventionPoint.POST_MODEL_CALL), List.of("lookup"), Map.of()))
            .build();
    try (var control = AgentControl.builder(p).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var stream =
          LangChain4jGovernance.buffered(
              new LangChain4jGovernance(control)
                  .aiServices(Assistant.class)
                  .streamingChatModel(model)
                  .tools(tools)
                  .build()
                  .chat("hello"));
      var seen = new AtomicInteger();
      stream
          .onPartialResponse(t -> seen.incrementAndGet())
          .onCompleteResponse(r -> seen.incrementAndGet())
          .start();
      assertTrue(early.await(3, TimeUnit.SECONDS));
      assertEquals(0, tools.effects.get());
      source.get().onCompleteResponse(call("tenant"));
      assertEquals("deny", code(stream.completion()));
      assertEquals(0, tools.effects.get());
      assertEquals(0, seen.get());
    }
  }

  @Test
  void toolLoopPreservesIdAndActualRedaction() throws Exception {
    var tools = new Tools();
    var model =
        new Model(
            (r, h) ->
                h.onCompleteResponse(
                    r.messages().size() == 1 ? call("tenant") : text("secret-final")));
    var audit = new CopyOnWriteArrayList<AuditRecord>();
    var p =
        policy()
            .content(
                new ContentRule(
                    "redact",
                    Set.of(InterventionPoint.POST_TOOL_CALL, InterventionPoint.OUTPUT),
                    List.of(),
                    Map.of("secret", "masked")))
            .build();
    try (var control = AgentControl.builder(p).audit(audit::add).build();
        var session = control.openSession(CTX)) {
      var stream =
          LangChain4jGovernance.buffered(
              new LangChain4jGovernance(control)
                  .aiServices(Assistant.class)
                  .streamingChatModel(model)
                  .tools(tools)
                  .build()
                  .chat("hello"));
      stream.start();
      assertEquals("masked-final", stream.completion().get(5, TimeUnit.SECONDS).aiMessage().text());
      assertEquals(1, tools.effects.get());
      assertEquals(2, model.calls.get());
      var result = (ToolExecutionResultMessage) model.requests.get(1).messages().get(2);
      assertEquals("id-1", result.id());
      assertEquals("masked-tool", result.text());
      assertEquals(2, session.budgetSnapshot().modelCalls());
      assertEquals(1, session.budgetSnapshot().toolCalls());
    }
    assertEquals(
        EnumSet.allOf(InterventionPoint.class),
        EnumSet.copyOf(audit.stream().map(AuditRecord::point).toList()));
  }

  @Test
  void deniedToolCannotRecoverThroughFrameworkErrorHandler() {
    var tools = new Tools();
    var model = new Model((r, h) -> h.onCompleteResponse(call("other")));
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var stream =
          LangChain4jGovernance.buffered(
              new LangChain4jGovernance(control)
                  .aiServices(Assistant.class)
                  .streamingChatModel(model)
                  .tools(tools)
                  .toolExecutionErrorHandler((e, c) -> ToolErrorHandlerResult.text("recovered"))
                  .build()
                  .chat("hello"));
      stream.start();
      String code = code(stream.completion());
      assertTrue(Set.of("session_failed", "context_binding_denied").contains(code), code);
      assertEquals(0, tools.effects.get());
      assertEquals(1, model.calls.get());
    }
  }

  @Test
  void outputDenyWithholdsAllCallbacks() {
    var model =
        new Model(
            (r, h) -> {
              h.onPartialResponse("secret");
              h.onCompleteResponse(text("secret"));
            });
    var p =
        policy()
            .content(
                new ContentRule(
                    "deny", Set.of(InterventionPoint.OUTPUT), List.of("secret"), Map.of()))
            .build();
    try (var control = AgentControl.builder(p).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var seen = new AtomicInteger();
      var stream = stream(control, model);
      stream
          .onPartialResponse(t -> seen.incrementAndGet())
          .onCompleteResponse(r -> seen.incrementAndGet())
          .start();
      assertEquals("deny", code(stream.completion()));
      assertEquals(0, seen.get());
    }
  }

  @Test
  void cancellationPropagatesHandleAndDiscardsLateResponse() throws Exception {
    var cancelled = new AtomicBoolean();
    var handle =
        new StreamingHandle() {
          public void cancel() {
            cancelled.set(true);
          }

          public boolean isCancelled() {
            return cancelled.get();
          }
        };
    AtomicReference<StreamingChatResponseHandler> source = new AtomicReference<>();
    var ready = new CountDownLatch(1);
    var model =
        new Model(
            (r, h) -> {
              source.set(h);
              h.onPartialResponse(
                  new PartialResponse("hidden"), new PartialResponseContext(handle));
              ready.countDown();
            });
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var seen = new AtomicInteger();
      var stream = stream(control, model);
      stream.onPartialResponse(t -> seen.incrementAndGet()).start();
      assertTrue(ready.await(3, TimeUnit.SECONDS));
      assertTrue(stream.cancel());
      for (int i = 0; i < 100 && !cancelled.get(); i++) Thread.sleep(10);
      assertTrue(cancelled.get());
      source.get().onCompleteResponse(text("late"));
      assertEquals(0, seen.get());
      assertTrue(session.budgetSnapshot().failed());
    }
  }

  @Test
  void limitsFailWithoutDeliveryAndCancelHandle() {
    var cancelled = new AtomicBoolean();
    var model =
        new Model(
            (r, h) ->
                h.onPartialResponse(
                    new PartialResponse("too-long"),
                    new PartialResponseContext(
                        new StreamingHandle() {
                          public void cancel() {
                            cancelled.set(true);
                          }

                          public boolean isCancelled() {
                            return cancelled.get();
                          }
                        })));
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var stream =
          LangChain4jGovernance.buffered(
              new LangChain4jGovernance(control)
                  .aiServices(Assistant.class)
                  .streamingChatModel(model)
                  .streamingLimits(new StreamingLimits(2, 100))
                  .build()
                  .chat("hi"));
      stream.start();
      assertEquals("stream_capacity_exceeded", code(stream.completion()));
      assertTrue(cancelled.get());
    }
  }

  @Test
  void eventLimitIncludesSuppressedRawEvents() {
    var model =
        new Model(
            (r, h) -> {
              h.onUnmappedRawEvent("secret");
              h.onUnmappedRawEvent("secret");
              h.onCompleteResponse(text("ok"));
            });
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var stream =
          LangChain4jGovernance.buffered(
              new LangChain4jGovernance(control)
                  .aiServices(Assistant.class)
                  .streamingChatModel(model)
                  .streamingLimits(new StreamingLimits(1000, 1))
                  .build()
                  .chat("hi"));
      stream.start();
      assertEquals("stream_capacity_exceeded", code(stream.completion()));
    }
  }

  @Test
  void terminalErrorsSanitizedAndSideChannelsRefused() {
    var model = new Model((r, h) -> h.onError(new IllegalStateException("secret-password")));
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var stream = stream(control, model);
      assertThrows(GovernanceException.class, () -> stream.onRetrieved(r -> {}));
      assertThrows(GovernanceException.class, () -> stream.onIntermediateResponse(r -> {}));
      assertThrows(GovernanceException.class, () -> stream.onToolExecuted(r -> {}));
      assertThrows(GovernanceException.class, () -> stream.onPartialThinking(r -> {}));
      assertThrows(GovernanceException.class, () -> stream.onPartialToolCall(r -> {}));
      assertThrows(GovernanceException.class, () -> stream.onUnmappedRawEvent(r -> {}));
      assertThrows(GovernanceException.class, stream::ignoreErrors);
      var error = new AtomicReference<Throwable>();
      stream.onError(error::set).start();
      assertEquals("stream_failed", code(stream.completion()));
      for (int i = 0; i < 100 && error.get() == null; i++) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          fail(e);
        }
      }
      assertNotNull(error.get());
      assertFalse(error.get().toString().contains("secret-password"));
    }
  }

  @Test
  void configurationFailsClearlyAndReactiveCannotBypass() {
    var model = new Model((r, h) -> h.onCompleteResponse(text("ok")));
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build()) {
      var adapter = new LangChain4jGovernance(control);
      assertThrows(
          GovernanceException.class,
          () -> adapter.aiServices(Mixed.class).streamingChatModel(model).build());
      assertThrows(
          GovernanceException.class,
          () ->
              adapter
                  .aiServices(Assistant.class)
                  .streamingChatModel(model)
                  .executeToolsConcurrently()
                  .build());
      assertThrows(
          GovernanceException.class,
          () ->
              adapter
                  .guardStreamingModel(model)
                  .chat(ChatRequest.builder().messages(UserMessage.from("hi")).build()));
      assertEquals(0, model.calls.get());
    }
  }

  @Test
  void closeAndExpiryFinishPendingStreams() throws Exception {
    for (boolean expire : List.of(false, true)) {
      var ready = new CountDownLatch(1);
      var model = new Model((r, h) -> ready.countDown());
      try (var control =
              AgentControl.builder(policy().build())
                  .budget(new BudgetLimits(5, 5, expire ? 500 : 10000))
                  .audit(r -> {})
                  .build();
          var session = control.openSession(CTX)) {
        var stream = stream(control, model);
        stream.start();
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        if (!expire) {
          session.close();
        }
        assertEquals(expire ? "session_expired" : "session_closed", code(stream.completion()));
      }
    }
  }

  @Test
  void cancellationInsidePartialStopsCompleteCallback() throws Exception {
    var model = new Model((r, h) -> h.onCompleteResponse(text("ok")));
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var completed = new AtomicInteger();
      var stream = stream(control, model);
      stream
          .onPartialResponseWithContext((p, c) -> c.streamingHandle().cancel())
          .onCompleteResponse(r -> completed.incrementAndGet())
          .start();
      assertEquals("CancellationException", code(stream.completion()));
      assertEquals(0, completed.get());
    }
  }

  @Test
  void callbackFailureTerminatesSessionAndSanitizesError() {
    var model = new Model((r, h) -> h.onCompleteResponse(text("ok")));
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var stream = stream(control, model);
      stream
          .onPartialResponse(
              t -> {
                throw new IllegalArgumentException("secret-callback");
              })
          .start();
      assertEquals("stream_callback_failed", code(stream.completion()));
      assertTrue(session.budgetSnapshot().failed());
    }
  }

  @Test
  void approvalAndImmediateReturnKeepNativeBehavior() throws Exception {
    for (boolean approve : List.of(false, true)) {
      var model = new Model((r, h) -> h.onCompleteResponse(call("tenant")));
      var tools = new ImmediateTools();
      var p =
          StructuredPolicy.builder("approval-stream")
              .tool(
                  "lookup",
                  new ToolRule(
                      Set.of("read"),
                      ParameterRule.object(
                          Map.of("tenant", ParameterRule.string("tenantId")), "tenant"),
                      true))
              .build();
      try (var control =
              AgentControl.builder(p)
                  .approvalResolver(
                      r ->
                          CompletableFuture.completedFuture(
                              approve
                                  ? io.agt.core.approval.ApprovalResponse.allow(r)
                                  : io.agt.core.approval.ApprovalResponse.deny(r)))
                  .audit(r -> {})
                  .build();
          var session = control.openSession(CTX)) {
        var stream =
            LangChain4jGovernance.buffered(
                new LangChain4jGovernance(control)
                    .aiServices(Assistant.class)
                    .streamingChatModel(model)
                    .tools(tools)
                    .build()
                    .chat("hi"));
        stream.start();
        if (approve)
          assertTrue(
              stream.completion().get(5, TimeUnit.SECONDS).aiMessage().hasToolExecutionRequests());
        else assertEquals("approval_denied", code(stream.completion()));
        assertEquals(approve ? 1 : 0, tools.effects.get());
        assertEquals(1, model.calls.get());
      }
    }
  }

  @Test
  void modelBudgetCountsEachStreamRound() {
    var model = new Model((r, h) -> h.onCompleteResponse(call("tenant")));
    var tools = new Tools();
    try (var control =
            AgentControl.builder(policy().build())
                .budget(new BudgetLimits(1, 5, 10000))
                .audit(r -> {})
                .build();
        var session = control.openSession(CTX)) {
      var stream =
          LangChain4jGovernance.buffered(
              new LangChain4jGovernance(control)
                  .aiServices(Assistant.class)
                  .streamingChatModel(model)
                  .tools(tools)
                  .build()
                  .chat("hi"));
      stream.start();
      assertEquals("model_budget_exceeded", code(stream.completion()));
      assertEquals(1, model.calls.get());
      assertEquals(1, tools.effects.get());
    }
  }

  @Test
  void fullFinalPayloadCannotBypassStreamLimit() {
    var model = new Model((r, h) -> h.onCompleteResponse(text("large".repeat(100))));
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var stream =
          LangChain4jGovernance.buffered(
              new LangChain4jGovernance(control)
                  .aiServices(Assistant.class)
                  .streamingChatModel(model)
                  .streamingLimits(new StreamingLimits(100, 100))
                  .build()
                  .chat("hi"));
      stream.start();
      assertEquals("stream_capacity_exceeded", code(stream.completion()));
    }
  }

  @Test
  void inputAndSystemMessagesCheckedBeforeNetwork() {
    var model = new Model((r, h) -> h.onCompleteResponse(text("ok")));
    var p =
        policy()
            .content(
                new ContentRule(
                    "pre-deny",
                    Set.of(InterventionPoint.PRE_MODEL_CALL),
                    List.of("restricted"),
                    Map.of()))
            .build();
    try (var control = AgentControl.builder(p).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var stream =
          LangChain4jGovernance.buffered(
              new LangChain4jGovernance(control)
                  .aiServices(Assistant.class)
                  .systemMessage("restricted")
                  .streamingChatModel(model)
                  .build()
                  .chat("hi"));
      stream.start();
      assertEquals("pre-deny", code(stream.completion()));
      assertEquals(0, model.calls.get());
      assertEquals(0, session.budgetSnapshot().modelCalls());
    }
  }

  @Test
  void startOnAnotherThreadUsesCapturedSession() throws Exception {
    var model = new Model((r, h) -> h.onCompleteResponse(text("ok")));
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var stream = stream(control, model);
      var same = new AtomicBoolean();
      stream.onCompleteResponse(r -> same.set(control.currentSession() == session));
      var starter = new Thread(stream::start);
      starter.start();
      starter.join(3000);
      assertEquals("ok", stream.completion().get(5, TimeUnit.SECONDS).aiMessage().text());
      assertTrue(same.get());
    }
  }

  @Test
  void deadlineResolvesResultWhileApplicationCallbackIsBlocked() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var completed = new AtomicInteger();
    var model = new Model((r, h) -> h.onCompleteResponse(text("safe")));
    try (var control =
            AgentControl.builder(policy().build())
                .budget(new BudgetLimits(5, 5, 500))
                .audit(r -> {})
                .build();
        var session = control.openSession(CTX)) {
      var stream = stream(control, model);
      stream
          .onPartialResponse(
              t -> {
                entered.countDown();
                try {
                  release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              })
          .onCompleteResponse(r -> completed.incrementAndGet())
          .start();
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertEquals("session_expired", code(stream.completion()));
      release.countDown();
      assertEquals(0, completed.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void pendingUnstartedStreamFailsWhenSessionCloses() {
    var model = new Model((r, h) -> h.onCompleteResponse(text("ok")));
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var stream = stream(control, model);
      session.close();
      assertEquals("session_closed", code(stream.completion()));
      assertThrows(GovernanceException.class, stream::start);
      assertEquals(0, model.calls.get());
    }
  }

  @Test
  void dynamicExecutorUsesNativeContextAndGovernedResult() throws Exception {
    var model =
        new Model(
            (r, h) -> {
              var last = r.messages().get(r.messages().size() - 1);
              h.onCompleteResponse(
                  last instanceof ToolExecutionResultMessage result
                      ? text(result.text())
                      : call("tenant"));
            });
    var specification =
        ToolSpecifications.toolSpecificationFrom(
            Tools.class.getDeclaredMethod("lookup", String.class));
    var effects = new AtomicInteger();
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      ToolExecutor executor =
          new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest r, Object memoryId) {
              throw new AssertionError("Missing InvocationContext");
            }

            @Override
            public ToolExecutionResult executeWithContext(
                ToolExecutionRequest r, dev.langchain4j.invocation.InvocationContext context) {
              assertNotNull(context);
              assertEquals("id-1", r.id());
              assertSame(session, control.currentSession());
              effects.incrementAndGet();
              return ToolExecutionResult.builder().resultText("safe").build();
            }
          };
      var stream =
          LangChain4jGovernance.buffered(
              new LangChain4jGovernance(control)
                  .aiServices(Assistant.class)
                  .streamingChatModel(model)
                  .toolProvider(r -> new ToolProviderResult(Map.of(specification, executor)))
                  .build()
                  .chat("hi"));
      stream.start();
      assertEquals("safe", stream.completion().get(5, TimeUnit.SECONDS).aiMessage().text());
      assertEquals(1, effects.get());
    }
  }

  @Test
  void lowLevelDecoratorChecksBeforeReplayingAndRejectsRawListeners() throws Exception {
    var p =
        policy()
            .content(
                new ContentRule(
                    "post-redact",
                    Set.of(InterventionPoint.POST_MODEL_CALL),
                    List.of(),
                    Map.of("secret", "masked")))
            .build();
    var model =
        new Model(
            (r, h) -> {
              h.onPartialResponse("secret");
              h.onCompleteResponse(text("secret"));
            });
    try (var control = AgentControl.builder(p).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var done = new CompletableFuture<ChatResponse>();
      var values = new CopyOnWriteArrayList<String>();
      var guarded = new LangChain4jGovernance(control).guardStreamingModel(model);
      guarded.doChat(
          ChatRequest.builder().messages(UserMessage.from("hi")).build(),
          new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String value) {
              values.add(value);
            }

            @Override
            public void onCompleteResponse(ChatResponse value) {
              done.complete(value);
            }

            @Override
            public void onError(Throwable error) {
              done.completeExceptionally(error);
            }
          });
      assertEquals("masked", done.get(5, TimeUnit.SECONDS).aiMessage().text());
      assertEquals(List.of("masked"), values);
      assertEquals(1, session.budgetSnapshot().modelCalls());
      assertThrows(
          GovernanceException.class,
          () -> new LangChain4jGovernance(control).guardStreamingModel(guarded));
      StreamingChatModel withListener =
          new StreamingChatModel() {
            @Override
            public List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() {
              return List.of(new dev.langchain4j.model.chat.listener.ChatModelListener() {});
            }
          };
      assertThrows(
          GovernanceException.class,
          () -> new LangChain4jGovernance(control).guardStreamingModel(withListener));
    }
  }

  @Test
  void requiredOutputAuditFailureWithholdsStreamDelivery() {
    var model = new Model((r, h) -> h.onCompleteResponse(text("secret")));
    try (var control =
        AgentControl.builder(policy().build())
            .audit(
                r -> {
                  if (r.point() == InterventionPoint.OUTPUT) {
                    throw new java.io.IOException("sink-secret");
                  }
                })
            .build()) {
      var session = control.openSession(CTX);
      var delivered = new AtomicInteger();
      var stream = stream(control, model);
      stream
          .onPartialResponse(t -> delivered.incrementAndGet())
          .onCompleteResponse(r -> delivered.incrementAndGet())
          .start();
      assertEquals("audit_failed", code(stream.completion()));
      assertEquals(0, delivered.get());
      assertThrows(GovernanceException.class, session::close);
    }
  }

  @Test
  void evaluateOnlyRecordsWithoutApplyingStreamTransform() throws Exception {
    var audit = new CopyOnWriteArrayList<AuditRecord>();
    var model = new Model((r, h) -> h.onCompleteResponse(text("secret")));
    var p =
        policy()
            .content(
                new ContentRule(
                    "redact",
                    Set.of(InterventionPoint.OUTPUT),
                    List.of(),
                    Map.of("secret", "masked")))
            .build();
    try (var control =
            AgentControl.builder(p).mode(EnforcementMode.EVALUATE_ONLY).audit(audit::add).build();
        var session = control.openSession(CTX)) {
      var values = new CopyOnWriteArrayList<String>();
      var stream = stream(control, model);
      stream.onPartialResponse(values::add).start();
      assertEquals("secret", stream.completion().get(5, TimeUnit.SECONDS).aiMessage().text());
      assertEquals(List.of("secret"), values);
      assertTrue(
          audit.stream()
              .anyMatch(
                  r ->
                      r.point() == InterventionPoint.OUTPUT
                          && r.verdict().equals("TRANSFORM")
                          && !r.transformApplied()));
    }
  }

  @Test
  void concurrentStreamsKeepTenantContextThroughProviderCallbacks() throws Exception {
    var io = Executors.newFixedThreadPool(2);
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build()) {
      var model =
          new Model(
              (r, h) -> {
                String tenant = control.currentSession().context().tenantId();
                var last = r.messages().get(r.messages().size() - 1);
                ChatResponse response =
                    last instanceof ToolExecutionResultMessage result
                        ? text(result.text())
                        : call(tenant);
                io.execute(() -> h.onCompleteResponse(response));
              });
      var specification =
          ToolSpecifications.toolSpecificationFrom(
              Tools.class.getDeclaredMethod("lookup", String.class));
      ToolExecutor executor =
          (r, id) -> {
            String tenant = (String) JsonValues.parseObject(r.arguments()).get("tenant");
            assertEquals(tenant, control.currentSession().context().tenantId());
            return tenant;
          };
      var assistant =
          new LangChain4jGovernance(control)
              .aiServices(Assistant.class)
              .streamingChatModel(model)
              .tools(Map.of(specification, executor))
              .build();
      var futures = new ArrayList<CompletableFuture<ChatResponse>>();
      for (int i = 0; i < 12; i++) {
        String tenant = "tenant-" + i;
        futures.add(
            control.withSessionAsync(
                new TrustedContext("agent", tenant, "user-" + i, Set.of("read")),
                () -> {
                  var stream = LangChain4jGovernance.buffered(assistant.chat("hi"));
                  stream.onCompleteResponse(
                      r -> assertEquals(tenant, control.currentSession().context().tenantId()));
                  stream.start();
                  return stream.completion();
                }));
      }
      for (int i = 0; i < futures.size(); i++) {
        assertEquals("tenant-" + i, futures.get(i).get(5, TimeUnit.SECONDS).aiMessage().text());
      }
      assertEquals(24, model.calls.get());
      assertThrows(GovernanceException.class, control::currentSession);
    } finally {
      io.shutdownNow();
    }
  }

  @Test
  void cancelObtainedHandleEvenWhenProviderEntryHasNotReturned() throws Exception {
    var ready = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var release = new Semaphore(0);
    var handle =
        new StreamingHandle() {
          @Override
          public void cancel() {
            cancelled.countDown();
          }

          @Override
          public boolean isCancelled() {
            return cancelled.getCount() == 0;
          }
        };
    var model =
        new Model(
            (r, h) -> {
              h.onPartialResponse(
                  new PartialResponse("hidden"), new PartialResponseContext(handle));
              ready.countDown();
              release.acquireUninterruptibly();
            });
    try (var control = AgentControl.builder(policy().build()).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var stream = stream(control, model);
      stream.start();
      assertTrue(ready.await(3, TimeUnit.SECONDS));
      assertTrue(stream.cancel());
      assertTrue(cancelled.await(2, TimeUnit.SECONDS));
      release.release();
    } finally {
      release.release();
    }
  }
}
