package io.agt.langchain4j;

import static io.agt.langchain4j.ModelTerminationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.TokenUsage;
import io.agt.core.*;
import io.agt.core.execution.*;
import io.agt.core.usage.UsageLimits;
import java.io.*;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** 真实 HTTP 传输与可信停止端点；模拟生成任务，不使用真实 Provider 凭据或账单。 */
class ModelTerminationHttpTest {
    @Test
    void closingNativeFutureDoesNotStopServerJobOrManufactureConfirmation() throws Exception {
        try (var provider = new Provider();
                var control = control();
                var root = control.openTaskSession(ID, limits());
                var child = control.openDelegatedSession(root, ID);
                var binding = child.bind()) {
            var future =
                    new LangChain4jGovernance(control)
                            .guardModel(provider.model())
                            .chatAsync(request());
            assertTrue(provider.begun.await(3, TimeUnit.SECONDS));
            child.revoke();
            assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            int before = provider.ticks.get();
            await(() -> provider.ticks.get() > before);
            assertTrue(
                    provider.running.get(),
                    "Server-side generation must remain alive after transport cancellation");
            await(() -> root.externalExecutionSnapshot().unknown() == 1);
            assertEquals(1, root.externalExecutionSnapshot().active());
            assertFalse(root.externalExecutionSnapshot().allTerminationConfirmed());
            assertTrue(provider.running.get());
            assertEquals(0, provider.stopRequests.get());
        }
    }

    @Test
    void authenticatedStopAcknowledgementConfirmsActualServerExit() throws Exception {
        try (var provider = new Provider();
                var control = control();
                var root = control.openTaskSession(ID, limits());
                var child = control.openDelegatedSession(root, ID);
                var binding = child.bind()) {
            provider.allowStop = new CountDownLatch(1);
            var future =
                    governance(control, provider.contract())
                            .guardModel(provider.model())
                            .chatAsync(request());
            assertTrue(provider.begun.await(3, TimeUnit.SECONDS));
            child.revoke();
            assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            await(() -> provider.stopRequests.get() == 1);
            assertTrue(provider.running.get());
            assertFalse(root.externalExecutionSnapshot().allTerminationConfirmed());
            provider.allowStop.countDown();
            await(() -> root.externalExecutionSnapshot().allTerminationConfirmed());
            assertFalse(provider.running.get());
            assertEquals(0, root.externalExecutionSnapshot().active());
            root.checkActive();
        }
    }

    @Test
    void confirmedProviderStopDoesNotRefundDispatchedUnknownUsage() throws Exception {
        var usage =
                new UsageLimits(
                        200,
                        BigDecimal.ONE,
                        "USD",
                        Map.of(
                                "fixture",
                                new UsageLimits.ModelRate(
                                        100, 50, BigDecimal.ONE, new BigDecimal("2"), Set.of())));
        var base = limits();
        var metered =
                new TaskLimits(
                        base.calls(),
                        base.maxDataCalls(),
                        base.maxConcurrentSessions(),
                        base.maxDelegationDepth(),
                        null,
                        usage);
        var caller = Executors.newSingleThreadExecutor();
        try (var provider = new Provider();
                var control = control();
                var root = control.openTaskSession(ID, metered);
                var child = control.openDelegatedSession(root, ID)) {
            var options =
                    new AiServiceOptions(
                            StreamDelivery.REALTIME,
                            StreamingLimits.DEFAULT,
                            32,
                            ModelUsageMeter.standardParameters(req -> 100),
                            provider.contract());
            var model = new LangChain4jGovernance(control, options).guardModel(provider.model());
            var future =
                    caller.submit(
                            () -> {
                                try (var binding = child.bind()) {
                                    return model.chat(request());
                                }
                            });
            assertTrue(provider.begun.await(3, TimeUnit.SECONDS));
            child.revoke();
            assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            await(() -> root.externalExecutionSnapshot().allTerminationConfirmed());
            assertFalse(provider.running.get());
            assertEquals(150, root.usageSnapshot().heldTokens());
            assertEquals(1, root.usageSnapshot().uncertainCalls());
            assertEquals("model_usage_unconfirmed", root.usageSnapshot().fault());
            assertThrows(GovernanceException.class, root::checkActive);
        } finally {
            caller.shutdownNow();
        }
    }

    static final class Provider implements AutoCloseable {
        final String credential = UUID.randomUUID().toString();
        final HttpClient client = HttpClient.newHttpClient();
        final ExecutorService workers = Executors.newCachedThreadPool();
        final HttpServer server;
        final AtomicReference<String> id = new AtomicReference<>();
        final AtomicBoolean running = new AtomicBoolean(), stop = new AtomicBoolean();
        final AtomicInteger ticks = new AtomicInteger(), stopRequests = new AtomicInteger();
        final CountDownLatch begun = new CountDownLatch(1), exited = new CountDownLatch(1);
        volatile CountDownLatch allowStop = new CountDownLatch(0);
        boolean entered;

