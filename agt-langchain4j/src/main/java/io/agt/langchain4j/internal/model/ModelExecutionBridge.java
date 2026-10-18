package io.agt.langchain4j.internal.model;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agt.core.*;
import io.agt.core.execution.*;
import io.agt.langchain4j.ModelTerminationContract;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 交付 future 与真实 Provider 终止分离；从未调用原生入口时才确认 NOT_STARTED。 */
public final class ModelExecutionBridge {
    private ModelExecutionBridge() {}

    static Call prepare(
            HostSession session, ChatRequest request, ModelTerminationContract contract) {
        var custom = contract == null ? null : Objects.requireNonNull(contract.bind(request));
        return new Call(session, custom);
    }

    public static final class Call {
        private enum Phase {
            READY,
            ENTERED,
            ABANDONED
        }

        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.READY);
        private final AtomicBoolean responded = new AtomicBoolean();
        private final HostSession session;
        private final ModelTerminationContract.Call custom;
        private final CompletableFuture<TerminationEvidence> terminal = new CompletableFuture<>();
        private final ExternalExecution execution;

        private Call(HostSession session, ModelTerminationContract.Call custom) {
            this.session = session;
            this.custom = custom;
            execution =
                    session.registerExternalExecution(
                            "MODEL_REQUEST",
                            () -> {
                                if (phase.compareAndSet(Phase.READY, Phase.ABANDONED))
                                    terminal.complete(TerminationEvidence.NOT_STARTED);
                                if (phase.get() == Phase.ABANDONED) return terminal;
                                return custom == null
                                        ? terminal
                                        : Objects.requireNonNull(custom.requestCancellation());
                            });
        }

        void started() {
            execution.started();
        }

        public void enter() {
            if (!phase.compareAndSet(Phase.READY, Phase.ENTERED))
                throw new GovernanceException("model_execution_not_started");
        }

        public void abandon() {
            if (phase.compareAndSet(Phase.READY, Phase.ABANDONED)) {
                execution.finished(TerminationEvidence.NOT_STARTED);
                terminal.complete(TerminationEvidence.NOT_STARTED);
            }
        }

        public void requestCancellation() {
            execution.requestCancellation();
        }

        CompletableFuture<ChatResponse> observe(CompletableFuture<ChatResponse> nativeCall) {
            var delivery =
                    new CompletableFuture<ChatResponse>() {
                        public boolean cancel(boolean interrupt) {
                            if (isDone()) return false;
                            requestCancellation();
                            boolean cancelled = super.cancel(interrupt);
                            if (cancelled) nativeCall.cancel(interrupt);
                            return cancelled;
                        }
                    };
            nativeCall.whenComplete(
                    (response, error) -> {
                        try {
                            if (error != null) {
                                failure(error);
                                delivery.completeExceptionally(error);
                            } else {
                                response(response);
                                delivery.complete(response);
                            }
                        } catch (Throwable refused) {
                            delivery.completeExceptionally(refused);
                        }
                    });
            return delivery;
        }

        public void response(ChatResponse response) {
            if (!responded.compareAndSet(false, true)) return;
            if (response == null || response.aiMessage() == null) {
                finish(TerminationEvidence.UNKNOWN);
                throw new GovernanceException("model_execution_response_invalid");
            }
            try {
                finish(
                        custom == null
                                ? TerminationEvidence.CONFIRMED
                                : Objects.requireNonNull(custom.completed(response)));
            } catch (RuntimeException invalid) {
                finish(TerminationEvidence.UNKNOWN);
                throw new GovernanceException("model_termination_contract_failed");
            }
        }

        public void failure(Throwable error) {
            failure(error, "async_execution_failed");
        }

        public void failure(Throwable error, String fallbackCode) {
            while ((error instanceof CompletionException || error instanceof ExecutionException)
                    && error.getCause() != null) error = error.getCause();
            var state = session.budgetSnapshot();
            if (error instanceof CancellationException
                    || state.closed()
                    || state.revoked()
                    || state.failed()) {
                if (!state.closed() && !state.revoked() && !state.failed())
                    session.noteFailureCause("operation_cancelled");
                requestCancellation();
                return;
            }
            session.noteFailureCause(
                    error instanceof GovernanceException denied ? denied.code() : fallbackCode);
            try {
                finish(
                        custom == null
                                ? TerminationEvidence.UNKNOWN
                                : Objects.requireNonNull(custom.failed(error)));
            } catch (RuntimeException invalid) {
                finish(TerminationEvidence.UNKNOWN);
            }
        }

        private void finish(TerminationEvidence evidence) {
            execution.finished(evidence);
            terminal.complete(evidence);
        }
    }
}
