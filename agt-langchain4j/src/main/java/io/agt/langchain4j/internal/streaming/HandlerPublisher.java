package io.agt.langchain4j.internal.streaming;

import dev.langchain4j.model.chat.response.*;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.langchain4j.StreamingLimits;
import java.util.ArrayDeque;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 将已治理的 handler 调用桥接为有界、有背压的模型 Publisher。 */
final class HandlerPublisher implements Flow.Publisher<ChatModelStreamingEvent> {
    private final HostSession session;
    private final StreamingLimits limits;
    private final Consumer<StreamingChatResponseHandler> start;

    HandlerPublisher(
            HostSession session,
            StreamingLimits limits,
            Consumer<StreamingChatResponseHandler> start) {
        this.session = session;
        this.limits = limits;
        this.start = start;
    }

    public void subscribe(Flow.Subscriber<? super ChatModelStreamingEvent> downstream) {
        class Subscription implements Flow.Subscription, StreamingChatResponseHandler {
            final ArrayDeque<ChatModelStreamingEvent> queue = new ArrayDeque<>();
            final AtomicInteger work = new AtomicInteger();
            long demand;
            boolean begun, cancelled, done, terminal;
            Throwable error;

            public void request(long n) {
                if (n <= 0) {
                    onError(new GovernanceException("invalid_publisher_demand"));
                    return;
                }
                boolean begin;
                synchronized (this) {
                    if (cancelled || terminal) return;
                    demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n;
                    begin = !begun;
                    begun = true;
                }
                if (begin) {
                    try (var scope = session.bind()) {
                        session.checkActive();
                        start.accept(this);
                    } catch (Throwable e) {
                        onError(e);
                    }
                }
                drain();
            }

            public void cancel() {
                synchronized (this) {
                    if (terminal || cancelled) return;
                    cancelled = true;
                    queue.clear();
                }
                session.revoke();
            }

            void add(ChatModelStreamingEvent value) {
                synchronized (this) {
                    if (cancelled || terminal || done) return;
                    if (queue.size() >= limits.maxEvents()) {
                        error = new GovernanceException("stream_capacity_exceeded");
                        done = true;
                        queue.clear();
                    } else queue.add(value);
                }
                drain();
            }

            public void onPartialResponse(String text) {
                add(new PartialResponse(text));
            }

            public void onPartialResponse(PartialResponse value, PartialResponseContext context) {
                add(value);
            }

            public void onPartialThinking(PartialThinking value) {
                add(value);
            }

            public void onPartialThinking(PartialThinking value, PartialThinkingContext context) {
                add(value);
            }

            public void onPartialToolCall(PartialToolCall value) {
                add(value);
            }

            public void onPartialToolCall(PartialToolCall value, PartialToolCallContext context) {
                add(value);
            }

            public void onUnmappedRawEvent(Object event) {
                add(RawStreamingEvent.of(event));
            }

            public void onCompleteResponse(ChatResponse value) {
                add(new CompleteResponse(value));
                synchronized (this) {
                    done = true;
                }
                drain();
            }

            public void onError(Throwable e) {
                synchronized (this) {
                    if (cancelled || terminal) return;
                    error = GovernedStreamingChatModel.safe(e);
                    done = true;
                    queue.clear();
                }
                drain();
            }

            void drain() {
                if (work.getAndIncrement() != 0) return;
                int missed = 1;
                do {
                    for (; ; ) {
                        ChatModelStreamingEvent value;
                        Throwable failure;
                        boolean finish;
                        synchronized (this) {
                            if (cancelled || terminal) break;
                            failure = error;
                            value = failure == null && demand > 0 ? queue.poll() : null;
                            if (value != null) demand--;
                            finish = done && queue.isEmpty() && value == null;
                            if (failure != null || finish) terminal = true;
                        }
                        try (var scope = session.bind()) {
                            if (failure != null) {
                                downstream.onError(failure);
                                break;
                            }
                            if (finish) {
                                downstream.onComplete();
                                break;
                            }
                            if (value == null) break;
                            session.checkDelivery();
                            downstream.onNext(value);
                        } catch (Throwable e) {
                            synchronized (this) {
                                if (terminal) break;
                            }
                            onError(e);
                        }
                    }
                    missed = work.addAndGet(-missed);
                } while (missed != 0);
            }
        }
        downstream.onSubscribe(new Subscription());
    }
}