        Provider() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext(
                    "/generate",
                    exchange -> {
                        try (exchange) {
                            if (!authorized(exchange)) {
                                exchange.sendResponseHeaders(403, -1);
                                return;
                            }
                            var body =
                                    JsonValues.parseObject(
                                            new String(
                                                    exchange.getRequestBody().readNBytes(65537),
                                                    StandardCharsets.UTF_8));
                            if (!Objects.equals(id.get(), body.get("id"))) {
                                exchange.sendResponseHeaders(400, -1);
                                return;
                            }
                            synchronized (this) {
                                if (stop.get()) {
                                    reply(
                                            exchange,
                                            Map.of("id", id.get(), "answer", "not-started"));
                                    return;
                                }
                                entered = true;
                                running.set(true);
                            }
                            begun.countDown();
                            try {
                                while (!stop.get()) {
                                    ticks.incrementAndGet();
                                    try {
                                        Thread.sleep(5);
                                    } catch (InterruptedException ignored) {
                                    }
                                }
                            } finally {
                                running.set(false);
                                exited.countDown();
                            }
                            try {
                                reply(exchange, Map.of("id", id.get(), "answer", "synthetic"));
                            } catch (IOException disconnected) {
                                /* Caller may already have cancelled transport. */
                            }
                        }
                    });
            server.createContext(
                    "/stop",
                    exchange -> {
                        try (exchange) {
                            if (!authorized(exchange)) {
                                exchange.sendResponseHeaders(403, -1);
                                return;
                            }
                            var body =
                                    JsonValues.parseObject(
                                            new String(
                                                    exchange.getRequestBody().readNBytes(65537),
                                                    StandardCharsets.UTF_8));
                            if (!Objects.equals(id.get(), body.get("id"))) {
                                exchange.sendResponseHeaders(400, -1);
                                return;
                            }
                            stopRequests.incrementAndGet();
                            try {
                                if (!allowStop.await(5, TimeUnit.SECONDS)) {
                                    exchange.sendResponseHeaders(503, -1);
                                    return;
                                }
                                // Tombstone prevents a late create for this ID from starting after
                                // stop acknowledgement.
                                synchronized (this) {
                                    stop.set(true);
                                    if (!entered) exited.countDown();
                                }
                                if (!exited.await(3, TimeUnit.SECONDS)) {
                                    exchange.sendResponseHeaders(503, -1);
                                    return;
                                }
                                reply(exchange, Map.of("id", id.get(), "confirmed", true));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                exchange.sendResponseHeaders(503, -1);
                            }
                        }
                    });
            server.start();
        }

        boolean authorized(com.sun.net.httpserver.HttpExchange exchange) {
            return "POST".equals(exchange.getRequestMethod())
                    && ("Bearer " + credential)
                            .equals(exchange.getRequestHeaders().getFirst("Authorization"));
        }

        static void reply(com.sun.net.httpserver.HttpExchange exchange, Map<String, Object> body)
                throws IOException {
            byte[] bytes = JsonValues.write(body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        HttpRequest request(String path) {
            return HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                    .header("Authorization", "Bearer " + credential)
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    JsonValues.write(Map.of("id", id.get()))))
                    .build();
        }

        ChatResponse decode(HttpResponse<String> result) {
            var body = JsonValues.parseObject(result.body());
            if (result.statusCode() != 200 || !Objects.equals(id.get(), body.get("id")))
                throw new GovernanceException("provider_response_identity_denied");
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from((String) body.get("answer")))
                    .metadata(
                            ChatResponseMetadata.builder()
                                    .modelName("fixture")
                                    .tokenUsage(new TokenUsage(40, 10))
                                    .build())
                    .build();
        }

        void ensureId() {
            id.compareAndSet(null, UUID.randomUUID().toString());
        }

        ChatModel model() {
            return new ChatModel() {
                public ChatResponse chat(ChatRequest input, ChatRequestOptions options) {
                    ensureId();
                    try {
                        return decode(
                                client.send(
                                        request("/generate"),
                                        HttpResponse.BodyHandlers.ofString()));
                    } catch (IOException | InterruptedException failure) {
                        throw new CompletionException(failure);
                    }
                }

                public CompletableFuture<ChatResponse> chatAsync(
                        ChatRequest input, ChatRequestOptions options) {
                    ensureId();
                    var transport =
                            client.sendAsync(
                                    request("/generate"), HttpResponse.BodyHandlers.ofString());
                    var result =
                            new CompletableFuture<ChatResponse>() {
                                public boolean cancel(boolean interrupt) {
                                    boolean changed = super.cancel(interrupt);
                                    if (changed) transport.cancel(interrupt);
                                    return changed;
                                }
                            };
                    transport.whenComplete(
                            (value, error) -> {
                                if (error != null) result.completeExceptionally(error);
                                else
                                    try {
                                        result.complete(decode(value));
                                    } catch (Throwable failed) {
                                        result.completeExceptionally(failed);
                                    }
                            });
                    return result;
                }
            };
        }

        ModelTerminationContract contract() {
            return actual -> {
                ensureId();
                return new ModelTerminationContract.Call() {
                    public CompletionStage<TerminationEvidence> requestCancellation() {
                        return client.sendAsync(
                                        request("/stop"), HttpResponse.BodyHandlers.ofString())
                                .thenApply(
                                        reply -> {
                                            var body = JsonValues.parseObject(reply.body());
                                            if (reply.statusCode() != 200
                                                    || !Objects.equals(id.get(), body.get("id"))
                                                    || !Boolean.TRUE.equals(body.get("confirmed")))
                                                throw new GovernanceException(
                                                        "provider_stop_unconfirmed");
                                            return TerminationEvidence.CONFIRMED;
                                        });
                    }
                };
            };
        }

        public void close() {
            allowStop.countDown();
            stop.set(true);
            try {
                if (entered) exited.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
