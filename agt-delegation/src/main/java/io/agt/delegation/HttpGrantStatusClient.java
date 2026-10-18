package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.flow.DataFlowState;
import io.agt.core.usage.UsageBudget;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/** 固定宿主端点的在线客户端；HTTPS、认证头、有界正文、全请求超时，禁止跟随重定向。 */
public final class HttpGrantStatusClient implements GrantStatusClient {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private final URI endpoint;
    private final Supplier<String> credentials;
    private final HttpClient http;

    public HttpGrantStatusClient(URI endpoint, Supplier<String> credentials) {
        this(endpoint, credentials, false);
    }

    /** 仅本机实验允许明文传输，生产端点必须使用 HTTPS 与宿主身份认证。 */
    public static HttpGrantStatusClient loopbackForTesting(
            URI endpoint, Supplier<String> credentials) {
        return new HttpGrantStatusClient(endpoint, credentials, true);
    }

    private HttpGrantStatusClient(URI endpoint, Supplier<String> credentials, boolean loopback) {
        Objects.requireNonNull(endpoint);
        boolean local =
                endpoint.getHost() != null
                        && Set.of("127.0.0.1", "[::1]", "::1").contains(endpoint.getHost());
        if (endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !("https".equals(endpoint.getScheme())
                        || (loopback && local && "http".equals(endpoint.getScheme())))) {
            throw new IllegalArgumentException("Trusted HTTPS endpoint required");
        }
        this.endpoint = endpoint;
        this.credentials = Objects.requireNonNull(credentials);
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(500))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public GrantDocument claim(
            String id, String digest, String agent, String audience, GrantLease lease) {
        var response =
                request(
                        "claim",
                        lease,
                        Map.of(
                                "grantId",
                                id,
                                "digest",
                                digest,
                                "agent",
                                agent,
                                "audience",
                                audience));
        if (!response.keySet().equals(Set.of("document"))
                || !(response.get("document") instanceof String document)) throw unavailable();
        return GrantCodec.decode(document);
    }

    public void bind(GrantLease lease, String session) {
        command("bind", lease, Map.of("sessionId", session));
    }

    public void check(GrantLease lease, String session) {
        command("check", lease, Map.of("sessionId", session));
    }

    public void commit(
            GrantLease lease, String session, String action, String kind, String digest) {
        command(
                "commit",
                lease,
                Map.of("sessionId", session, "actionId", action, "kind", kind, "digest", digest));
    }

    public void complete(GrantLease lease, String session) {
        command("complete", lease, Collections.singletonMap("sessionId", session));
    }

    public void usageReserve(
            GrantLease lease, String session, String id, String model, int input, int output) {
        command(
                "usage_reserve",
                lease,
                Map.of(
                        "sessionId",
                        session,
                        "reservationId",
                        id,
                        "model",
                        model,
                        "input",
                        input,
                        "output",
                        output));
    }

    public void usageDispatch(GrantLease lease, String session, String id) {
        command("usage_dispatch", lease, Map.of("sessionId", session, "reservationId", id));
    }

