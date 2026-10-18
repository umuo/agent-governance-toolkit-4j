package io.agt.identity;

import io.agt.core.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/** 固定 introspection 端点，包含响应体的总期限与容量；不跟随重定向，不自动发现 URL。 */
public final class HttpTokenStatus implements TokenStatus {
    private final URI endpoint;
    private final Supplier<String> authorization;
    private final HttpClient client;
    private final int timeoutMillis;

    public HttpTokenStatus(URI endpoint, Supplier<String> authorization, int timeoutMillis) {
        this(endpoint, authorization, timeoutMillis, false);
    }

    public static HttpTokenStatus loopbackForTesting(
            URI endpoint, Supplier<String> authorization, int timeoutMillis) {
        return new HttpTokenStatus(endpoint, authorization, timeoutMillis, true);
    }

    private HttpTokenStatus(
            URI endpoint, Supplier<String> authorization, int timeoutMillis, boolean testing) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.authorization = Objects.requireNonNull(authorization);
        this.timeoutMillis = timeoutMillis;
        boolean loopback =
                Set.of("127.0.0.1", "::1", "[::1]")
                        .contains(Objects.toString(endpoint.getHost(), ""));
        if ((!"https".equalsIgnoreCase(endpoint.getScheme())
                        && !(testing && "http".equalsIgnoreCase(endpoint.getScheme()) && loopback))
                || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getFragment() != null
                || timeoutMillis < 1
                || timeoutMillis > 5000)
            throw new IllegalArgumentException("A fixed protected identity endpoint is required");
        client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(timeoutMillis))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public boolean active(VerifiedToken token) {
        CompletableFuture<HttpResponse<byte[]>> call = null;
        try {
            String credential = authorization.get();
            if (credential == null
                    || credential.isBlank()
                    || credential.length() > 4096
                    || credential.indexOf('\r') >= 0
                    || credential.indexOf('\n') >= 0) throw new IllegalArgumentException();
            var request =
                    HttpRequest.newBuilder(endpoint)
                            .header("Authorization", credential)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Accept", "application/json")
                            .timeout(Duration.ofMillis(timeoutMillis))
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "token="
                                                    + URLEncoder.encode(
                                                            token.credential().value(),
                                                            StandardCharsets.UTF_8)
                                                    + "&token_type_hint=access_token"))
                            .build();
            call = client.sendAsync(request, response -> new LimitedBody());
            var response = call.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) return false;
            String contentType =
                    response.headers()
                            .firstValue("Content-Type")
                            .orElse("")
                            .split(";", 2)[0]
                            .strip();
            if (!"application/json".equalsIgnoreCase(contentType)) return false;
            String json =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(response.body()))
                            .toString();
            var fields = JsonValues.parseObject(json);
            return Boolean.TRUE.equals(fields.get("active")) && token.matchesIntrospection(fields);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GovernanceException("identity_status_cancelled");
        } catch (Exception unavailable) {
            throw new GovernanceException("identity_status_unavailable");
        } finally {
            if (call != null) call.cancel(true);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public synchronized void onSubscribe(Flow.Subscription next) {
            if (subscription != null) {
                next.cancel();
                return;
            }
            subscription = next;
            next.request(1);
        }

        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                subscription.cancel();
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int size = buffer.remaining();
                if (size > 65536 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Identity response limit"));
                    return;
                }
                byte[] part = new byte[size];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        public synchronized void onError(Throwable failed) {
            result.completeExceptionally(new IOException("Identity response failed"));
        }

        public synchronized void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
