package io.agt.core.usage;

import io.agt.core.GovernanceException;
import io.agt.core.Identifiers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 根任务内原子预占和结算，不执行网络、模型或会话回调。故障通知只安排宿主停止任务。 */
public final class UsageBudget implements UsageLedger {
    private final UsageLimits limits;
    private final Consumer<String> faultSignal;
    private long settledTokens;
    private BigDecimal settledCost = BigDecimal.ZERO;
    private long heldTokens;
    private BigDecimal heldCost = BigDecimal.ZERO;
    private int inFlight;
    private int uncertain;
    private String fault;

    public UsageBudget(UsageLimits limits, Consumer<String> faultSignal) {
        this.limits = Objects.requireNonNull(limits);
        this.faultSignal = Objects.requireNonNull(faultSignal);
    }

    public UsageLimits limits() {
        return limits;
    }

    /** 纯状态恢复；校验聚合余额与全部单次凭据一致，不重放发送/退款/回调。 */
    public static UsageBudget restored(
            UsageLimits limits,
            Snapshot baseline,
            Snapshot snapshot,
            List<ModelUsageReservation.Checkpoint> receipts) {
        try {
            if (receipts.size() > 100000
                    || !identity(limits, baseline)
                    || !identity(limits, snapshot)
                    || baseline.inFlightCalls() != 0
                    || baseline.uncertainCalls() != 0
                    || baseline.heldTokens() != 0
                    || baseline.heldCost().signum() != 0
                    || baseline.fault() != null
                    || baseline.settledTokens() > limits.maxTokens()
                    || baseline.settledCost().compareTo(limits.maxCost()) > 0)
                throw new IllegalArgumentException();
            long settled = baseline.settledTokens(), held = 0;
            BigDecimal cost = baseline.settledCost(), heldCost = BigDecimal.ZERO;
            int inFlight = 0, uncertain = 0;
            boolean breached = false;
            for (var receipt : receipts) {
                var rate = limits.models().get(receipt.model());
                if (rate == null
                        || receipt.inputBound() > rate.maxInputTokens()
                        || receipt.outputBound() > rate.maxOutputTokens())
                    throw new IllegalArgumentException();
                switch (receipt.state()) {
                    case PREPARED, DISPATCHED, UNCERTAIN -> {
                        held =
                                Math.addExact(
                                        held, (long) receipt.inputBound() + receipt.outputBound());
                        heldCost =
                                heldCost.add(
                                        rate.cost(receipt.inputBound(), receipt.outputBound()));
                        if (receipt.state() == ModelUsageReservation.State.UNCERTAIN) uncertain++;
                        else inFlight++;
                    }
                    case SETTLED -> {
                        settled =
                                Math.addExact(
                                        settled,
                                        (long) receipt.actualInput() + receipt.actualOutput());
                        cost = cost.add(rate.cost(receipt.actualInput(), receipt.actualOutput()));
                        breached |=
                                receipt.actualInput() > receipt.inputBound()
                                        || receipt.actualOutput() > receipt.outputBound();
                    }
                    case RELEASED -> {}
                }
            }
            if (inFlight > 10000
                    || settled != snapshot.settledTokens()
                    || held != snapshot.heldTokens()
                    || cost.compareTo(snapshot.settledCost()) != 0
                    || heldCost.compareTo(snapshot.heldCost()) != 0
                    || inFlight != snapshot.inFlightCalls()
                    || uncertain != snapshot.uncertainCalls()
                    || ((uncertain > 0 || breached) != (snapshot.fault() != null)))
                throw new IllegalArgumentException();
            if (snapshot.fault() != null
                    && !Set.of(
                                    "model_usage_unconfirmed",
                                    "model_usage_unknown",
                                    "model_usage_invalid",
                                    "model_usage_identity_mismatch",
                                    "model_usage_bound_exceeded",
                                    "model_usage_accounting_overflow")
                            .contains(snapshot.fault())) throw new IllegalArgumentException();
            if (!breached
                    && (Math.addExact(settled, held) > limits.maxTokens()
                            || cost.add(heldCost).compareTo(limits.maxCost()) > 0))
                throw new IllegalArgumentException();
            var budget = new UsageBudget(limits, ignored -> {});
            budget.settledTokens = settled;
            budget.settledCost = cost;
            budget.heldTokens = held;
            budget.heldCost = heldCost;
            budget.inFlight = inFlight;
            budget.uncertain = uncertain;
            budget.fault = snapshot.fault();
            return budget;
        } catch (RuntimeException invalid) {
            throw new GovernanceException("model_usage_checkpoint_denied");
        }
    }

