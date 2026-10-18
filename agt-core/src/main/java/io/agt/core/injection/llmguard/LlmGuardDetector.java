package io.agt.core.injection.llmguard;

import io.agt.core.*;
import io.agt.core.injection.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/** LLM Guard 官方 /analyze/prompt 协议；不接受服务端改写，不发送宿主身份/租约/完整请求对象。 */
public final class LlmGuardDetector implements InjectionDetector {
    private final URI endpoint;
    private final Supplier<String> credential;
    private final long timeoutMillis;
    private final HttpClient client;

    public LlmGuardDetector(URI endpoint, Supplier<String> credential, Duration timeout) {
        this(endpoint, credential, timeout, false);
    }

    public static LlmGuardDetector loopbackForTesting(
            URI endpoint, Supplier<String> credential, Duration timeout) {
        return new LlmGuardDetector(endpoint, credential, timeout, true);
    }

    private LlmGuardDetector(
            URI endpoint, Supplier<String> credential, Duration timeout, boolean testing) {
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(timeout);
        boolean loopback =
                testing
                        && "http".equals(endpoint.getScheme())
                        && Set.of("127.0.0.1", "[::1]", "::1")
                                .contains(Objects.toString(endpoint.getHost(), ""));
        if ((!"https".equals(endpoint.getScheme()) && !loopback)
                || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || !"/analyze/prompt".equals(endpoint.getPath()))
            throw new IllegalArgumentException("Invalid trusted detector endpoint");
        timeoutMillis = timeout.toMillis();
        if (timeoutMillis < 1 || timeoutMillis > 30000)
            throw new IllegalArgumentException("Invalid detector HTTP timeout");
        this.endpoint = endpoint;
        this.credential = Objects.requireNonNull(credential);
        client =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public DetectionResult detect(DetectionInput input) throws Exception {
        var content = input.content();
        String text = content instanceof String s ? s : JsonValues.write(content);
        var request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofMillis(timeoutMillis))
                        .header(
                                "Authorization",
                                "Bearer " + Objects.requireNonNull(credential.get()))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        JsonValues.write(Map.of("prompt", text))))
                        .build();
        var call = client.sendAsync(request, info -> new BoundedBody());
        try {
            var response = call.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200)
                throw new GovernanceException("injection_service_unavailable");
            String json =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(response.body()))
                            .toString();
            var fields = JsonValues.parseObject(json);
            if (!fields.keySet().equals(Set.of("sanitized_prompt", "is_valid", "scanners"))
                    || !text.equals(fields.get("sanitized_prompt"))
                    || !(fields.get("is_valid") instanceof Boolean valid)
                    || !(fields.get("scanners") instanceof Map<?, ?> scanners)
                    || !scanners.keySet().equals(Set.of("PromptInjection"))
                    || !(scanners.get("PromptInjection") instanceof Number score))
                return DetectionResult.unknown();
            double risk = score.doubleValue();
            if (!Double.isFinite(risk) || risk < -1 || risk > 1) return DetectionResult.unknown();
            // 官方 risk 是相对阈值的 [-1,1] 分数，不是概率；矛盾结果不得成为 CLEAR。
            if ((valid && risk > 0) || (!valid && risk < 0)) return DetectionResult.unknown();
            return new DetectionResult(
                    valid ? DetectionResult.Status.CLEAR : DetectionResult.Status.SUSPECT,
                    (risk + 1) / 2);
        } finally {
            if (!call.isDone()) call.cancel(true);
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        public void onNext(List<ByteBuffer> chunks) {
            try {
                for (var chunk : chunks) {
                    if (chunk.remaining() > 65536 - bytes.size())
                        throw new GovernanceException("injection_response_limit");
                    var data = new byte[chunk.remaining()];
                    chunk.get(data);
                    bytes.write(data);
                }
                subscription.request(1);
            } catch (Exception invalid) {
                subscription.cancel();
                result.completeExceptionally(invalid);
            }
        }

        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }

    @Override
    public String toString() {
        return "LlmGuardDetector[redacted]";
    }
}
