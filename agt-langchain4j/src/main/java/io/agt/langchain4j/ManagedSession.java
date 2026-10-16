package io.agt.langchain4j;

import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.TrustedContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 原生接入服务的自有会话生命周期。
 *
 * <p>仅顶层调用创建会话；完成和取消传播不依赖调用线程一直存活。
 */
final class ManagedSession {
    private ManagedSession() {}

    private static final ThreadLocal<Owned> BUILDING = new ThreadLocal<>();

    private static final class Owned {
        final HostSession session;
        Object deferred;

        Owned(HostSession session) {
            this.session = session;
        }
    }

    static boolean defer(HostSession session, Object exposed) {
        var owner = BUILDING.get();
        if (owner == null || owner.session != session) {
            return false;
        }
        owner.deferred = exposed;
        return true;
    }

    static Object invoke(
            AgentControl control, Supplier<TrustedContext> identity, Supplier<Object> invoke) {
        try {
            control.currentSession();
        } catch (GovernanceException error) {
            if (!error.code().equals("missing_session")) {
                throw error;
            }
            return owned(control, identity, invoke);
        }
        // 已有会话属于宿主；既不能重置预算，也不能在本次调用结束时关闭它。
        return invoke.get();
    }

    private static Object owned(
            AgentControl control, Supplier<TrustedContext> identity, Supplier<Object> invoke) {
        TrustedContext context;
        try {
            context = identity.get();
        } catch (RuntimeException error) {
            throw new GovernanceException("identity_resolution_failed");
        }
        if (context == null) {
            throw new GovernanceException("missing_trusted_context");
        }
        HostSession session = control.openDetachedSession(context);
        Owned previous = BUILDING.get();
        var building = new Owned(session);
        BUILDING.set(building);
        try (var scope = session.bind()) {
            try {
                Object result = invoke.get();
                if (building.deferred == result && result != null) {
                    return result;
                }
                if (result instanceof CompletionStage<?> stage) {
                    return closeAfter(session, stage.toCompletableFuture());
                }
                if (result instanceof BufferedTokenStream stream) {
                    stream.manageSession(session);
                    return stream;
                }
                session.close();
                return result;
            } catch (RuntimeException | Error error) {
                session.close();
                throw error;
            }
        } finally {
            if (previous == null) {
                BUILDING.remove();
            } else {
                BUILDING.set(previous);
            }
        }
    }

    static <T> CompletableFuture<T> closeAfter(HostSession session, CompletableFuture<T> source) {
        var result =
                new CompletableFuture<T>() {
                    @Override
                    public boolean cancel(boolean interrupt) {
                        boolean changed = super.cancel(interrupt);
                        if (changed) {
                            source.cancel(interrupt);
                        }
                        return changed;
                    }
                };
        source.whenComplete(
                (value, error) -> {
                    Throwable failure = error;
                    while (failure instanceof CompletionException && failure.getCause() != null) {
                        failure = failure.getCause();
                    }
                    try (var scope = session.bind()) {
                        try {
                            session.close();
                        } catch (RuntimeException closeError) {
                            failure = closeError;
                        }
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    }
                });
        return result;
    }
}