    private static boolean identity(UsageLimits limits, Snapshot snapshot) {
        return snapshot.maxTokens() == limits.maxTokens()
                && snapshot.maxCost().compareTo(limits.maxCost()) == 0
                && snapshot.currency().equals(limits.currency())
                && snapshot.settledTokens() >= 0
                && snapshot.heldTokens() >= 0
                && snapshot.settledCost().signum() >= 0
                && snapshot.heldCost().signum() >= 0
                && snapshot.inFlightCalls() >= 0
                && snapshot.uncertainCalls() >= 0;
    }

    /** 调用方必须同时停止旧账本的新预占；只允许没有未决费用的交接，不执行外部回调。 */
    public synchronized UsageBudget transferred() {
        checkActive();
        if (inFlight != 0 || uncertain != 0 || heldTokens != 0 || heldCost.signum() != 0)
            throw new GovernanceException("model_usage_inflight_export_denied");
        var next = new UsageBudget(limits, code -> {});
        next.settledTokens = settledTokens;
        next.settledCost = settledCost;
        return next;
    }

    public synchronized void checkActive() {
        if (fault != null) throw new GovernanceException(fault);
    }

    public synchronized ModelUsageReservation reserve(
            String owner,
            String model,
            int inputUpperBound,
            int outputUpperBound,
            Consumer<ModelUsageReservation> finished) {
        checkActive();
        Identifiers.require(owner);
        Objects.requireNonNull(finished);
        if (inFlight >= 10000) throw new GovernanceException("model_usage_capacity_exceeded");
        var rate = limits.models().get(model);
        if (rate == null) throw new GovernanceException("model_usage_model_denied");
        if (inputUpperBound < 0
                || inputUpperBound > rate.maxInputTokens()
                || outputUpperBound < 1
                || outputUpperBound > rate.maxOutputTokens()) {
            throw new GovernanceException("model_usage_request_bound_denied");
        }
        long tokens = (long) inputUpperBound + outputUpperBound;
        if (tokens > limits.maxTokens() - settledTokens - heldTokens) {
            throw new GovernanceException("task_token_budget_exceeded");
        }
        BigDecimal cost = rate.cost(inputUpperBound, outputUpperBound);
        if (settledCost.add(heldCost).add(cost).compareTo(limits.maxCost()) > 0) {
            throw new GovernanceException("task_cost_budget_exceeded");
        }
        heldTokens += tokens;
        heldCost = heldCost.add(cost);
        inFlight++;
        return new ModelUsageReservation(
                this, owner, model, inputUpperBound, outputUpperBound, rate, finished);
    }

    synchronized void release(ModelUsageReservation reservation) {
        heldTokens -= reservation.reservedTokens();
        heldCost = heldCost.subtract(reservation.reservedCost());
        inFlight--;
    }

    synchronized boolean settle(
            ModelUsageReservation reservation, int input, int output, String violation) {
        long nextTokens = Math.addExact(settledTokens, (long) input + output);
        BigDecimal nextCost = settledCost.add(reservation.rate().cost(input, output));
        release(reservation);
        settledTokens = nextTokens;
        settledCost = nextCost;
        return violation != null && markFault(violation);
    }

    synchronized boolean uncertain(String code) {
        inFlight--;
        uncertain++;
        return markFault(code);
    }

    private boolean markFault(String code) {
        if (fault != null) return false;
        fault = code;
        return true;
    }

    void signalFault(String code, boolean first) {
        if (first) faultSignal.accept(code);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                settledTokens,
                settledCost,
                heldTokens,
                heldCost,
                inFlight,
                uncertain,
                limits.maxTokens(),
                limits.maxCost(),
                limits.currency(),
                fault);
    }

    public record Snapshot(
            long settledTokens,
            BigDecimal settledCost,
            long heldTokens,
            BigDecimal heldCost,
            int inFlightCalls,
            int uncertainCalls,
            long maxTokens,
            BigDecimal maxCost,
            String currency,
            String fault) {}
}
