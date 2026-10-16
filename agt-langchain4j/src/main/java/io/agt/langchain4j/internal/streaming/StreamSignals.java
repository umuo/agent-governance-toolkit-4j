package io.agt.langchain4j.internal.streaming;

import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 当前受控服务流的事件路由。
 *
 * <p>按真实会话隔离，完成时移除；工具事件只能由授权后的执行路径发布。
 */
public final class StreamSignals {
    private static final ConcurrentHashMap<HostSession, BiConsumer<String, Object>> SIGNALS =
            new ConcurrentHashMap<>();

    private StreamSignals() {}

    public static void attach(HostSession session, BiConsumer<String, Object> signal) {
        var existing = SIGNALS.putIfAbsent(session, signal);
        if (existing != null && existing != signal) {
            throw new GovernanceException("concurrent_session_stream");
        }
    }

    public static void detach(HostSession session, BiConsumer<String, Object> signal) {
        SIGNALS.remove(session, signal);
    }

    public static void emit(HostSession session, String kind, Object value) {
        var consumer = SIGNALS.get(session);
        if (consumer != null) {
            consumer.accept(kind, value);
        }
    }
}
