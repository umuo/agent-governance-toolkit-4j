package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.usage.*;
import java.time.Clock;
import java.util.*;

/** 只由权威注册表持锁调用，账本来自无未决预占的交接副本；不执行外部回调。 */
final class GrantUsage {
    private enum State {
        PREPARED,
        DISPATCHED,
        SETTLED,
        CLOSED
    }

    private record Key(String grant, String reservation) {}

    private static final class Receipt {
        final ModelUsageReservation reservation;
        final String fingerprint;
        State state = State.PREPARED;
        String settlement;

        Receipt(ModelUsageReservation reservation, String fingerprint) {
            this.reservation = reservation;
            this.fingerprint = fingerprint;
        }
    }

    private final UsageBudget budget;
    private final Clock clock;
    private final UsageBudget.Snapshot baseline;
    private final Map<Key, Receipt> receipts = new HashMap<>();
    private final Map<String, Long> deadlines = new HashMap<>();
    private final Map<String, Set<String>> owned = new HashMap<>();
    private final Set<String> ended = new HashSet<>();
    private final Map<String, Set<String>> children = new HashMap<>();

    GrantUsage(UsageBudget source, Clock clock) {
        // 再次取私有副本，防止传入的宿主回调在注册表锁内被调用。
        budget = source.transferred();
        baseline = budget.snapshot();
        this.clock = clock;
    }

    private GrantUsage(UsageBudget budget, UsageBudget.Snapshot baseline, Clock clock) {
        this.budget = budget;
        this.baseline = baseline;
        this.clock = clock;
    }

    RegistryCheckpoint.Usage checkpoint() {
        var values =
                receipts.entrySet().stream()
                        .map(
                                entry ->
                                        new RegistryCheckpoint.Receipt(
                                                entry.getKey().grant(),
                                                entry.getKey().reservation(),
                                                entry.getValue().state.name(),
                                                entry.getValue().fingerprint,
                                                entry.getValue().settlement,
                                                entry.getValue().reservation.checkpoint()))
                        .sorted(
                                Comparator.comparing(RegistryCheckpoint.Receipt::grant)
                                        .thenComparing(RegistryCheckpoint.Receipt::id))
                        .toList();
        return new RegistryCheckpoint.Usage(
                baseline, budget.snapshot(), values, deadlines, ended, children);
    }

    static GrantUsage restored(
            UsageLimits limits, RegistryCheckpoint.Usage checkpoint, Clock clock) {
        try {
            var budget =
                    UsageBudget.restored(
                            limits,
                            checkpoint.baseline(),
                            checkpoint.balance(),
                            checkpoint.receipts().stream()
                                    .map(RegistryCheckpoint.Receipt::reservation)
                                    .toList());
            var result = new GrantUsage(budget, checkpoint.baseline(), clock);
            for (var value : checkpoint.receipts()) {
                Identifiers.require(value.grant());
                Identifiers.require(value.id());
                var held = value.reservation();
                if (!held.owner().equals(value.grant())
                        || !JsonValues.digest(
                                        List.of(
                                                held.model(),
                                                held.inputBound(),
                                                held.outputBound()))
                                .equals(value.fingerprint())) throw new IllegalArgumentException();
                var stage = State.valueOf(value.stage());
                boolean valid =
                        switch (stage) {
                            case PREPARED -> held.state() == ModelUsageReservation.State.PREPARED;
                            case DISPATCHED ->
                                    held.state() == ModelUsageReservation.State.DISPATCHED;
                            case SETTLED ->
                                    held.state() == ModelUsageReservation.State.SETTLED
                                            || held.state()
                                                    == ModelUsageReservation.State.UNCERTAIN;
                            case CLOSED ->
                                    held.state() == ModelUsageReservation.State.RELEASED
                                            || held.state()
                                                    == ModelUsageReservation.State.UNCERTAIN;
                        };
                if (!valid
                        || ((stage == State.SETTLED) != (value.settlement() != null))
                        || (value.settlement() != null
                                && !value.settlement().matches("sha256:[0-9a-f]{64}")))
                    throw new IllegalArgumentException();
                var receipt =
                        new Receipt(
                                ModelUsageReservation.restored(budget, held), value.fingerprint());
                receipt.state = stage;
                receipt.settlement = value.settlement();
                if (result.receipts.put(new Key(value.grant(), value.id()), receipt) != null)
                    throw new IllegalArgumentException();
                result.owned
                        .computeIfAbsent(value.grant(), ignored -> new HashSet<>())
                        .add(value.id());
            }
            checkpoint
                    .deadlines()
                    .forEach(
                            (id, deadline) -> {
                                Identifiers.require(id);
                                if (deadline < 0 || checkpoint.ended().contains(id))
                                    throw new IllegalArgumentException();
                            });
            result.deadlines.putAll(checkpoint.deadlines());
            checkpoint.ended().forEach(Identifiers::require);
            result.ended.addAll(checkpoint.ended());
            checkpoint
                    .children()
                    .forEach(
                            (parent, values) -> {
                                Identifiers.require(parent);
                                values.forEach(Identifiers::require);
                                result.children.put(parent, new HashSet<>(values));
                            });
            var parents = new HashMap<String, String>();
            result.children.forEach(
                    (parent, values) ->
                            values.forEach(
                                    child -> {
                                        if (parents.put(child, parent) != null)
                                            throw new IllegalArgumentException();
                                    }));
            for (String child : parents.keySet()) {
                var path = new HashSet<String>();
                String node = child;
                while (node != null) {
                    if (!path.add(node) || path.size() > 33) throw new IllegalArgumentException();
                    node = parents.get(node);
                }
            }
            return result;
        } catch (RuntimeException invalid) {
            throw new GovernanceException("delegation_checkpoint_denied");
        }
    }

