package io.agt.langchain4j;

import dev.langchain4j.service.AiServiceStreamingEvent;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.JsonValues;
import io.agt.langchain4j.internal.concurrent.AsyncSources;
import io.agt.langchain4j.internal.service.CallbackViews;
import io.agt.langchain4j.internal.service.PublisherTypes;
import io.agt.langchain4j.internal.service.TypedValues;
import io.agt.langchain4j.internal.streaming.StreamSignals;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 单次运行的有界 Reactive 交付状态机。
 *
 * <p>首次有效 demand 才调用原生服务；保持背压、取消、最终检查和自有会话关闭关联。
 */
final class GovernedPublisher implements Flow.Publisher<Object> {
    private final HostSession session;
    private final Object input;
    private final Function<Object, Object> invoke;
    private final Type elementType;
    private final StreamDelivery delivery;
    private final boolean singleValue;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private CompletableFuture<Void> managedCompletion;

    GovernedPublisher(
            HostSession session,
            Object input,
            Function<Object, Object> invoke,
            Type elementType,
            StreamDelivery delivery,
            boolean singleValue) {
        this.session = session;
        this.input = input;
        this.invoke = invoke;
        this.elementType = elementType;
        this.delivery = delivery;
        this.singleValue = singleValue;
        session.watchTermination(completion);
    }

    void manageSession() {
        managedCompletion = ManagedSession.closeAfter(session, completion);
    }

    public void subscribe(Flow.Subscriber<? super Object> downstream) {
        Objects.requireNonNull(downstream);
        if (!subscribed.compareAndSet(false, true)) {
            downstream.onSubscribe(
                    new Flow.Subscription() {
                        public void request(long n) {}

                        public void cancel() {}
                    });
            downstream.onError(new GovernanceException("publisher_already_subscribed"));
            return;
        }
        var subscription = new Subscription(downstream);
        downstream.onSubscribe(subscription);
        completion.whenComplete(
                (value, error) -> {
                    if (error != null) {
                        subscription.fail(error);
                    }
                });
    }

    private record Batch(List<Object> items, String text) {}

    private final class Subscription implements Flow.Subscription {
        private final Flow.Subscriber<? super Object> downstream;
        private final ArrayDeque<Object> queue = new ArrayDeque<>();
        private final List<Object> all = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final AtomicInteger draining = new AtomicInteger();
        private final BiConsumer<String, Object> signals = this::signal;
        private long demand;
        private long characters;
        private boolean started;
        private boolean cancelled;
        private boolean ready;
        private boolean terminal;
        private volatile boolean closedForDelivery;
        private Throwable failure;
        private volatile Flow.Subscription upstream;
        private volatile CompletableFuture<Batch> running;

        Subscription(Flow.Subscriber<? super Object> downstream) {
            this.downstream = downstream;
        }

        public void request(long n) {
            if (n <= 0) {
                fail(new GovernanceException("invalid_publisher_demand"));
                return;
            }
            boolean begin;
            synchronized (this) {
                if (cancelled || terminal) {
                    return;
                }
                demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n;
                begin = !started;
                started = true;
            }
            if (begin) {
                start();
            }
            drain();
        }

