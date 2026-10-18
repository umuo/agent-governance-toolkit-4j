package io.agt.core.injection;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import io.agt.core.*;
import io.agt.core.injection.llmguard.LlmGuardDetector;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LlmGuardDetectorTest {
    static DetectionInput input() {
        return new DetectionInput(
                InterventionPoint.INPUT,
                DetectionInput.Stage.ORIGINAL,
                new DetectionInput.Source(
                        DetectionInput.Origin.USER_INPUT, DetectionInput.Trust.UNTRUSTED, null),
                "session",
                "action",
                "synthetic prompt");
    }

    static final class Service implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final String token = UUID.randomUUID().toString();
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger redirected = new AtomicInteger();
        final AtomicReference<Map<String, Object>> request = new AtomicReference<>();
        volatile String response =
                "{\"is_valid\":true,\"sanitized_prompt\":\"synthetic prompt\",\"scanners\":{\"PromptInjection\":-0.5}}";
        volatile boolean slow, redirect;

        Service() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext(
                    "/leak",
                    e -> {
                        redirected.incrementAndGet();
                        e.sendResponseHeaders(200, -1);
                        e.close();
                    });
            server.createContext(
                    "/analyze/prompt",
                    e -> {
                        try (e) {
                            assertEquals(
                                    "Bearer " + token,
                                    e.getRequestHeaders().getFirst("Authorization"));
                            request.set(
                                    JsonValues.parseObject(
                                            new String(
                                                    e.getRequestBody().readNBytes(65537),
                                                    StandardCharsets.UTF_8)));
                            if (redirect) {
                                e.getResponseHeaders().add("Location", "/leak");
                                e.sendResponseHeaders(302, -1);
                                return;
                            }
                            e.getResponseHeaders().add("Content-Type", "application/json");
                            if (slow) {
                                e.sendResponseHeaders(200, 0);
                                e.getResponseBody().write('{');
                                e.getResponseBody().flush();
                                try {
                                    release.await(3, TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                                return;
                            }
                            byte[] body = response.getBytes(StandardCharsets.UTF_8);
                            e.sendResponseHeaders(200, body.length);
                            try {
                                e.getResponseBody().write(body);
                            } catch (java.io.IOException disconnected) {
                            }
                        }
                    });
            server.start();
        }

        URI endpoint() {
            return URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/analyze/prompt");
        }

        LlmGuardDetector detector() {
            return LlmGuardDetector.loopbackForTesting(
                    endpoint(), () -> token, Duration.ofSeconds(2));
        }

        public void close() {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
        }
    }

    @Test
    void officialRelativeRiskScoreAndWireShapeAreHandledWithoutApplyingSanitizedText()
            throws Exception {
        try (var service = new Service()) {
            var result = service.detector().detect(input());
            assertEquals(DetectionResult.Status.CLEAR, result.status());
            assertEquals(0.25, result.score());
            assertEquals(Map.of("prompt", "synthetic prompt"), service.request.get());
            service.response =
                    "{\"is_valid\":false,\"sanitized_prompt\":\"synthetic prompt\",\"scanners\":{\"PromptInjection\":0.4}}";
            assertEquals(
                    DetectionResult.Status.SUSPECT, service.detector().detect(input()).status());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"is_valid\":true,\"sanitized_prompt\":\"synthetic prompt\",\"scanners\":{}}",
                "{\"is_valid\":\"true\",\"sanitized_prompt\":\"synthetic prompt\",\"scanners\":{\"PromptInjection\":-0.5}}",
                "{\"is_valid\":true,\"sanitized_prompt\":\"synthetic prompt\",\"scanners\":{\"PromptInjection\":0.5}}",
                "{\"is_valid\":true,\"sanitized_prompt\":\"synthetic prompt\",\"scanners\":{\"PromptInjection\":-2}}",
                "{\"is_valid\":true,\"sanitized_prompt\":\"synthetic prompt\",\"scanners\":{\"PromptInjection\":-0.5,\"Other\":0}}"
            })
    void incompleteMistypedOrInconsistentScannerResultIsUnknown(String response) throws Exception {
        try (var service = new Service()) {
            service.response = response;
            assertEquals(
                    DetectionResult.Status.UNKNOWN, service.detector().detect(input()).status());
        }
    }

    @Test
    void classificationOfRewrittenContentIsNotAcceptedForOriginalInput() throws Exception {
        try (var service = new Service()) {
            service.response =
                    "{\"is_valid\":true,\"sanitized_prompt\":\"rewritten\",\"scanners\":{\"PromptInjection\":-0.5}}";
            assertEquals(
                    DetectionResult.Status.UNKNOWN, service.detector().detect(input()).status());
        }
    }

    @Test
    void redirectsCannotSendContentToAnotherEndpoint() throws Exception {
        try (var service = new Service()) {
            service.redirect = true;
            assertThrows(Exception.class, () -> service.detector().detect(input()));
            assertEquals(0, service.redirected.get());
        }
    }

    @Test
    void wholeResponseDeadlineCoversStalledBodyAfterHeaders() throws Exception {
        try (var service = new Service()) {
            service.slow = true;
            var detector =
                    LlmGuardDetector.loopbackForTesting(
                            service.endpoint(), () -> service.token, Duration.ofMillis(400));
            long start = System.nanoTime();
            assertThrows(Exception.class, () -> detector.detect(input()));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
        }
    }

    @Test
    void oversizedResponseAndMalformedJsonCannotBecomeClear() throws Exception {
        try (var service = new Service()) {
            service.response = "x".repeat(70000);
            assertThrows(Exception.class, () -> service.detector().detect(input()));
            service.response = "{\"is_valid\":true,\"is_valid\":false}";
            assertThrows(Exception.class, () -> service.detector().detect(input()));
        }
    }

    @Test
    void productionEndpointRequiresTlsAndFixedPath() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new LlmGuardDetector(
                                URI.create("http://127.0.0.1/analyze/prompt"),
                                () -> "token",
                                Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LlmGuardDetector.loopbackForTesting(
                                URI.create("http://localhost/analyze/prompt"),
                                () -> "token",
                                Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new LlmGuardDetector(
                                URI.create("https://trusted.example/analyze/prompt?secret=x"),
                                () -> "token",
                                Duration.ofSeconds(1)));
    }
}