    public void usageSettle(
            GrantLease lease,
            String session,
            String id,
            String model,
            Integer input,
            Integer output,
            Integer total) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("sessionId", session);
        fields.put("reservationId", id);
        fields.put("model", model);
        fields.put("input", input);
        fields.put("output", output);
        fields.put("total", total);
        command("usage_settle", lease, fields);
    }

    public void usageClose(GrantLease lease, String session, String id) {
        command("usage_close", lease, Map.of("sessionId", session, "reservationId", id));
    }

    public UsageBudget.Snapshot usageSnapshot(GrantLease lease, String session) {
        var response = request("usage_snapshot", lease, Map.of("sessionId", session));
        if (!response.keySet().equals(Set.of("snapshot"))
                || !(response.get("snapshot") instanceof String encoded)) throw unavailable();
        return GrantCodec.decodeUsageSnapshot(encoded);
    }

    private void command(String op, GrantLease lease, Map<String, Object> fields) {
        if (!request(op, lease, fields).equals(Map.of("ok", true))) throw unavailable();
    }

    private Map<String, Object> flowFields(
            String session, InterventionPoint point, String tool, Object projection) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("sessionId", session);
        fields.put("point", point.name());
        fields.put("tool", tool);
        fields.put("projection", projection);
        return fields;
    }

    public void guardedCommit(
            GrantLease lease,
            String session,
            String action,
            InterventionPoint point,
            String tool,
            Object projection,
            String digest) {
        var fields = flowFields(session, point, tool, projection);
        fields.put("actionId", action);
        fields.put("digest", digest);
        command("guarded_commit", lease, fields);
    }

    public void flowValidate(
            GrantLease lease,
            String session,
            InterventionPoint point,
            String tool,
            Object projection) {
        command("flow_validate", lease, flowFields(session, point, tool, projection));
    }

    public void flowComplete(GrantLease lease, String session, String action, String tool) {
        command(
                "flow_complete",
                lease,
                Map.of("sessionId", session, "actionId", action, "tool", tool));
    }

    public void flowValidate(
            GrantLease lease,
            String session,
            String action,
            InterventionPoint point,
            String tool,
            Object projection) {
        var fields = new LinkedHashMap<>(flowFields(session, point, tool, projection));
        fields.put("actionId", action);
        command("flow_validate_action", lease, fields);
    }

    public DataFlowState.Snapshot flowSnapshot(GrantLease lease, String session) {
        var response = request("flow_snapshot", lease, Map.of("sessionId", session));
        if (!response.keySet().equals(Set.of("snapshot"))
                || !(response.get("snapshot") instanceof String encoded)) throw unavailable();
        return GrantCodec.decodeFlowSnapshot(encoded);
    }

    public void workRegister(GrantLease lease, String session, String id, String kind) {
        command(
                "work_register",
                lease,
                Map.of("sessionId", session, "executionId", id, "kind", kind));
    }

    public void workStart(GrantLease lease, String session, String id) {
        command("work_start", lease, Map.of("sessionId", session, "executionId", id));
    }

    public void workCancel(GrantLease lease, String session, String id) {
        command("work_cancel", lease, Map.of("sessionId", session, "executionId", id));
    }

    public void workFinish(
            GrantLease lease,
            String session,
            String id,
            io.agt.core.execution.TerminationEvidence evidence) {
        command(
                "work_finish",
                lease,
                Map.of("sessionId", session, "executionId", id, "evidence", evidence.name()));
    }

    public io.agt.core.execution.ExternalExecutionState.Summary workStatus(
            GrantLease lease, String session) {
        var response = request("work_status", lease, Map.of("sessionId", session));
        if (!response.keySet().equals(Set.of("summary"))
                || !(response.get("summary") instanceof String encoded)) throw unavailable();
        return ExternalWorkCodec.summary(encoded);
    }

    public io.agt.core.execution.ExternalExecutionState.Page workPage(
            GrantLease lease, String session, long revision, int offset) {
        var response =
                request(
                        "work_page",
                        lease,
                        Map.of("sessionId", session, "revision", revision, "offset", offset));
        if (!response.keySet().equals(Set.of("page"))
                || !(response.get("page") instanceof String encoded)) throw unavailable();
        return ExternalWorkCodec.page(encoded);
    }

    private Map<String, Object> request(String op, GrantLease lease, Map<String, Object> fields) {
        try {
            String credential = credentials.get();
            if (credential == null
                    || credential.isBlank()
                    || credential.length() > 4096
                    || credential.chars().anyMatch(value -> value < 33 || value > 126))
                throw unavailable();
            var body = new LinkedHashMap<String, Object>(fields);
            body.put("op", op);
            body.put("lease", Map.of("grantId", lease.grantId(), "secret", lease.secret()));
            var request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(1))
                            .header("Authorization", "Bearer " + credential)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JsonValues.write(body)))
                            .build();
            var pending = http.sendAsync(request, info -> new BoundedBody());
            HttpResponse<byte[]> response;
            try {
                // 显式覆盖完整正文读取期限，不依赖 Provider 对请求超时的细节。
                response = pending.get(1, TimeUnit.SECONDS);
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
            var decoder =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
            var value =
                    JsonValues.parseObject(
                            decoder.decode(ByteBuffer.wrap(response.body())).toString());
            if (response.statusCode() == 200) return value;
            if (response.statusCode() >= 400
                    && response.statusCode() < 500
                    && value.keySet().equals(Set.of("code"))
                    && value.get("code") instanceof String code
                    && code.matches(
                            "(?:delegation|task|model_usage|data_flow|label_store)_[a-z_]{1,80}"))
                throw new GovernanceException(code);
            throw unavailable();
        } catch (GovernanceException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception unknown) {
            // 不把原始 URL、认证头或响应正文写入异常。
            throw unavailable();
        }
    }

    private static GovernanceException unavailable() {
        return new GovernanceException("delegation_registry_unavailable");
    }

    /** 有界完整正文；外层 future 期限同时限制慢速正文与未结束响应。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) {
                value.cancel();
                return;
            }
            subscription = value;
            value.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_BODY_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(unavailable());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        public void onError(Throwable failure) {
            result.completeExceptionally(unavailable());
        }

        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
