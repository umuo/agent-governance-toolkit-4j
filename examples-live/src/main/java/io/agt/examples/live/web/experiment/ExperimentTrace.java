package io.agt.examples.live.web.experiment;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 每条对照路径独立的过程记录；同步 HTTP 采集只在当前路径作用域内启用。 */
public final class ExperimentTrace {
    public record Step(int sequence, long elapsedMillis, int round, String kind, JsonNode body) {}

    private static final ThreadLocal<ExperimentTrace> CURRENT = new ThreadLocal<>();
    // SDK 消息类使用字段而非 JavaBean getter；只序列化显式传入的消息 DTO，不序列化模型或客户端。
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    private final List<Step> steps = new ArrayList<>();
    private final long started = System.nanoTime();
    private int round;

    public static ExperimentTrace current() {
        return CURRENT.get();
    }

    public Scope bind() {
        return new Scope(this);
    }

    public synchronized void beginRound(int value, Object request) {
        round = value;
        add("model_input", request);
    }

    public synchronized void add(String kind, Object body) {
        JsonNode snapshot;
        try {
            snapshot = JSON.valueToTree(body);
        } catch (IllegalArgumentException error) {
            snapshot = JSON.valueToTree(Map.of("captureError", "unsupported_trace_value"));
        }
        steps.add(
                new Step(
                        steps.size() + 1,
                        (System.nanoTime() - started) / 1_000_000,
                        round,
                        kind,
                        snapshot));
    }

    public void httpBody(String kind, String body) {
        try {
            add(kind, JSON.readTree(body));
        } catch (Exception error) {
            add(kind, Map.of("captureError", "invalid_json_body"));
        }
    }

    public synchronized List<Step> snapshot() {
        return List.copyOf(steps);
    }

    public static final class Scope implements AutoCloseable {
        private final ExperimentTrace previous = CURRENT.get();

        private Scope(ExperimentTrace trace) {
            CURRENT.set(trace);
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
