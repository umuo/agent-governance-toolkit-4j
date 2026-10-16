package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.V;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.langchain4j.internal.service.TypedValues;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 普通参数、结构化返回与多模态的原生兼容性。
 *
 * <p>检查改写后的实际请求/结果以及类型恢复失败时的副作用边界。
 */
public class StructuredCompatibilityTest {
    record Order(String name, int count) {}

    interface Records {
        @dev.langchain4j.service.UserMessage("name={{name}},count={{count}}")
        Order create(@V("name") String name, @V("count") int count);
    }

    interface AsyncRecords {
        CompletableFuture<List<Order>> create(String prompt);
    }

    interface Detailed {
        Result<String> chat(String prompt);
    }

    public interface Defaults {
        String chat(String prompt);

        default String twice(String prompt) {
            return chat(prompt) + chat(prompt);
        }
    }

    interface Multi {
        String describe(List<dev.langchain4j.data.message.Content> prompt);
    }

    static ContentRule rewrite(InterventionPoint point) {
        return new ContentRule(
                "redact-" + point.wireName(), Set.of(point), List.of(), Map.of("secret", "masked"));
    }

    @Test
    void numericArgumentsAndRecordOutputsKeepTheirNativeTypes() {
        var model =
                new NativeIntegrationTest.NativeModel(
                        r -> LangChain4jGovernanceTest.text("{\"name\":\"secret\",\"count\":3}"));
        try (var control =
                AgentControl.builder(
                                StructuredPolicy.builder("types")
                                        .content(rewrite(InterventionPoint.INPUT))
                                        .content(rewrite(InterventionPoint.OUTPUT))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    Records.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.chatModel(model));
            assertEquals(new Order("masked", 3), service.create("secret", 3));
            assertTrue(model.requests.get(0).messages().toString().contains("name=masked,count=3"));
        }
    }

