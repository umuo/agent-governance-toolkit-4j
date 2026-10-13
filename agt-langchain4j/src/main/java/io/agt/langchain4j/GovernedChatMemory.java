package io.agt.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.data.DataOperation;
import io.agt.core.data.DataResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 对每次真实记忆读写授权，改写有效值；追加、替换和清空互不隐式授权。
 *
 * <p>持久化前使用有效消息，读取改写仅影响交付，不回写底层存储。
 */
final class GovernedChatMemory implements ChatMemory {
    private final AgentControl control;
    private final DataResource resource;
    private final ChatMemory delegate;

    GovernedChatMemory(AgentControl control, DataResource resource, ChatMemory delegate) {
        this.control = control;
        this.resource = resource;
        this.delegate = delegate;
        if (!resource.instanceId().equals(delegate.id())) {
            throw new GovernanceException("memory_binding_mismatch");
        }
    }

    private void checkBinding() {
        if (!resource.instanceId().equals(delegate.id())) {
            throw new GovernanceException("memory_binding_mismatch");
        }
    }

    private List<ChatMessage> prepareMessages(
            List<ChatMessage> original, Object effective, HostSession session) {
        checkBinding();
        return DataProjection.replaceMessages(original, effective, session);
    }

    @Override
    public Object id() {
        return resource.instanceId();
    }

    private List<ChatMessage> copy(Iterable<ChatMessage> messages) {
        var result = new ArrayList<ChatMessage>();
        for (var message : messages) {
            result.add(Objects.requireNonNull(message));
            DataProjection.limit(result.size(), control.currentSession());
        }
        return List.copyOf(result);
    }

    @Override
    public void add(ChatMessage message) {
        add(List.of(message));
    }

    @Override
    public void add(Iterable<ChatMessage> messages) {
        write(DataOperation.MEMORY_ADD, messages, delegate::add);
    }

    @Override
    public void set(Iterable<ChatMessage> messages) {
        write(DataOperation.MEMORY_SET, messages, delegate::set);
    }

    private void write(
            DataOperation operation,
            Iterable<ChatMessage> messages,
            Consumer<List<ChatMessage>> execute) {
        var session = control.currentSession();
        try {
            var originals = copy(messages);
            session.dataOperationPrepared(
                    resource,
                    operation,
                    DataProjection.messages(originals, session),
                    v -> prepareMessages(originals, v, session),
                    prepared -> {
                        execute.accept(prepared);
                        return null;
                    },
                    result -> null,
                    (result, v) -> {
                        if (v != null) {
                            TextProjection.unsupported();
                        }
                        return null;
                    });
        } catch (RuntimeException error) {
            throw DataProjection.failure(session, error);
        }
    }

    @Override
    public List<ChatMessage> messages() {
        var session = control.currentSession();
        try {
            return session.dataOperationPrepared(
                    resource,
                    DataOperation.MEMORY_READ,
                    null,
                    v -> {
                        checkBinding();
                        if (v != null) {
                            TextProjection.unsupported();
                        }
                        return null;
                    },
                    v -> delegate.messages(),
                    result -> DataProjection.messages(result, session),
                    (result, v) -> DataProjection.replaceMessages(result, v, session));
        } catch (RuntimeException error) {
            throw DataProjection.failure(session, error);
        }
    }

    @Override
    public void clear() {
        var session = control.currentSession();
        try {
            session.dataOperationPrepared(
                    resource,
                    DataOperation.MEMORY_CLEAR,
                    null,
                    v -> {
                        checkBinding();
                        if (v != null) {
                            TextProjection.unsupported();
                        }
                        return null;
                    },
                    v -> {
                        delegate.clear();
                        return null;
                    },
                    result -> null,
                    (result, v) -> {
                        if (v != null) {
                            TextProjection.unsupported();
                        }
                        return null;
                    });
        } catch (RuntimeException error) {
            throw DataProjection.failure(session, error);
        }
    }

    @Override
    public CompletableFuture<Void> addAsync(List<ChatMessage> messages) {
        return writeAsync(DataOperation.MEMORY_ADD, messages, delegate::addAsync, delegate::add);
    }

    @Override
    public CompletableFuture<Void> setAsync(List<ChatMessage> messages) {
        return writeAsync(DataOperation.MEMORY_SET, messages, delegate::setAsync, delegate::set);
    }

    private CompletableFuture<Void> writeAsync(
            DataOperation operation,
            List<ChatMessage> messages,
            Function<List<ChatMessage>, CompletableFuture<Void>> async,
            Consumer<List<ChatMessage>> blocking) {
        HostSession session = null;
        try {
            session = control.currentSession();
            var captured = session;
            var originals = copy(messages);
            return session.dataOperationAsyncPrepared(
                    resource,
                    operation,
                    DataProjection.messages(originals, session),
                    v -> prepareMessages(originals, v, captured),
                    prepared ->
                            AsyncSources.fallback(
                                    captured,
                                    async.apply(prepared),
                                    () -> {
                                        blocking.accept(prepared);
                                        return null;
                                    }),
                    result -> null,
                    (result, v) -> {
                        if (v != null) {
                            TextProjection.unsupported();
                        }
                        return null;
                    });
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    session == null ? error : DataProjection.failure(session, error));
        }
    }

    @Override
    public CompletableFuture<List<ChatMessage>> messagesAsync() {
        HostSession session = null;
        try {
            session = control.currentSession();
            var captured = session;
            return session.dataOperationAsyncPrepared(
                    resource,
                    DataOperation.MEMORY_READ,
                    null,
                    v -> {
                        checkBinding();
                        if (v != null) {
                            TextProjection.unsupported();
                        }
                        return null;
                    },
                    v ->
                            AsyncSources.fallback(
                                    captured, delegate.messagesAsync(), delegate::messages),
                    result -> DataProjection.messages(result, captured),
                    (result, v) -> DataProjection.replaceMessages(result, v, captured));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    session == null ? error : DataProjection.failure(session, error));
        }
    }
}