    void renew(String grant, String parent, long deadline) {
        if (ended.contains(grant) || ended.contains(parent))
            throw new GovernanceException("delegation_inactive_denied");
        children.computeIfAbsent(parent, ignored -> new HashSet<>()).add(grant);
        deadlines.put(grant, deadline);
    }

    void sweep() {
        var expired = new ArrayList<String>();
        deadlines.forEach(
                (owner, deadline) -> {
                    if (clock.millis() >= deadline) expired.add(owner);
                });
        expired.forEach(this::end);
    }

    void checkActive() {
        sweep();
        budget.checkActive();
    }

    void reserve(String grant, String id, String model, int input, int output) {
        checkActive();
        var key = new Key(grant, Identifiers.require(id));
        String fingerprint = JsonValues.digest(List.of(model, input, output));
        var previous = receipts.get(key);
        if (previous != null) {
            if (!previous.fingerprint.equals(fingerprint))
                throw new GovernanceException("model_usage_reservation_denied");
            return;
        }
        if (receipts.size() >= 100000)
            throw new GovernanceException("model_usage_capacity_exceeded");
        var reservation = budget.reserve(grant, model, input, output, ignored -> {});
        receipts.put(key, new Receipt(reservation, fingerprint));
        owned.computeIfAbsent(grant, ignored -> new HashSet<>()).add(id);
    }

    private Receipt receipt(String grant, String id) {
        var receipt = receipts.get(new Key(grant, Identifiers.require(id)));
        if (receipt == null) throw new GovernanceException("model_usage_reservation_denied");
        return receipt;
    }

    void dispatch(String grant, String id) {
        checkActive();
        var receipt = receipt(grant, id);
        if (receipt.state == State.DISPATCHED) return;
        if (receipt.state != State.PREPARED)
            throw new GovernanceException("model_usage_state_invalid");
        receipt.reservation.dispatch(grant, budget);
        receipt.state = State.DISPATCHED;
    }

    void settle(
            String grant, String id, String model, Integer input, Integer output, Integer total) {
        checkActive();
        var receipt = receipt(grant, id);
        String fingerprint = JsonValues.digest(Arrays.asList(model, input, output, total));
        if (receipt.state == State.SETTLED && fingerprint.equals(receipt.settlement)) return;
        if (receipt.state != State.DISPATCHED)
            throw new GovernanceException("model_usage_state_invalid");
        // 先记录终态，未知/越界用量也不能在重试时重复记账。
        receipt.state = State.SETTLED;
        receipt.settlement = fingerprint;
        receipt.reservation.settle(model, input, output, total);
    }

    void close(String grant, String id) {
        var receipt = receipt(grant, id);
        if (receipt.state == State.PREPARED || receipt.state == State.DISPATCHED) {
            receipt.state = State.CLOSED;
            receipt.reservation.close();
        }
    }

    void end(String grant) {
        deadlines.remove(grant);
        if (!ended.add(grant)) return;
        for (var id : owned.getOrDefault(grant, Set.of())) close(grant, id);
        for (var child : children.getOrDefault(grant, Set.of())) end(child);
    }

    void endAll() {
        deadlines.clear();
        children.values().forEach(ended::addAll);
        for (var entry : receipts.entrySet())
            close(entry.getKey().grant(), entry.getKey().reservation());
    }

    UsageBudget.Snapshot snapshot() {
        sweep();
        return budget.snapshot();
    }
}
