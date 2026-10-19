package io.agt.examples.live.web.streaming;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.TokenStream;
import io.agt.core.*;
import io.agt.core.audit.AuditRecord;
import io.agt.core.policy.*;
import io.agt.core.streaming.ChunkBufferOptions;
import io.agt.examples.live.web.AgentEvents;
import io.agt.langchain4j.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Web transport only: all checking, buffering and cancellation use the formal SDK. */
@Service
public class StreamingExamplesService {
    public interface Assistant {
        TokenStream chat(String message);
    }

    private final ObjectProvider<StreamingChatModel> live;
    private final ConcurrentHashMap<String, Run> runs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    4,
                    4,
                    0,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        var t = new Thread(r, "streaming-example");
                        t.setDaemon(true);
                        return t;
                    });

    public StreamingExamplesService(
            @Qualifier("guardrailLiveModel") ObjectProvider<StreamingChatModel> live) {
        this.live = live;
    }

    public SseEmitter start(
            String owner,
            StreamingCases.Entry entry,
            StreamDelivery mode,
            String provider,
            String message) {
        var emitter = new SseEmitter(60_000L);
        var run = new Run(owner, entry, mode, provider, message, emitter);
        runs.put(run.id, run);
        emitter.onCompletion(run::cancel);
        emitter.onTimeout(run::cancel);
        emitter.onError(error -> run.cancel());
        try {
            workers.execute(run);
        } catch (RejectedExecutionException error) {
            runs.remove(run.id);
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "streaming_examples_busy");
        }
        return emitter;
    }

    public void cancel(String owner, String id) {
        Run run = runs.get(id);
        if (run != null && run.owner.equals(owner)) run.cancel();
    }

    @PreDestroy
    public void close() {
        runs.values().forEach(Run::cancel);
        workers.shutdownNow();
    }

    private final class Run implements Runnable {
        final String id = UUID.randomUUID().toString();
        final String owner, provider, message;
        final StreamingCases.Entry entry;
        final StreamDelivery mode;
        final AgentEvents events;
        final long started = System.nanoTime();
        final AtomicBoolean cancelled = new AtomicBoolean(), triggered = new AtomicBoolean();
        final AtomicInteger received = new AtomicInteger(),
                delivered = new AtomicInteger(),
                checks = new AtomicInteger(),
                cancelAttempts = new AtomicInteger();
        final AtomicLong first = new AtomicLong();
        volatile BufferedTokenStream stream;

        Run(
                String owner,
                StreamingCases.Entry entry,
                StreamDelivery mode,
                String provider,
                String message,
                SseEmitter emitter) {
            this.owner = owner;
            this.entry = entry;
            this.mode = mode;
            this.provider = provider;
            this.message = message;
            events = new AgentEvents(emitter);
        }

        void cancel() {
            cancelled.set(true);
            var value = stream;
            if (value != null && !value.completion().isDone()) value.cancel();
        }

        Map<String, Object> metrics() {
            return Map.of(
                    "receivedCharacters",
                    received.get(),
                    "deliveredCharacters",
                    delivered.get(),
                    "localChecks",
                    checks.get(),
                    "firstChunkMs",
                    first.get() == 0 ? -1 : first.get() / 1_000_000.0,
                    "elapsedMs",
                    (System.nanoTime() - started) / 1_000_000.0,
                    "cancelAttempts",
                    cancelAttempts.get(),
                    "triggered",
                    triggered.get());
        }

        void audit(AuditRecord record) {
            if (record.executionOutcome().equals("stream_local")) checks.incrementAndGet();
            if (record.verdict().equals("DENY")
                    && record.reasonCode().equals("demo_stream_literal")) triggered.set(true);
            if (record.executionOutcome().equals("stream_local")
                    || record.verdict().equals("DENY")) {
                events.send(
                        "check",
                        Map.of(
                                "stage",
                                record.executionOutcome().equals("stream_local")
                                        ? "local"
                                        : "final",
                                "verdict",
                                record.verdict(),
                                "rule",
                                record.reasonCode(),
                                "offsets",
                                record.warnings(),
                                "metrics",
                                metrics()));
            }
        }

        @Override
        public void run() {
            try {
                events.send(
                        "started", Map.of("runId", id, "mode", mode.name(), "provider", provider));
                var policy =
                        StructuredPolicy.builder("streaming-web-v1")
                                .content(
                                        new ContentRule(
                                                "demo_stream_literal",
                                                Set.of(InterventionPoint.POST_MODEL_CALL),
                                                List.of(entry.marker()),
                                                Map.of()))
                                .build();
                try (var control =
                        AgentControl.builder(policy)
                                .budget(new BudgetLimits(1, 0, 50_000))
                                .audit(this::audit)
                                .build()) {
                    var options =
                            new AiServiceOptions(
                                    mode,
                                    new StreamingLimits(32768, 4096),
                                    32,
                                    mode == StreamDelivery.GUARDED
                                            ? ChunkBufferOptions.defaults(
                                                    Set.of("demo_stream_literal"))
                                            : null);
                    StreamingChatModel model =
                            provider.equals("fixture") ? fixture(entry) : live.getObject();
                    var assistant =
                            new LangChain4jGovernance(control, options)
                                    .create(
                                            Assistant.class,
                                            () ->
                                                    new TrustedContext(
                                                            "streaming-demo",
                                                            "demo",
                                                            "visitor",
                                                            Set.of()),
                                            builder -> builder.streamingChatModel(observe(model)));
                    stream = LangChain4jGovernance.buffered(assistant.chat(message));
                    stream.onPartialResponse(
                                    text -> {
                                        first.compareAndSet(0, System.nanoTime() - started);
                                        delivered.addAndGet(text.length());
                                        events.send(
                                                "text_delta",
                                                Map.of("text", text, "metrics", metrics()));
                                    })
                            .ignoreErrors();
                    if (cancelled.get()) {
                        stream.cancel();
                        throw new CancellationException();
                    }
                    stream.start();
                    stream.completion().get(55, TimeUnit.SECONDS);
                }
                events.send("result", Map.of("status", "done", "code", "", "metrics", metrics()));
            } catch (Throwable failure) {
                while ((failure instanceof ExecutionException
                                || failure instanceof CompletionException)
                        && failure.getCause() != null) failure = failure.getCause();
                String code =
                        cancelled.get()
                                ? "operation_cancelled"
                                : failure instanceof GovernanceException g
                                        ? g.code()
                                        : "model_unavailable";
                try {
                    events.send(
                            "result",
                            Map.of(
                                    "status",
                                    cancelled.get()
                                            ? "cancelled"
                                            : triggered.get() ? "blocked" : "error",
                                    "code",
                                    code,
                                    "metrics",
                                    metrics()));
                } catch (RuntimeException ignored) {
                    /* disconnected browser */
                }
            } finally {
                cancel();
                runs.remove(id, this);
                events.complete();
            }
        }

        StreamingChatModel observe(StreamingChatModel delegate) {
            return new StreamingChatModel() {
                @Override
                public ChatRequestParameters defaultRequestParameters() {
                    return delegate.defaultRequestParameters();
                }

                @Override
                public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    delegate.chat(
                            request,
                            new StreamingChatResponseHandler() {
                                StreamingHandle tracked(StreamingHandle source) {
                                    return new StreamingHandle() {
                                        public void cancel() {
                                            cancelAttempts.incrementAndGet();
                                            source.cancel();
                                        }

                                        public boolean isCancelled() {
                                            return source.isCancelled();
                                        }
                                    };
                                }

                                @Override
                                public void onPartialResponse(String text) {
                                    received.addAndGet(text.length());
                                    handler.onPartialResponse(text);
                                }

                                @Override
                                public void onPartialResponse(
                                        PartialResponse value, PartialResponseContext context) {
                                    received.addAndGet(value.text().length());
                                    handler.onPartialResponse(
                                            value,
                                            new PartialResponseContext(
                                                    tracked(context.streamingHandle())));
                                }

                                @Override
                                public void onPartialThinking(PartialThinking value) {
                                    handler.onPartialThinking(value);
                                }

                                @Override
                                public void onPartialThinking(
                                        PartialThinking value, PartialThinkingContext context) {
                                    handler.onPartialThinking(
                                            value,
                                            new PartialThinkingContext(
                                                    tracked(context.streamingHandle())));
                                }

                                @Override
                                public void onPartialToolCall(PartialToolCall value) {
                                    handler.onPartialToolCall(value);
                                }

                                @Override
                                public void onPartialToolCall(
                                        PartialToolCall value, PartialToolCallContext context) {
                                    handler.onPartialToolCall(
                                            value,
                                            new PartialToolCallContext(
                                                    tracked(context.streamingHandle())));
                                }

                                @Override
                                public void onCompleteToolCall(CompleteToolCall value) {
                                    handler.onCompleteToolCall(value);
                                }

                                @Override
                                public void onUnmappedRawEvent(Object value) {
                                    handler.onUnmappedRawEvent(value);
                                }

                                @Override
                                public void onCompleteResponse(ChatResponse value) {
                                    handler.onCompleteResponse(value);
                                }

                                @Override
                                public void onError(Throwable error) {
                                    handler.onError(error);
                                }
                            });
                }
            };
        }
    }

    static StreamingChatModel fixture(StreamingCases.Entry entry) {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                var stopped = new AtomicBoolean();
                var handle =
                        new StreamingHandle() {
                            public void cancel() {
                                stopped.set(true);
                            }

                            public boolean isCancelled() {
                                return stopped.get();
                            }
                        };
                for (String fragment : entry.fragments()) {
                    if (stopped.get() || Thread.currentThread().isInterrupted()) return;
                    handler.onPartialResponse(
                            new PartialResponse(fragment), new PartialResponseContext(handle));
                    if (stopped.get()) return;
                    try {
                        Thread.sleep(entry.id().equals("long-word") ? 600 : 180);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!stopped.get())
                    handler.onCompleteResponse(
                            ChatResponse.builder()
                                    .aiMessage(AiMessage.from(String.join("", entry.fragments())))
                                    .build());
            }
        };
    }
}
