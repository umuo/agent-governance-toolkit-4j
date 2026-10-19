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
    static final int PREFETCH = 32;
    private final HostSession session;
    private final StreamingLimits limits;
    private final int prefetch;
    private Object input;
    private Function<Object, Object> invoke;
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
        this(
                session,
                input,
                invoke,
                elementType,
                delivery,
                singleValue,
                StreamingLimits.DEFAULT,
                PREFETCH);
    }

    GovernedPublisher(
            HostSession session,
            Object input,
            Function<Object, Object> invoke,
            Type elementType,
            StreamDelivery delivery,
            boolean singleValue,
            StreamingLimits limits,
            int prefetch) {
        // GUARDED 文本已通过模型门；这里独立校验交付队列容量。
        var options = new AiServiceOptions(StreamDelivery.REALTIME, limits, prefetch);
        this.limits = options.streamingLimits();
        this.prefetch = options.publisherPrefetch();
        this.session = session;
        this.input = input;
        this.invoke = invoke;
        this.elementType = elementType;
        this.delivery = delivery;
        this.singleValue = singleValue;
        session.watchTermination(completion);
        completion.whenComplete(
                (value, error) -> {
                    this.input = null;
                    this.invoke = null;
                });
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

    private record Batch(
            List<Object> items, List<Object> history, List<Integer> indexes, String text) {}

    private final class Subscription implements Flow.Subscription {
        private final Flow.Subscriber<? super Object> downstream;
        private final ArrayDeque<Object> queue = new ArrayDeque<>();
        // 实时已交付事件只保留有界 JSON，不保留其原生上下文/宿主对象图。
        private final List<String> history = new ArrayList<>();
        private final List<Object> retained = new ArrayList<>();
        private final List<Integer> indexes = new ArrayList<>();
        private StringBuilder text = new StringBuilder();
        private final AtomicInteger requesting = new AtomicInteger();
        private long outstanding;
        private int events;
        private boolean sourceDone;
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
                Object runInput = input;
                var execution = invoke;
                input = null;
                invoke = null;
                running =
                        session.runAsync(
                                runInput,
                                effective -> {
                                    var result = new CompletableFuture<Batch>();
                                    result.whenComplete(
                                            (value, error) -> {
                                                if (error != null && upstream != null) {
                                                    upstream.cancel();
                                                }
                                            });
                                    PublisherTypes.flow(execution.apply(effective))
                                            .subscribe(
                                                    new Flow.Subscriber<>() {
                                                        public void onSubscribe(
                                                                Flow.Subscription source) {
                                                            synchronized (Subscription.this) {
                                                                if (upstream != null
                                                                        || completion.isDone()
                                                                        || cancelled) {
                                                                    source.cancel();
                                                                    return;
                                                                }
                                                                upstream = source;
                                                            }
                                                            requestUpstream();
                                                        }

                                                        public void onNext(Object value) {
                                                            try (var binding = session.bind()) {
                                                                synchronized (Subscription.this) {
                                                                    if (sourceDone
                                                                            || cancelled
                                                                            || terminal
                                                                            || completion.isDone())
                                                                        return;
                                                                    if (outstanding <= 0)
                                                                        throw new GovernanceException(
                                                                                "publisher_without_demand");
                                                                    outstanding--;
                                                                }
                                                                receive(value, false);
                                                                requestUpstream();
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
                                                            try {
                                                                List<String> snapshots;
                                                                List<Object> values;
                                                                List<Integer> positions;
                                                                String joined;
                                                                synchronized (Subscription.this) {
                                                                    if (cancelled
                                                                            || terminal
                                                                            || sourceDone) return;
                                                                    sourceDone = true;
                                                                    snapshots =
                                                                            List.copyOf(history);
                                                                    values = List.copyOf(retained);
                                                                    positions =
                                                                            List.copyOf(indexes);
                                                                    joined = text.toString();
                                                                    releaseTranscript();
                                                                    upstream = null;
                                                                }
                                                                result.complete(
                                                                        new Batch(
                                                                                values,
                                                                                snapshots.stream()
                                                                                        .map(
                                                                                                JsonValues
                                                                                                        ::parse)
                                                                                        .toList(),
                                                                                positions,
                                                                                joined));
                                                            } catch (Throwable error) {
                                                                result.completeExceptionally(
                                                                        safe(error));
                                                            }
                                                        }
                                                    });
                                    return result;
                                },
                                batch -> Map.of("text", batch.text(), "items", batch.history()),
                                (batch, effective) -> {
                                    var values =
                                            io.agt.langchain4j.internal.message.TextProjection
                                                    .object(effective);
                                    io.agt.langchain4j.internal.message.TextProjection.sameKeys(
                                            Map.of("text", batch.text(), "items", batch.history()),
                                            values);
                                    String checkedText =
                                            io.agt.langchain4j.internal.message.TextProjection
                                                    .requiredString(values.get("text"));
                                    var effectiveItems =
                                            io.agt.langchain4j.internal.message.TextProjection.list(
                                                    values.get("items"));
                                    if (effectiveItems.size() != batch.history().size())
                                        throw new GovernanceException("typed_value_shape_mismatch");
                                    List<Object> checked = new ArrayList<>();
                                    for (int i = 0; i < batch.items().size(); i++)
                                        checked.add(
                                                TypedValues.restore(
                                                        batch.items().get(i),
                                                        effectiveItems.get(batch.indexes().get(i)),
                                                        elementType));
                                    if (delivery == StreamDelivery.STRICT
                                            && TypedValues.raw(elementType) == String.class) {
                                        checked =
                                                checkedText.isEmpty()
                                                        ? List.of()
                                                        : List.of(checkedText);
                                    }
                                    return new Batch(checked, List.of(), List.of(), checkedText);
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

        private void requestUpstream() {
            if (requesting.getAndIncrement() != 0) return;
            int missed = 1;
            do {
                Flow.Subscription source;
                long amount;
                synchronized (this) {
                    source = upstream;
                    int capacity =
                            delivery != StreamDelivery.STRICT ? prefetch - queue.size() : prefetch;
                    amount =
                            source != null
                                            && !sourceDone
                                            && !cancelled
                                            && !terminal
                                            && failure == null
                                            && !completion.isDone()
                                    ? Math.max(0, capacity - outstanding)
                                    : 0;
                    outstanding += amount;
                }
                if (amount > 0) {
                    try (var scope = session.bind()) {
                        session.checkActive();
                        source.request(amount);
                    } catch (Throwable error) {
                        fail(error);
                    }
                }
                missed = requesting.addAndGet(-missed);
            } while (missed != 0);
        }

        private synchronized void releaseTranscript() {
            history.clear();
            retained.clear();
            indexes.clear();
            text = new StringBuilder();
        }

        private void receive(Object value, boolean approved) {
            session.checkActive();
            if (!approved
                    && (value instanceof AiServiceStreamingEvent.BeforeToolExecutionEvent
                            || value instanceof AiServiceStreamingEvent.AfterToolExecutionEvent))
                return;
            String snapshot = JsonValues.write(TypedValues.project(value));
            boolean deliver =
                    delivery != StreamDelivery.STRICT
                            && !(value instanceof AiServiceStreamingEvent.FinalResponseEvent);
            // 保存交付前快照，防止接收方修改原始预览后改变最终检查依据。
            synchronized (this) {
                if (cancelled || terminal || completion.isDone()) return;
                if (++events > limits.maxEvents()
                        || (characters += snapshot.length()) > limits.maxCharacters())
                    throw new GovernanceException("stream_capacity_exceeded");
                if (value instanceof String part) text.append(part);
                else {
                    int position = history.size();
                    history.add(snapshot);
                    if (delivery == StreamDelivery.STRICT
                            || singleValue
                            || value instanceof AiServiceStreamingEvent.FinalResponseEvent) {
                        retained.add(value);
                        indexes.add(position);
                    }
                }
                if (value instanceof AiServiceStreamingEvent.PartialResponseEvent part)
                    text.append(part.partialResponse().text());
            }
            if (deliver) {
                Object outgoing = value;
                if (value instanceof AiServiceStreamingEvent
                        && !(value instanceof AiServiceStreamingEvent.PartialResponseEvent)
                        && !(value instanceof AiServiceStreamingEvent.PartialThinkingEvent)
                        && !(value instanceof AiServiceStreamingEvent.PartialToolCallEvent)
                        && !(value instanceof AiServiceStreamingEvent.RawEvent))
                    outgoing = CallbackViews.checked(session, value, elementType);
                synchronized (this) {
                    if (cancelled || terminal || completion.isDone()) return;
                    if (queue.size() >= prefetch)
                        throw new GovernanceException("stream_capacity_exceeded");
                    queue.add(outgoing);
                }
                drain();
            }
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
                releaseTranscript();
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
                releaseTranscript();
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
                        requestUpstream();
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
