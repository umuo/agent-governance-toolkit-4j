package io.agt.examples;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.TokenStream;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.JsonValues;
import io.agt.core.TrustedContext;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.langchain4j.LangChain4jGovernance;
import io.agt.langchain4j.StreamingLimits;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 严格流式治理离线演示示例。
 *
 * <p>演示流式模型输出在缓冲与全量检查机制下的安全投递，应用只接收检查合规后的文本块。
 */
public final class StreamingGovernanceDemo {
    public interface Assistant {
        TokenStream chat(String message);
    }

    public static final class Tools {
        final AtomicInteger effects = new AtomicInteger();

        @Tool
        public String lookup(String tenant) {
            effects.incrementAndGet();
            return "secret-tool";
        }
    }

    private StreamingGovernanceDemo() {}

    public static void main(String[] args) throws Exception {
        var scenarios =
                List.of("allowed", "transform", "denied", "output-denied", "capacity", "cancel");
        String requested = args.length == 0 ? "all" : args[0];
        if (!requested.equals("all") && !scenarios.contains(requested)) {
            throw new IllegalArgumentException("Unknown stream scenario");
        }
        for (String scenario : requested.equals("all") ? scenarios : List.of(requested)) {
            run(scenario);
        }
    }

    private static void run(String scenario) throws Exception {
        var tools = new Tools();
        var calls = new AtomicInteger();
        var blocks = new CopyOnWriteArrayList<String>();
        var ready = new CountDownLatch(1);
        var p =
                StructuredPolicy.builder("stream-demo-v3")
                        .tool(
                                "lookup",
                                new ToolRule(
                                        Set.of("read"),
                                        ParameterRule.object(
                                                Map.of("tenant", ParameterRule.string("tenantId")),
                                                "tenant"),
                                        false));
        if (scenario.equals("transform")) {
            p.content(
                    new ContentRule(
                            "redact",
                            Set.of(InterventionPoint.POST_TOOL_CALL, InterventionPoint.OUTPUT),
                            List.of(),
                            Map.of("secret", "masked")));
        }
        if (scenario.equals("output-denied")) {
            p.content(
                    new ContentRule(
                            "final_denied",
                            Set.of(InterventionPoint.OUTPUT),
                            List.of("secret"),
                            Map.of()));
        }
        StreamingChatModel model =
                (new StreamingChatModel() {
                    @Override
                    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                        calls.incrementAndGet();
                        if (scenario.equals("cancel")) {
                            handler.onPartialResponse("hidden");
                            ready.countDown();
                            return;
                        }
                        if (scenario.equals("capacity")) {
                            handler.onPartialResponse("overflow");
                            return;
                        }
                        var last = request.messages().get(request.messages().size() - 1);
                        if (last instanceof ToolExecutionResultMessage result) {
                            handler.onPartialResponse("sec");
                            handler.onPartialResponse("ret-final");
                            handler.onCompleteResponse(
                                    ChatResponse.builder()
                                            .aiMessage(AiMessage.from(result.text()))
                                            .build());
                        } else {
                            var tool =
                                    ToolExecutionRequest.builder()
                                            .id("stream-demo")
                                            .name("lookup")
                                            .arguments(
                                                    JsonValues.write(
                                                            Map.of(
                                                                    "tenant",
                                                                    scenario.equals("denied")
                                                                            ? "other"
                                                                            : "tenant")))
                                            .build();
                            handler.onCompleteToolCall(new CompleteToolCall(0, tool));
                            handler.onCompleteResponse(
                                    ChatResponse.builder().aiMessage(AiMessage.from(tool)).build());
                        }
                    }
                });
        try (var control = AgentControl.builder(p.build()).audit(r -> {}).build();
                var session =
                        control.openSession(
                                new TrustedContext("agent", "tenant", "user", Set.of("read")))) {
            var stream =
                    LangChain4jGovernance.buffered(
                            new LangChain4jGovernance(control)
                                    .aiServices(Assistant.class)
                                    .streamingChatModel(model)
                                    .streamingLimits(
                                            scenario.equals("capacity")
                                                    ? new StreamingLimits(2, 100)
                                                    : StreamingLimits.DEFAULT)
                                    .tools(tools)
                                    .build()
                                    .chat("lookup"));
            stream.onPartialResponse(blocks::add).start();
            String result = null;
            String errorCode = null;
            if (scenario.equals("cancel")) {
                if (!ready.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Stream not started");
                }
                stream.cancel();
            }
            try {
                result = stream.completion().get(5, TimeUnit.SECONDS).aiMessage().text();
            } catch (Exception error) {
                Throwable cause = error;
                while (cause instanceof ExecutionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                errorCode =
                        cause instanceof GovernanceException g
                                ? g.code()
                                : cause instanceof CancellationException
                                        ? "operation_cancelled"
                                        : "demo_failed";
            }
            boolean allowed = scenario.equals("allowed") || scenario.equals("transform");
            if ((allowed && (errorCode != null || blocks.size() != 1 || tools.effects.get() != 1))
                    || (!allowed && (errorCode == null || !blocks.isEmpty()))) {
                throw new IllegalStateException("Stream acceptance failed");
            }
            if ((scenario.equals("denied")
                            || scenario.equals("capacity")
                            || scenario.equals("cancel"))
                    && tools.effects.get() != 0) {
                throw new IllegalStateException("Denied tool executed");
            }
            if (scenario.equals("transform") && !"masked-tool".equals(result)) {
                throw new IllegalStateException("Original content escaped");
            }
            var output = new LinkedHashMap<String, Object>();
            output.put("scenario", "stream-" + scenario);
            output.put("result", result);
            output.put("errorCode", errorCode);
            output.put("textBlocks", blocks.size());
            output.put("modelCalls", calls.get());
            output.put("toolEffects", tools.effects.get());
            System.out.println(JsonValues.write(output));
        }
    }
}
