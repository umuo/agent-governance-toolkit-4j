package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.usage.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** 接收端只保存凭据与状态，不创建独立预算余额，任何远端错误都不能退回本机结算。 */
final class RemoteUsageLedger implements UsageLedger {
    private final UsageLimits limits;
    private final GrantStatusClient registry;
    private final GrantLease lease;
    private final Supplier<String> session;
    private final Runnable active;
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final AtomicBoolean stopped = new AtomicBoolean();

    RemoteUsageLedger(
            UsageLimits limits,
            GrantStatusClient registry,
            GrantLease lease,
            Supplier<String> session,
            Runnable active) {
        this.limits = limits;
        this.registry = registry;
        this.lease = lease;
        this.session = session;
        this.active = active;
    }

    public UsageLimits limits() {
        return limits;
    }

    String failure() {
        return failure.get();
    }

    void stop() {
        stopped.set(true);
    }

    public void checkActive() {
        if (stopped.get()) throw new GovernanceException("delegation_inactive_denied");
        if (failure.get() != null) throw new GovernanceException(failure.get());
        active.run();
    }

    private <T> T query(Supplier<T> query) {
        try {
            return query.get();
        } catch (GovernanceException denied) {
            throw denied;
        } catch (RuntimeException unknown) {
            throw new GovernanceException("delegation_registry_unavailable");
        }
    }

    private void command(Runnable operation) {
        query(
                () -> {
                    operation.run();
                    return null;
                });
    }

    public ModelUsageReservation reserve(
            String owner,
            String model,
            int input,
            int output,
            Consumer<ModelUsageReservation> finished) {
        Identifiers.require(owner);
        Objects.requireNonNull(finished);
        checkActive();
        if (!owner.equals(session.get()))
            throw new GovernanceException("model_usage_reservation_denied");
        String id = UUID.randomUUID().toString();
        command(() -> registry.usageReserve(lease, owner, id, model, input, output));
        return ModelUsageReservation.external(
                this,
                owner,
                new UsageReceipt() {
                    public void dispatch() {
                        command(() -> registry.usageDispatch(lease, owner, id));
                    }

                    public void settle(
                            String served, Integer input, Integer output, Integer total) {
                        command(
                                () ->
                                        registry.usageSettle(
                                                lease, owner, id, served, input, output, total));
                    }

                    public void close() {
                        if (stopped.get() || failure.get() != null) return;
                        try {
                            command(() -> registry.usageClose(lease, owner, id));
                        } catch (GovernanceException unknown) {
                            failure.compareAndSet(null, unknown.code());
                        }
                    }
                },
                finished);
    }

    public UsageBudget.Snapshot snapshot() {
        var snapshot = query(() -> registry.usageSnapshot(lease, session.get()));
        if (snapshot.maxTokens() != limits.maxTokens()
                || snapshot.maxCost().compareTo(limits.maxCost()) != 0
                || !snapshot.currency().equals(limits.currency()))
            throw new GovernanceException("delegation_document_denied");
        return snapshot;
    }
}
