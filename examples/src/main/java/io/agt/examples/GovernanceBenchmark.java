package io.agt.examples;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.*;
import io.agt.core.policy.StructuredPolicy;
import io.agt.langchain4j.LangChain4jGovernance;
import java.util.*;

/** 单 JVM 冒烟测量，不是 JMH、生产 SLA 或持久化审计性能。 */
public final class GovernanceBenchmark {
  private static volatile String responseSink;

  private GovernanceBenchmark() {}

  static void run() {
    ChatModel raw =
        new ChatModel() {
          public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
          }
        };
    int warmup = 1000, samples = 5000;
    long[] baseline = new long[samples], governed = new long[samples];
    var policy =
        StructuredPolicy.builder("benchmark-v1")
            .budget(new BudgetLimits(warmup + samples + 1, 0, 300_000))
            .build();
    try (var control = AgentControl.builder(policy).audit(r -> {}).build();
        var session =
            control.openSession(new TrustedContext("benchmark-agent", "demo", "demo", Set.of()))) {
      var guarded = new LangChain4jGovernance(control).guardModel(raw);
      for (int i = 0; i < warmup; i++) {
        responseSink = raw.chat("hello");
        responseSink = guarded.chat("hello");
      }
      for (int i = 0; i < samples; i++) {
        long started = System.nanoTime();
        responseSink = raw.chat("hello");
        baseline[i] = System.nanoTime() - started;
        started = System.nanoTime();
        responseSink = guarded.chat("hello");
        governed[i] = System.nanoTime() - started;
      }
    }
    Arrays.sort(baseline);
    Arrays.sort(governed);
    System.out.println(
        JsonValues.write(
            Map.of(
                "java",
                System.getProperty("java.version"),
                "os",
                System.getProperty("os.name"),
                "arch",
                System.getProperty("os.arch"),
                "warmup",
                warmup,
                "samples",
                samples,
                "baselineMedianNanos",
                baseline[samples / 2],
                "governedMedianNanos",
                governed[samples / 2],
                "governedP95Nanos",
                governed[(int) (samples * .95)],
                "audit",
                "in-memory callback")));
  }
}