        private void start() {
            try (var scope = session.bind()) {
                session.checkActive();
                StreamSignals.attach(session, signals);
                running =
                        session.runAsync(
                                input,
                                effective -> {
                                    var result = new CompletableFuture<Batch>();
                                    result.whenComplete(
                                            (value, error) -> {
                                                if (error != null && upstream != null) {
                                                    upstream.cancel();
                                                }
                                            });
                                    PublisherTypes.flow(invoke.apply(effective))
                                            .subscribe(
                                                    new Flow.Subscriber<>() {
                                                        public void onSubscribe(
                                                                Flow.Subscription source) {
                                                            upstream = source;
                                                            if (completion.isDone() || cancelled) {
                                                                source.cancel();
                                                            } else {
                                                                source.request(
                                                                        (long)
                                                                                        StreamingLimits
                                                                                                .DEFAULT
                                                                                                .maxEvents()
                                                                                + 1);
                                                            }
                                                        }

                                                        public void onNext(Object value) {
                                                            try (var binding = session.bind()) {
                                                                receive(value, false);
                                                            } catch (Throwable error) {
                                                                result.completeExceptionally(
                                                                        safe(error));
                                                            }
                                                        }

                                                        public void onError(Throwable error) {
                                                            result.completeExceptionally(
                                                                    safe(error));
                                                        }

                                                        public void onComplete() {
                                                            synchronized (Subscription.this) {
                                                                result.complete(
                                                                        new Batch(
                                                                                List.copyOf(all),
                                                                                text.toString()));
                                                            }
                                                        }
                                                    });
                                    return result;
                                },
                                batch ->
                                        Map.of(
                                                "text",
                                                batch.text(),
                                                "items",
                                                TypedValues.project(batch.items())),
                                (batch, effective) -> {
                                    var values =
                                            io.agt.langchain4j.internal.message.TextProjection
                                                    .object(effective);
                                    io.agt.langchain4j.internal.message.TextProjection.sameKeys(
                                            Map.of("text", batch.text(), "items", batch.items()),
                                            values);
                                    String checkedText =
                                            io.agt.langchain4j.internal.message.TextProjection
                                                    .requiredString(values.get("text"));
                                    @SuppressWarnings("unchecked")
                                    List<Object> checked =
                                            (List<Object>)
                                                    TypedValues.restore(
                                                            batch.items(),
                                                            values.get("items"),
                                                            List.class);
                                    if (delivery == StreamDelivery.STRICT
                                            && TypedValues.raw(elementType) == String.class) {
                                        checked =
                                                checkedText.isEmpty()
                                                        ? List.of()
                                                        : List.of(checkedText);
                                    }
                                    return new Batch(checked, checkedText);
                                });
                if (completion.isDone()) {
                    running.cancel(true);
                }
                running.whenComplete(
                        (batch, error) -> {
                            if (error != null) {
                                fail(error);
                                return;
                            }
                            if (singleValue && managedCompletion != null) {
                                try {
                                    StreamSignals.detach(session, signals);
                                    completion.complete(null);
                                    managedCompletion.join();
                                    closedForDelivery = true;
                                } catch (Throwable failure) {
                                    fail(failure);
                                    return;
                                }
                            }
                            synchronized (Subscription.this) {
                                if (delivery == StreamDelivery.STRICT) {
                                    queue.addAll(batch.items());
                                } else {
                                    batch.items().stream()
                                            .filter(
                                                    v ->
                                                            v
                                                                    instanceof
                                                                    AiServiceStreamingEvent
                                                                            .FinalResponseEvent)
                                            .forEach(queue::add);
                                }
                                ready = true;
                            }
                            drain();
                        });
            } catch (Throwable error) {
                fail(error);
            }
        }

        private void receive(Object value, boolean approved) {
            session.checkActive();
            if (!approved
                    && (value instanceof AiServiceStreamingEvent.BeforeToolExecutionEvent
                            || value instanceof AiServiceStreamingEvent.AfterToolExecutionEvent)) {
                return;
            }
            add(value);
            if (value instanceof String part) {
                text.append(part);
            }
            if (value instanceof AiServiceStreamingEvent.PartialResponseEvent part) {
                text.append(part.partialResponse().text());
            }
            if (delivery == StreamDelivery.REALTIME
                    && !(value instanceof AiServiceStreamingEvent.FinalResponseEvent)) {
                Object outgoing = value;
                if (value instanceof AiServiceStreamingEvent
                        && !(value instanceof AiServiceStreamingEvent.PartialResponseEvent)
                        && !(value instanceof AiServiceStreamingEvent.PartialThinkingEvent)
                        && !(value instanceof AiServiceStreamingEvent.PartialToolCallEvent)
                        && !(value instanceof AiServiceStreamingEvent.RawEvent)) {
                    outgoing = CallbackViews.checked(session, value, elementType);
                }
                synchronized (this) {
                    queue.add(outgoing);
                }
                drain();
            }
        }

