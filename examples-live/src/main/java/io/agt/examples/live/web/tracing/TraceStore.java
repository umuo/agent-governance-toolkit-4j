package io.agt.examples.live.web.tracing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agt.core.ExecutionContext;
import io.agt.core.audit.AuditRecord;
import io.agt.examples.live.web.experiment.ExperimentTrace;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 有界本机元数据存储。同步原子替换快照，正文脱敏并限制大小，不宣称断电持久或防篡改。 */
public final class TraceStore implements SpanProcessor {
    public record Node(
            String spanId,
            String parentSpanId,
            String name,
            String kind,
            String agentId,
            String runId,
            String parentRunId,
            String agentInstanceId,
            long startNanos,
            long endNanos,
            String status,
            String reasonCode,
            List<Payload> payloads) {
        public Node {
            payloads = payloads == null ? List.of() : List.copyOf(payloads);
        }

        public Node withPayloads(List<Payload> values) {
            return new Node(
                    spanId,
                    parentSpanId,
                    name,
                    kind,
                    agentId,
                    runId,
                    parentRunId,
                    agentInstanceId,
                    startNanos,
                    endNanos,
                    status,
                    reasonCode,
                    values);
        }
    }

    public record Payload(String kind, JsonNode body, boolean truncated, int originalBytes) {}

    public record Audit(
            int sequence,
            long timestamp,
            String agentId,
            String runId,
            String spanId,
            String sessionId,
            String actionId,
            String point,
            String verdict,
            String reasonCode,
            String outcome) {}

    public record View(
            String traceId,
            long createdAt,
            String status,
            List<Node> spans,
            List<Audit> audit,
            Map<String, Integer> effects) {}

    private final ObjectMapper json = new ObjectMapper();
    private final Path directory;
    private final Map<String, View> traces = new LinkedHashMap<>();
    private volatile boolean failed;

