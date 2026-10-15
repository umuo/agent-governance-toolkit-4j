package io.agt.examples.live.web;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 序列化单次运行的 SSE 事件，保证模型回调与工具线程事件有序。 */
public final class AgentEvents {
    private final SseEmitter emitter;
    private final AtomicLong sequence = new AtomicLong();
    private boolean closed;

    public AgentEvents(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public synchronized void send(String type, Object data) {
        if (closed) {
            return;
        }
        try {
            emitter.send(
                    SseEmitter.event()
                            .id(Long.toString(sequence.incrementAndGet()))
                            .name(type)
                            .data(Map.of("type", type, "data", data)));
        } catch (IOException | IllegalStateException error) {
            closed = true;
            throw new IllegalStateException("event_delivery_failed", error);
        }
    }

    public synchronized void complete() {
        if (!closed) {
            closed = true;
            emitter.complete();
        }
    }
}
