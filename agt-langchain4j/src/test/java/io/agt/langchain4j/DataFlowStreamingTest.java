package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import io.agt.core.*;
import io.agt.core.flow.*;
import io.agt.core.policy.StructuredPolicy;
import io.agt.langchain4j.internal.streaming.StreamingAdapters;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DataFlowStreamingTest {
    @ParameterizedTest
    @EnumSource(StreamDelivery.class)
    void reactiveSubscriberNeverReceivesLowClearancePreviewOrBufferedResult(StreamDelivery delivery)
            throws Exception {
        var flow =
                new DataFlowPolicy(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        DataLabel.CONFIDENTIAL,
                        DataLabel.SECRET,
                        DataLabel.PUBLIC);
        var identity = new TrustedContext("agent", "tenant", "user", Set.of());
        try (var control =
                        AgentControl.builder(StructuredPolicy.builder("flow-publisher").build())
                                .audit(record -> {})
                                .build();
                var session =
                        control.openTaskSession(
                                identity,
                                new TaskLimits(new BudgetLimits(5, 5, 60000), 5, 3, 2, flow))) {
            var source = new PublisherResourceTest.Source(3);
            var receiver = new PublisherResourceTest.Receiver<Object>();
            new GovernedPublisher(
                            session, "request", input -> source, String.class, delivery, false)
                    .subscribe(receiver);
            receiver.subscription.request(Long.MAX_VALUE);
            var error =
                    assertThrows(
                            ExecutionException.class, () -> receiver.done.get(5, TimeUnit.SECONDS));
            assertEquals(
                    "data_flow_output_denied", ((GovernanceException) error.getCause()).code());
            assertTrue(receiver.values.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(StreamDelivery.class)
    void lowClearanceConsumerNeverReceivesAnyStreamContent(StreamDelivery delivery)
            throws Exception {
        var flow =
                new DataFlowPolicy(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        DataLabel.CONFIDENTIAL,
                        DataLabel.SECRET,
                        DataLabel.PUBLIC);
        var policy = StructuredPolicy.builder("flow-stream").build();
        var identity = new TrustedContext("agent", "tenant", "user", Set.of());
        var received = new CopyOnWriteArrayList<String>();
        var calls = new AtomicInteger();
        StreamingChatModel provider =
                new StreamingChatModel() {
                    @Override
                    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                        calls.incrementAndGet();
                        handler.onPartialResponse("confidential preview");
                        handler.onCompleteResponse(
                                ChatResponse.builder()
                                        .aiMessage(AiMessage.from("confidential final"))
                                        .build());
                    }
                };
        try (var control = AgentControl.builder(policy).audit(record -> {}).build();
                var session =
                        control.openTaskSession(
                                identity,
                                new TaskLimits(new BudgetLimits(5, 5, 60000), 5, 3, 2, flow));
                var binding = session.bind()) {
            var completion = new CompletableFuture<ChatResponse>();
            StreamingAdapters.guard(control, provider, StreamingLimits.DEFAULT, delivery)
                    .chat(
                            ChatRequest.builder().messages(UserMessage.from("request")).build(),
                            new StreamingChatResponseHandler() {
                                @Override
                                public void onPartialResponse(String text) {
                                    received.add(text);
                                }

                                @Override
                                public void onCompleteResponse(ChatResponse response) {
                                    received.add(response.aiMessage().text());
                                    completion.complete(response);
                                }

                                @Override
                                public void onError(Throwable error) {
                                    completion.completeExceptionally(error);
                                }
                            });
            var error =
                    assertThrows(
                            ExecutionException.class, () -> completion.get(5, TimeUnit.SECONDS));
            assertEquals(
                    "data_flow_output_denied", ((GovernanceException) error.getCause()).code());
            assertTrue(received.isEmpty());
            assertEquals(1, calls.get());
        }
    }
}