    public TraceStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath();
        Files.createDirectories(this.directory);
        if (Files.isSymbolicLink(this.directory))
            throw new IOException("Trace directory must not be a symbolic link");
        try (var files = Files.list(this.directory)) {
            for (Path path :
                    files.filter(p -> p.getFileName().toString().matches("[0-9a-f]{32}\\.json"))
                            .sorted()
                            .limit(101)
                            .toList()) {
                if (traces.size() >= 100) throw new IOException("Trace store capacity exceeded");
                if (Files.isSymbolicLink(path) || Files.size(path) > 2_000_000)
                    throw new IOException("Invalid trace snapshot");
                var view = json.readValue(path.toFile(), View.class);
                if (!path.getFileName().toString().equals(view.traceId() + ".json"))
                    throw new IOException("Invalid trace identity");
                // 进程重启后不能把未完成任务冒充成功，也不自动重放副作用。
                if (view.status().equals("running")) {
                    view =
                            new View(
                                    view.traceId(),
                                    view.createdAt(),
                                    "interrupted",
                                    view.spans().stream()
                                            .map(
                                                    n ->
                                                            n.status().equals("running")
                                                                    ? new Node(
                                                                            n.spanId(),
                                                                            n.parentSpanId(),
                                                                            n.name(),
                                                                            n.kind(),
                                                                            n.agentId(),
                                                                            n.runId(),
                                                                            n.parentRunId(),
                                                                            n.agentInstanceId(),
                                                                            n.startNanos(),
                                                                            n.endNanos(),
                                                                            "interrupted",
                                                                            "process_restarted",
                                                                            n.payloads())
                                                                    : n)
                                            .toList(),
                                    view.audit(),
                                    view.effects());
                }
                traces.put(view.traceId(), view);
            }
        }
    }

    public synchronized List<View> list() {
        return traces.values().stream()
                .sorted(Comparator.comparingLong(View::createdAt).reversed())
                .toList();
    }

    public synchronized View get(String id) {
        if (id == null || !id.matches("[0-9a-f]{32}") || !traces.containsKey(id))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "unknown_trace");
        return traces.get(id);
    }

    public synchronized void requireCapacity() {
        if (failed) throw new IllegalStateException("trace_store_unavailable");
        if (traces.size() >= 100) throw new IllegalStateException("trace_store_full");
    }

    private void save(View view) {
        if (failed) throw new IllegalStateException("trace_store_unavailable");
        if (view.spans().size() > 200 || view.audit().size() > 1000)
            throw new IllegalStateException("trace_limit_exceeded");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, "snapshot-", ".tmp");
            json.writeValue(temporary.toFile(), view);
            if (Files.size(temporary) > 2_000_000) throw new IOException("trace_snapshot_limit");
            Files.move(
                    temporary,
                    directory.resolve(view.traceId() + ".json"),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            traces.put(view.traceId(), view);
        } catch (IOException error) {
            failed = true;
            throw new IllegalStateException("trace_persistence_failed");
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    failed = true;
                }
        }
    }

    private synchronized void span(SpanData data, boolean ended) {
        var old = traces.get(data.getTraceId());
        if (old == null) {
            requireCapacity();
            old =
                    new View(
                            data.getTraceId(),
                            System.currentTimeMillis(),
                            "running",
                            List.of(),
                            List.of(),
                            Map.of());
        }
        Map<String, String> attrs = new HashMap<>();
        data.getAttributes()
                .forEach((key, value) -> attrs.put(key.getKey(), String.valueOf(value)));
        var node =
                new Node(
                        data.getSpanId(),
                        data.getParentSpanId(),
                        data.getName(),
                        attrs.getOrDefault("agt.kind", "operation"),
                        attrs.get("agt.agent.id"),
                        attrs.get("agt.run.id"),
                        attrs.get("agt.parent.run.id"),
                        attrs.get("agt.agent.instance.id"),
                        data.getStartEpochNanos(),
                        ended ? data.getEndEpochNanos() : 0,
                        ended ? attrs.getOrDefault("agt.outcome", "completed") : "running",
                        attrs.get("agt.reason.code"),
                        old.spans().stream()
                                .filter(n -> n.spanId().equals(data.getSpanId()))
                                .findFirst()
                                .map(Node::payloads)
                                .orElse(List.of()));
        var nodes = new ArrayList<>(old.spans());
        nodes.removeIf(n -> n.spanId().equals(node.spanId()));
        nodes.add(node);
        nodes.sort(Comparator.comparingLong(Node::startNanos));
        save(
                new View(
                        old.traceId(),
                        old.createdAt(),
                        old.status(),
                        List.copyOf(nodes),
                        old.audit(),
                        old.effects()));
    }

    /** 每个正文最多 64 KiB，整条链路正文最多 512 KiB；超限有明确标记。 */
    public synchronized void capture(ExecutionContext execution, String kind, Object value) {
        var old = get(execution.traceId());
        int used =
                old.spans().stream()
                        .flatMap(n -> n.payloads().stream())
                        .mapToInt(p -> TraceBodies.bytes(p.body()))
                        .sum();
        var node =
                old.spans().stream()
                        .filter(n -> n.spanId().equals(execution.spanId()))
                        .findFirst()
                        .orElseThrow();
        var values = new ArrayList<>(node.payloads());
        if (values.size() >= 20) throw new IllegalStateException("trace_payload_limit");
        var payload =
                TraceBodies.capture(
                        kind,
                        ExperimentTrace.snapshotOf(value),
                        Math.max(0, Math.min(65536, 524288 - used)));
        values.add(payload);
        var nodes =
                old.spans().stream()
                        .map(n -> n.spanId().equals(node.spanId()) ? n.withPayloads(values) : n)
                        .toList();
        save(
                new View(
                        old.traceId(),
                        old.createdAt(),
                        old.status(),
                        nodes,
                        old.audit(),
                        old.effects()));
    }

    public synchronized void audit(AuditRecord record) {
        var context = Objects.requireNonNull(record.execution());
        var old = get(context.traceId());
        var rows = new ArrayList<>(old.audit());
        rows.add(
                new Audit(
                        rows.size() + 1,
                        record.timestamp().toEpochMilli(),
                        record.agentId(),
                        context.runId(),
                        context.spanId(),
                        record.sessionId(),
                        record.actionId(),
                        record.point().wireName(),
                        record.verdict(),
                        record.reasonCode(),
                        record.executionOutcome()));
        save(
                new View(
                        old.traceId(),
                        old.createdAt(),
                        old.status(),
                        old.spans(),
                        List.copyOf(rows),
                        old.effects()));
    }

    public synchronized void effect(String traceId, String name) {
        var old = get(traceId);
        var effects = new HashMap<>(old.effects());
        effects.merge(name, 1, Integer::sum);
        save(
                new View(
                        old.traceId(),
                        old.createdAt(),
                        old.status(),
                        old.spans(),
                        old.audit(),
                        Map.copyOf(effects)));
    }

    public synchronized void finish(String traceId, String status) {
        var old = get(traceId);
        save(
                new View(
                        old.traceId(),
                        old.createdAt(),
                        status,
                        old.spans(),
                        old.audit(),
                        old.effects()));
    }

    @Override
    public void onStart(Context parent, ReadWriteSpan span) {
        span(span.toSpanData(), false);
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        span(span.toSpanData(), true);
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }
}
