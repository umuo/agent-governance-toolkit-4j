package io.agt.examples;

import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.agt.core.*;
import io.agt.core.data.*;
import io.agt.core.policy.*;
import io.agt.langchain4j.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 数据访问演示验证真实 delegate 调用次数与持久化有效值。 */
public final class DataGovernanceDemo {
  public interface Assistant {
    String chat(String message);
  }

  private static final class Store implements ChatMemory {
    final List<ChatMessage> saved = new ArrayList<>();
    int reads, writes, clears;

    public Object id() {
      return "memory-a";
    }

    public void add(ChatMessage message) {
      writes++;
      saved.add(message);
    }

    public List<ChatMessage> messages() {
      reads++;
      return List.copyOf(saved);
    }

    public void clear() {
      clears++;
      saved.clear();
    }
  }

  private DataGovernanceDemo() {}

  public static void main(String[] args) {
    var scenarios =
        List.of(
            "rag-allowed",
            "rag-denied",
            "rag-transform",
            "memory-transform",
            "memory-denied",
            "memory-readonly",
            "data-budget");
    String requested = args.length == 0 ? "all" : args[0];
    if (!requested.equals("all") && !scenarios.contains(requested)) {
      throw new IllegalArgumentException("Unknown data scenario");
    }
    for (String scenario : requested.equals("all") ? scenarios : List.of(requested)) {
      run(scenario, args.length > 1 ? Path.of(args[1]) : null);
    }
  }

  private static void run(String scenario, Path policyPath) {
    var resource =
        new DataResource(
            "knowledge",
            DataResource.Kind.RETRIEVAL,
            scenario.equals("rag-denied") ? "tenant-b" : "tenant-a",
            null,
            "partition-a");
    var memoryResource =
        new DataResource(
            "conversation",
            DataResource.Kind.MEMORY,
            "tenant-a",
            scenario.equals("memory-denied") ? "bob" : "alice",
            "memory-a");
    var p =
        StructuredPolicy.builder("data-demo-v4")
            .resource(
                "knowledge",
                new DataRule(
                    DataResource.Kind.RETRIEVAL,
                    DataRule.Scope.TENANT,
                    Map.of(DataOperation.RETRIEVE, Set.of("data:read"))))
            .resource(
                "conversation",
                new DataRule(
                    DataResource.Kind.MEMORY,
                    DataRule.Scope.PRINCIPAL,
                    Map.of(
                        DataOperation.MEMORY_READ,
                        Set.of("memory:read"),
                        DataOperation.MEMORY_ADD,
                        Set.of("memory:write"))));
    if (scenario.equals("rag-transform") || scenario.equals("memory-transform")) {
      p.content(
          new ContentRule(
              "data-redact",
              Set.of(InterventionPoint.PRE_DATA_ACCESS, InterventionPoint.POST_DATA_ACCESS),
              List.of(),
              Map.of("secret", "masked")));
    }
    var sourceCalls = new AtomicInteger();
    var queries = new ArrayList<String>();
    ContentRetriever source =
        query -> {
          sourceCalls.incrementAndGet();
          queries.add(query.text());
          return List.of(Content.from("secret-document"));
        };
    var store = new Store();
    var identity =
        new TrustedContext(
            "agent",
            "tenant-a",
            "alice",
            scenario.equals("memory-readonly")
                ? Set.of("memory:read")
                : Set.of("data:read", "memory:read", "memory:write"));
    var builder =
        AgentControl.builder(policyPath == null ? p.build() : StructuredPolicy.fromPath(policyPath))
            .audit(r -> {});
    if (scenario.equals("data-budget")) {
      builder.dataLimits(new DataLimits(1, 10));
    }
    try (var control = builder.build();
        var session = control.openSession(identity)) {
      var adapter = new LangChain4jGovernance(control);
      String result = null, errorCode = null;
      try {
        if (scenario.startsWith("memory")) {
          var memory = adapter.guardMemory(memoryResource, store);
          memory.add(UserMessage.from("secret-memory"));
          result = ((UserMessage) memory.messages().get(0)).singleText();
        } else if (scenario.equals("data-budget")) {
          var retriever = adapter.guardRetriever(resource, source);
          retriever.retrieve(Query.from("query"));
          retriever.retrieve(Query.from("again"));
        } else {
          ChatModel model =
              new ChatModel() {
                public ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
                  String prompt = ((UserMessage) request.messages().get(0)).singleText();
                  String marker = "Answer using the following information:";
                  String document =
                      prompt.substring(prompt.indexOf(marker) + marker.length()).strip();
                  return ChatResponse.builder().aiMessage(AiMessage.from(document)).build();
                }
              };
          result =
              adapter
                  .aiServices(Assistant.class)
                  .chatModel(model)
                  .contentRetriever(resource, source)
                  .build()
                  .chat("secret-query");
        }
      } catch (GovernanceException error) {
        errorCode = error.code();
      }
      boolean denied =
          scenario.equals("rag-denied")
              || scenario.equals("memory-denied")
              || scenario.equals("memory-readonly")
              || scenario.equals("data-budget");
      if (denied && errorCode == null) {
        throw new IllegalStateException("Expected denied data access");
      }
      if (!denied && errorCode != null) {
        throw new IllegalStateException("Expected allowed data access");
      }
      if (scenario.equals("rag-denied") && sourceCalls.get() != 0) {
        throw new IllegalStateException("Unauthorized retrieval executed");
      }
      if ((scenario.equals("memory-denied") || scenario.equals("memory-readonly"))
          && store.reads + store.writes != 0) {
        throw new IllegalStateException("Unauthorized storage executed");
      }
      if (scenario.equals("rag-transform")
          && (result.contains("secret") || !queries.equals(List.of("masked-query")))) {
        throw new IllegalStateException("Original query escaped");
      }
      if (scenario.equals("memory-transform")
          && !"masked-memory".equals(((UserMessage) store.saved.get(0)).singleText())) {
        throw new IllegalStateException("Original value persisted");
      }
      var row = new LinkedHashMap<String, Object>();
      row.put("scenario", scenario);
      row.put("errorCode", errorCode);
      row.put("result", result);
      row.put("sourceCalls", sourceCalls.get());
      row.put("memoryReads", store.reads);
      row.put("memoryWrites", store.writes);
      row.put("dataCalls", session.budgetSnapshot().dataCalls());
      System.out.println(JsonValues.write(row));
    }
  }
}
