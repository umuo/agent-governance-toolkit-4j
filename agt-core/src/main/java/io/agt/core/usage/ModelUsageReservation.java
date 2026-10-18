package io.agt.core.usage;

import io.agt.core.GovernanceException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Consumer;

/** 单次模型调用预占。已发出而未确认的额度不会自动退款；不能复用或跨会话转移。 */
public final class ModelUsageReservation implements AutoCloseable {
    public enum State {
        PREPARED,
        DISPATCHED,
        SETTLED,
        RELEASED,
        UNCERTAIN
    }

    private final UsageBudget budget;
    private final UsageLedger ledger;
    private final UsageReceipt receipt;
    private final String owner;
    private final String model;
    private final int inputBound;
    private final int outputBound;
    private final UsageLimits.ModelRate rate;
    private final Consumer<ModelUsageReservation> finished;
    private State state = State.PREPARED;
    private Integer actualInput, actualOutput;

    public record Checkpoint(
            String owner,
            String model,
            int inputBound,
            int outputBound,
            State state,
            Integer actualInput,
            Integer actualOutput) {
        public Checkpoint {
            io.agt.core.Identifiers.require(owner);
            io.agt.core.Identifiers.require(model);
            Objects.requireNonNull(state);
            if (inputBound < 0
                    || outputBound < 1
                    || (state == State.SETTLED) != (actualInput != null && actualOutput != null)
                    || (state != State.SETTLED && (actualInput != null || actualOutput != null))
                    || (actualInput != null
                            && (actualInput < 0
                                    || actualOutput < 0
                                    || (long) actualInput + actualOutput > Integer.MAX_VALUE)))
                throw new IllegalArgumentException("Invalid usage checkpoint");
        }

        @Override
        public String toString() {
            return "UsageCheckpoint[redacted]";
        }
    }

    public synchronized Checkpoint checkpoint() {
        if (receipt != null) throw new GovernanceException("model_usage_checkpoint_unsupported");
        return new Checkpoint(
                owner, model, inputBound, outputBound, state, actualInput, actualOutput);
    }

    public static ModelUsageReservation restored(UsageBudget budget, Checkpoint checkpoint) {
        var rate = budget.limits().models().get(checkpoint.model());
        if (rate == null
                || checkpoint.inputBound() > rate.maxInputTokens()
                || checkpoint.outputBound() > rate.maxOutputTokens())
            throw new GovernanceException("model_usage_checkpoint_denied");
        var restored =
                new ModelUsageReservation(
                        budget,
                        checkpoint.owner(),
                        checkpoint.model(),
                        checkpoint.inputBound(),
                        checkpoint.outputBound(),
                        rate,
                        ignored -> {});
        restored.state = checkpoint.state();
        restored.actualInput = checkpoint.actualInput();
        restored.actualOutput = checkpoint.actualOutput();
        return restored;
    }

    ModelUsageReservation(
            UsageBudget budget,
            String owner,
            String model,
            int input,
            int output,
            UsageLimits.ModelRate rate,
            Consumer<ModelUsageReservation> finished) {
        this.budget = budget;
        this.ledger = budget;
        this.receipt = null;
        this.owner = Objects.requireNonNull(owner);
        this.model = model;
        inputBound = input;
        outputBound = output;
        this.rate = rate;
        this.finished = Objects.requireNonNull(finished);
    }

    private ModelUsageReservation(
            UsageLedger ledger,
            String owner,
            UsageReceipt receipt,
            Consumer<ModelUsageReservation> finished) {
        this.budget = null;
        this.ledger = Objects.requireNonNull(ledger);
        this.receipt = Objects.requireNonNull(receipt);
        this.owner = Objects.requireNonNull(owner);
        this.finished = Objects.requireNonNull(finished);
        this.model = null;
        this.inputBound = 0;
        this.outputBound = 0;
        this.rate = null;
    }

    /** 仅供可信宿主的账本适配器使用，保留会话归属、一次 dispatch 和统一终止清理。 */
    public static ModelUsageReservation external(
            UsageLedger ledger,
            String owner,
            UsageReceipt receipt,
            Consumer<ModelUsageReservation> finished) {
        return new ModelUsageReservation(ledger, owner, receipt, finished);
    }

    public void dispatch(String owner, UsageBudget expectedBudget) {
        dispatch(owner, (UsageLedger) expectedBudget);
    }

    public synchronized void dispatch(String owner, UsageLedger expectedBudget) {
        if (!this.owner.equals(owner) || ledger != expectedBudget || state != State.PREPARED) {
            throw new GovernanceException("model_usage_reservation_denied");
        }
        ledger.checkActive();
        if (receipt != null) receipt.dispatch();
        state = State.DISPATCHED;
    }

    public synchronized void settle(
            String servedModel, Integer input, Integer output, Integer total) {
        if (state != State.DISPATCHED) throw new GovernanceException("model_usage_state_invalid");
        if (receipt != null) {
            try {
                receipt.settle(servedModel, input, output, total);
                state = State.SETTLED;
            } catch (RuntimeException unknown) {
                state = State.UNCERTAIN;
                receipt.close();
                throw unknown;
            } finally {
                finished.accept(this);
            }
            return;
        }
        if (servedModel == null
                || (!servedModel.equals(model) && !rate.servedAliases().contains(servedModel))) {
            unknown("model_usage_identity_mismatch");
        }
        if (input == null || output == null || total == null) unknown("model_usage_unknown");
        if (input < 0 || output < 0 || total < 0 || (long) input + output != total)
            unknown("model_usage_invalid");
        boolean exceeded = input > inputBound || output > outputBound;
        boolean signal;
        try {
            signal =
                    budget.settle(
                            this, input, output, exceeded ? "model_usage_bound_exceeded" : null);
        } catch (ArithmeticException overflow) {
            unknown("model_usage_accounting_overflow");
            return;
        }
        state = State.SETTLED;
        actualInput = input;
        actualOutput = output;
        finished.accept(this);
        if (exceeded) {
            budget.signalFault("model_usage_bound_exceeded", signal);
            throw new GovernanceException("model_usage_bound_exceeded");
        }
    }

    private void unknown(String code) {
        state = State.UNCERTAIN;
        boolean signal = budget.uncertain(code);
        finished.accept(this);
        budget.signalFault(code, signal);
        throw new GovernanceException(code);
    }

    long reservedTokens() {
        return (long) inputBound + outputBound;
    }

    BigDecimal reservedCost() {
        return rate.cost(inputBound, outputBound);
    }

    UsageLimits.ModelRate rate() {
        return rate;
    }

    @Override
    public synchronized void close() {
        if (receipt != null && (state == State.PREPARED || state == State.DISPATCHED)) {
            state = state == State.PREPARED ? State.RELEASED : State.UNCERTAIN;
            try {
                receipt.close();
            } finally {
                finished.accept(this);
            }
            return;
        }
        if (state == State.PREPARED) {
            state = State.RELEASED;
            budget.release(this);
            finished.accept(this);
        } else if (state == State.DISPATCHED) {
            state = State.UNCERTAIN;
            boolean signal = budget.uncertain("model_usage_unconfirmed");
            finished.accept(this);
            budget.signalFault("model_usage_unconfirmed", signal);
        }
    }

    @Override
    public String toString() {
        return "ModelUsageReservation[redacted]";
    }
}