        private synchronized void add(Object value) {
            if (cancelled || terminal || completion.isDone()) {
                return;
            }
            characters += JsonValues.write(TypedValues.project(value)).length();
            if (all.size() >= StreamingLimits.DEFAULT.maxEvents()
                    || characters > StreamingLimits.DEFAULT.maxCharacters()) {
                throw new GovernanceException("stream_capacity_exceeded");
            }
            all.add(value);
        }

        private void signal(String kind, Object value) {
            if (TypedValues.raw(elementType) == String.class) {
                return;
            }
            Object event;
            if (kind.equals("before")) {
                var before = (dev.langchain4j.service.tool.BeforeToolExecution) value;
                event =
                        new AiServiceStreamingEvent.BeforeToolExecutionEvent(
                                before, before.invocationContext());
            } else if (kind.equals("after")) {
                var after = (dev.langchain4j.service.tool.ToolExecution) value;
                event =
                        new AiServiceStreamingEvent.AfterToolExecutionEvent(
                                after, after.invocationContext());
            } else {
                return;
            }
            try {
                receive(event, true);
            } catch (Throwable error) {
                fail(error);
            }
        }

        public void cancel() {
            synchronized (this) {
                if (cancelled || terminal) {
                    return;
                }
                cancelled = true;
                queue.clear();
            }
            if (upstream != null) {
                upstream.cancel();
            }
            if (running != null) {
                running.cancel(true);
            }
            StreamSignals.detach(session, signals);
            completion.cancel(true);
            session.refuse("operation_cancelled");
        }

        private void fail(Throwable error) {
            synchronized (this) {
                if (terminal || cancelled || failure != null) {
                    return;
                }
                failure = safe(error);
                queue.clear();
            }
            session.refuse(safe(error).code());
            if (upstream != null) {
                upstream.cancel();
            }
            if (running != null && !running.isDone()) {
                running.cancel(true);
            }
            StreamSignals.detach(session, signals);
            completion.completeExceptionally(safe(error));
            drain();
        }

        private void drain() {
            if (draining.getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            do {
                for (; ; ) {
                    Object next;
                    Throwable problem;
                    boolean finished;
                    synchronized (this) {
                        if (cancelled || terminal) {
                            break;
                        }
                        problem = failure;
                        next = problem == null && demand > 0 ? queue.poll() : null;
                        if (next != null) {
                            demand--;
                        }
                        finished = problem == null && ready && queue.isEmpty() && next == null;
                        if (problem != null || finished) {
                            terminal = true;
                        }
                    }
                    try {
                        if (problem != null) {
                            downstream.onError(problem);
                            break;
                        }
                        if (finished) {
                            StreamSignals.detach(session, signals);
                            completion.complete(null);
                            if (managedCompletion != null) {
                                managedCompletion.join();
                            }
                            downstream.onComplete();
                            break;
                        }
                        if (next == null) {
                            break;
                        }
                        if (closedForDelivery) downstream.onNext(next);
                        else
                            try (var scope = session.bind()) {
                                session.checkActive();
                                downstream.onNext(next);
                            }
                    } catch (Throwable error) {
                        if (finished) {
                            downstream.onError(safe(error));
                        } else {
                            fail(error);
                        }
                        break;
                    }
                }
                missed = draining.addAndGet(-missed);
            } while (missed != 0);
        }
    }

    private static GovernanceException safe(Throwable error) {
        Throwable cause = AsyncSources.unwrap(error);
        return new GovernanceException(
                cause instanceof GovernanceException g ? g.code() : "publisher_failed");
    }
}