    @Test
    void asynchronousGenericCollectionsAreCheckedAfterNativeParsing() throws Exception {
        var model =
                new NativeIntegrationTest.NativeModel(
                        r ->
                                LangChain4jGovernanceTest.text(
                                        "[{\"name\":\"secret\",\"count\":2}]")) {
                    @Override
                    public Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
                        return Set.of(
                                dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA);
                    }
                };
        try (var control =
                AgentControl.builder(
                                StructuredPolicy.builder("types")
                                        .content(rewrite(InterventionPoint.OUTPUT))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            dev.langchain4j.service.AiServices.builder(AsyncRecords.class)
                    .chatModel(model)
                    .build()
                    .create("baseline")
                    .get(5, TimeUnit.SECONDS);
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    AsyncRecords.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.chatModel(model));
            assertEquals(
                    List.of(new Order("masked", 2)),
                    service.create("hello").get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void resultChecksContentAndFinalResponseInsteadOfLeakingRawCopies() {
        var model =
                new NativeIntegrationTest.NativeModel(
                        r -> LangChain4jGovernanceTest.text("secret"));
        try (var control =
                AgentControl.builder(
                                StructuredPolicy.builder("types")
                                        .content(rewrite(InterventionPoint.OUTPUT))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    Detailed.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.chatModel(model));
            var result = service.chat("hello");
            assertEquals("masked", result.content());
            assertEquals("masked", result.finalResponse().aiMessage().text());
        }
    }

    @Test
    void defaultMethodsReuseTheSameBudgetAndHaveAnOutputBoundary() {
        var calls = new AtomicInteger();
        var model =
                new NativeIntegrationTest.NativeModel(
                        r -> {
                            calls.incrementAndGet();
                            return LangChain4jGovernanceTest.text("secret");
                        });
        try (var control =
                AgentControl.builder(
                                StructuredPolicy.builder("types")
                                        .content(rewrite(InterventionPoint.OUTPUT))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    Defaults.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.chatModel(model));
            assertEquals("maskedmasked", service.twice("hello"));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void mediaPayloadAndTextReachTheActualModelWithPreservedTypes() {
        var model =
                new NativeIntegrationTest.NativeModel(r -> LangChain4jGovernanceTest.text("ok"));
        try (var control =
                AgentControl.builder(
                                StructuredPolicy.builder("media")
                                        .content(rewrite(InterventionPoint.INPUT))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    Multi.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.chatModel(model));
            assertEquals(
                    "ok",
                    service.describe(
                            UserMessage.from(
                                            dev.langchain4j.data.message.TextContent.from("secret"),
                                            ImageContent.from("aGVsbG8=", "image/png"))
                                    .contents()));
            var actual = (UserMessage) model.requests.get(0).messages().get(0);
            assertEquals(2, actual.contents().size());
            assertEquals(
                    "masked",
                    ((dev.langchain4j.data.message.TextContent) actual.contents().get(0)).text());
            assertEquals(
                    "aGVsbG8=", ((ImageContent) actual.contents().get(1)).image().base64Data());
        }
    }

    @Test
    void typeRestorationRejectsCoercionTruncationAndUnknownFields() {
        var original = new Order("value", 1);
        assertThrows(
                GovernanceException.class,
                () ->
                        TypedValues.restore(
                                original, Map.of("name", "value", "count", "3"), Order.class));
        assertThrows(
                GovernanceException.class,
                () ->
                        TypedValues.restore(
                                original, Map.of("name", "value", "count", 3.5), Order.class));
        assertThrows(
                GovernanceException.class,
                () ->
                        TypedValues.restore(
                                original,
                                Map.of("name", "value", "count", 3, "undeclared", true),
                                Order.class));
    }

    static class ObjectTools {
        @Tool
        Order lookup(String tenant) {
            return new Order("secret", 7);
        }
    }

    @Test
    void toolObjectIsRewrittenBeforeTheNextModelAndStillParsedNatively() {
        var model =
                new NativeIntegrationTest.NativeModel(
                        r ->
                                r.messages().get(r.messages().size() - 1)
                                                instanceof ToolExecutionResultMessage result
                                        ? LangChain4jGovernanceTest.text(result.text())
                                        : dev.langchain4j.model.chat.response.ChatResponse.builder()
                                                .aiMessage(
                                                        AiMessage.from(
                                                                LangChain4jGovernanceTest.call(
                                                                        "tenant-a")))
                                                .build());
        try (var control =
                AgentControl.builder(
                                LangChain4jGovernanceTest.policy()
                                        .content(rewrite(InterventionPoint.POST_TOOL_CALL))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    Records.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.chatModel(model).tools(new ObjectTools()));
            assertEquals(new Order("masked", 7), service.create("lookup", 7));
        }
    }

    @Test
    void audioVideoAndPdfPreservePayloadsThroughActualNativeRequests() {
        var contents =
                List.<dev.langchain4j.data.message.Content>of(
                        dev.langchain4j.data.message.TextContent.from("secret"),
                        dev.langchain4j.data.message.AudioContent.from("YQ==", "audio/wav"),
                        dev.langchain4j.data.message.VideoContent.from(
                                "https://example.invalid/video.mp4"),
                        dev.langchain4j.data.message.PdfFileContent.from(
                                "Yg==", "application/pdf"));
        var model =
                new NativeIntegrationTest.NativeModel(r -> LangChain4jGovernanceTest.text("ok"));
        try (var control =
                AgentControl.builder(
                                NativeIntegrationTest.policy()
                                        .content(rewrite(InterventionPoint.INPUT))
                                        .build())
                        .audit(r -> {})
                        .build()) {
            var service =
                    new LangChain4jGovernance(control)
                            .create(
                                    Multi.class,
                                    () -> NativeIntegrationTest.CTX,
                                    b -> b.chatModel(model));
            assertEquals("ok", service.describe(contents));
            var actual = ((UserMessage) model.requests.get(0).messages().get(0)).contents();
            assertEquals(4, actual.size());
            assertEquals(dev.langchain4j.data.message.TextContent.from("masked"), actual.get(0));
            assertEquals(contents.subList(1, 4), actual.subList(1, 4));
        }
    }

    @Test
    void malformedMediaAndChangedMimeAreRefused() {
        var image = ImageContent.from("YQ==", "image/png");
        var projected = TypedValues.project(image);
        String json = io.agt.core.JsonValues.write(projected);
        assertThrows(
                GovernanceException.class,
                () ->
                        TypedValues.restore(
                                image,
                                io.agt.core.JsonValues.parse(json.replace("YQ==", "!")),
                                ImageContent.class));
        assertThrows(
                GovernanceException.class,
                () ->
                        TypedValues.restore(
                                image,
                                io.agt.core.JsonValues.parse(
                                        json.replace("image/png", "image/jpeg")),
                                ImageContent.class));
    }
}
