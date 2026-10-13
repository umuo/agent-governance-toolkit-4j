package io.agt.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import io.agt.core.AgentControl;
import io.agt.core.HostSession;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 框架 Memory 上下文桥。
 *
 * <p>只有显式绑定 DataResource 的 delegate 才实施读写 ACL，默认仅做会话活跃检查与异步上下文传播。
 */
final class ContextChatMemory implements ChatMemory {
    private final AgentControl control;
    private final ChatMemory delegate;

    ContextChatMemory(AgentControl control, ChatMemory delegate) {
        this.control = control;
        this.delegate = delegate;
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public void add(ChatMessage message) {
        control.currentSession().checkActive();
        delegate.add(message);
    }

    @Override
    public void add(Iterable<ChatMessage> messages) {
        control.currentSession().checkActive();
        delegate.add(messages);
    }

    @Override
    public void set(Iterable<ChatMessage> messages) {
        control.currentSession().checkActive();
        delegate.set(messages);
    }

    @Override
    public List<ChatMessage> messages() {
        control.currentSession().checkActive();
        return delegate.messages();
    }

    @Override
    public void clear() {
        control.currentSession().checkActive();
        delegate.clear();
    }

    @Override
    public CompletableFuture<Void> addAsync(List<ChatMessage> messages) {
        HostSession session = control.currentSession();
        var copy = List.copyOf(messages);
        return session.supplyAsync(
                () ->
                        AsyncSources.fallback(
                                session,
                                delegate.addAsync(copy),
                                () -> {
                                    delegate.add(copy);
                                    return null;
                                }));
    }

    @Override
    public CompletableFuture<Void> setAsync(List<ChatMessage> messages) {
        HostSession session = control.currentSession();
        var copy = List.copyOf(messages);
        return session.supplyAsync(
                () ->
                        AsyncSources.fallback(
                                session,
                                delegate.setAsync(copy),
                                () -> {
                                    delegate.set(copy);
                                    return null;
                                }));
    }

    @Override
    public CompletableFuture<List<ChatMessage>> messagesAsync() {
        HostSession session = control.currentSession();
        return session.supplyAsync(
                () -> AsyncSources.fallback(session, delegate.messagesAsync(), delegate::messages));
    }
}
