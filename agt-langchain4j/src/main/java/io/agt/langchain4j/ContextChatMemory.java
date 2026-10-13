package io.agt.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import io.agt.core.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 框架 Memory 上下文桥；读写 ACL 将由后续治理适配器实现。 */
final class ContextChatMemory implements ChatMemory {
  private final AgentControl control;
  private final ChatMemory delegate;

  ContextChatMemory(AgentControl control, ChatMemory delegate) {
    this.control = control;
    this.delegate = delegate;
  }

  public Object id() {
    return delegate.id();
  }

  public void add(ChatMessage message) {
    control.currentSession().checkActive();
    delegate.add(message);
  }

  public void add(Iterable<ChatMessage> messages) {
    control.currentSession().checkActive();
    delegate.add(messages);
  }

  public void set(Iterable<ChatMessage> messages) {
    control.currentSession().checkActive();
    delegate.set(messages);
  }

  public List<ChatMessage> messages() {
    control.currentSession().checkActive();
    return delegate.messages();
  }

  public void clear() {
    control.currentSession().checkActive();
    delegate.clear();
  }

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

  public CompletableFuture<List<ChatMessage>> messagesAsync() {
    HostSession session = control.currentSession();
    return session.supplyAsync(
        () -> AsyncSources.fallback(session, delegate.messagesAsync(), delegate::messages));
  }
}
