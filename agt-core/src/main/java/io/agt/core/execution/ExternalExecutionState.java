package io.agt.core.execution;

import static io.agt.core.execution.ExternalExecutionRegistry.State.*;

import io.agt.core.*;
import java.time.Clock;
import java.util.*;

/** 可恢复的纯状态；无线程、回调或外部执行。时间来自宿主权威时钟。 */
public final class ExternalExecutionState {
    public static final int MAX_RECORDS = 100000, MAX_ACTIVE = 128, PAGE_SIZE = 128;

    public record Row(ExternalExecutionRegistry.EntrySnapshot entry, long cancellationUntil) {}

    public record Checkpoint(String taskId, long revision, String fault, List<Row> rows) {
        public Checkpoint {
            Identifiers.require(taskId);
            rows = List.copyOf(rows);
            if (rows.size() > MAX_RECORDS || revision < 0 || revision > Long.MAX_VALUE - 1000000)
                throw new IllegalArgumentException("Invalid external checkpoint");
        }
    }

    public record Summary(
            String taskId,
            long revision,
            int records,
            int active,
            int pendingCancellation,
            int unknown,
            boolean allTerminationConfirmed,
            String fault) {}

    public record Page(long revision, int offset, int total, List<Row> rows) {
        public Page {
            rows = List.copyOf(rows);
        }
    }

    private final String taskId;
    private final Clock clock;
    private final LinkedHashMap<String, Row> entries = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final Map<String, Long> deadlines = new HashMap<>();
    private long revision;
    private String fault;
    private int active, pending, unknown, unconfirmed;

    public ExternalExecutionState(String taskId, Clock clock) {
        this.taskId = Identifiers.require(taskId);
        this.clock = Objects.requireNonNull(clock);
    }

    public static ExternalExecutionState restored(Checkpoint checkpoint, Clock clock) {
        try {
            var state = new ExternalExecutionState(checkpoint.taskId(), clock);
            if (checkpoint.fault() != null
                    && !checkpoint.fault().equals("external_execution_unconfirmed"))
                throw new IllegalArgumentException();
            for (var row : checkpoint.rows()) {
                var e = row.entry();
                Identifiers.require(e.executionId());
                Identifiers.require(e.sessionId());
                Identifiers.require(e.kind());
                boolean full = full(e.evidence());
                boolean valid =
                        switch (e.state()) {
                            case PREPARED, RUNNING ->
                                    !e.cancellationRequested()
                                            && e.evidence() == null
                                            && row.cancellationUntil() == 0;
                            case CANCEL_REQUESTED ->
                                    e.cancellationRequested()
                                            && e.evidence() == null
                                            && row.cancellationUntil() > 0;
                            case COMPLETED ->
                                    !e.cancellationRequested()
                                            && (full
                                                    || e.evidence()
                                                            == TerminationEvidence.WORKER_ONLY)
                                            && row.cancellationUntil() == 0;
                            case CANCEL_CONFIRMED ->
                                    e.cancellationRequested()
                                            && full
                                            && row.cancellationUntil() == 0;
                            case UNKNOWN ->
                                    (e.evidence() == TerminationEvidence.UNKNOWN
                                                    || e.evidence()
                                                            == TerminationEvidence.WORKER_ONLY)
                                            && row.cancellationUntil() == 0;
                        };
                if (!valid || state.entries.putIfAbsent(e.executionId(), row) != null)
                    throw new IllegalArgumentException();
                state.order.add(e.executionId());
                state.count(row, 1);
                if (e.state() == CANCEL_REQUESTED)
                    state.deadlines.put(e.executionId(), row.cancellationUntil());
            }
            if (state.active > MAX_ACTIVE
                    || state.pending > MAX_ACTIVE
                    || (state.unknown > 0 && checkpoint.fault() == null))
                throw new IllegalArgumentException();
            state.fault = checkpoint.fault();
            state.revision = checkpoint.revision();
            return state;
        } catch (RuntimeException invalid) {
            throw new GovernanceException("external_execution_checkpoint_denied");
        }
    }

    public static Checkpoint settled(ExternalExecutionRegistry.Snapshot snapshot) {
        if (snapshot.active() != 0 || snapshot.fault() != null)
            throw new GovernanceException("external_execution_inflight_denied");
        var checkpoint =
                new Checkpoint(
                        snapshot.taskId(),
                        snapshot.entries().size() * 4L,
                        null,
                        snapshot.entries().stream().map(e -> new Row(e, 0)).toList());
        if (!restored(checkpoint, Clock.systemUTC()).snapshot().equals(snapshot))
            throw new GovernanceException("external_execution_checkpoint_denied");
        return checkpoint;
    }

    private static boolean full(TerminationEvidence evidence) {
        return evidence == TerminationEvidence.CONFIRMED
                || evidence == TerminationEvidence.NOT_STARTED;
    }

    private void count(Row row, int direction) {
        var e = row.entry();
        if (e.state() != COMPLETED && e.state() != CANCEL_CONFIRMED) active += direction;
        if (e.state() == CANCEL_REQUESTED) pending += direction;
        if (e.state() == UNKNOWN) unknown += direction;
        if (!full(e.evidence())) unconfirmed += direction;
    }

    private void put(Row row) {
        if (row.equals(entries.get(row.entry().executionId()))) return;
        var previous = entries.put(row.entry().executionId(), row);
        if (previous == null) order.add(row.entry().executionId());
        if (previous != null) count(previous, -1);
        count(row, 1);
        revision = Math.addExact(revision, 1);
        if (row.entry().state() == CANCEL_REQUESTED)
            deadlines.put(row.entry().executionId(), row.cancellationUntil());
        else deadlines.remove(row.entry().executionId());
    }

    private Row own(String id, String session) {
        var row = entries.get(Identifiers.require(id));
        if (row == null || !row.entry().sessionId().equals(session))
            throw new GovernanceException("external_execution_owner_denied");
        return row;
    }

    public synchronized void register(String id, String session, String kind) {
        checkActive();
        Identifiers.require(id);
        Identifiers.require(session);
        Identifiers.require(kind);
        var previous = entries.get(id);
        if (previous != null) {
            if (!previous.entry().sessionId().equals(session)
                    || !previous.entry().kind().equals(kind))
                throw new GovernanceException("external_execution_identity_denied");
            return;
        }
        if (entries.size() >= MAX_RECORDS || active >= MAX_ACTIVE)
            throw new GovernanceException("external_execution_capacity_exceeded");
        put(
                new Row(
                        new ExternalExecutionRegistry.EntrySnapshot(
                                id, session, kind, PREPARED, false, null),
                        0));
    }

    public synchronized void start(String id, String session) {
        checkActive();
        var row = own(id, session);
        var e = row.entry();
        if (e.state() == RUNNING) return; // 仅重试元数据准入，不能据此重放外部工作。
        if (e.state() != PREPARED) throw new GovernanceException("external_execution_start_denied");
        put(
                new Row(
                        new ExternalExecutionRegistry.EntrySnapshot(
                                id, session, e.kind(), RUNNING, false, null),
                        0));
    }

    public synchronized void cancel(String id, String session) {
        sweep();
        var row = own(id, session);
        var e = row.entry();
        if (e.state() == COMPLETED || e.state() == CANCEL_CONFIRMED || e.cancellationRequested())
            return;
        if (e.state() == PREPARED) {
            put(
                    new Row(
                            new ExternalExecutionRegistry.EntrySnapshot(
                                    id,
                                    session,
                                    e.kind(),
                                    CANCEL_CONFIRMED,
                                    true,
                                    TerminationEvidence.NOT_STARTED),
                            0));
        } else if (e.state() == UNKNOWN) {
            put(
                    new Row(
                            new ExternalExecutionRegistry.EntrySnapshot(
                                    id, session, e.kind(), UNKNOWN, true, e.evidence()),
                            0));
        } else {
            put(
                    new Row(
                            new ExternalExecutionRegistry.EntrySnapshot(
                                    id, session, e.kind(), CANCEL_REQUESTED, true, null),
                            Math.addExact(clock.millis(), 2000)));
        }
    }

    public synchronized void finish(String id, String session, TerminationEvidence evidence) {
        sweep();
        Objects.requireNonNull(evidence);
        var e = own(id, session).entry();
        if (e.state() == COMPLETED || e.state() == CANCEL_CONFIRMED) {
            if (e.evidence() != evidence && evidence != TerminationEvidence.UNKNOWN)
                throw new GovernanceException("external_execution_receipt_conflict");
            return;
        }
        if (e.state() == PREPARED && evidence != TerminationEvidence.NOT_STARTED)
            throw new GovernanceException("external_execution_receipt_denied");
        var next =
                full(evidence)
                        ? (e.cancellationRequested() ? CANCEL_CONFIRMED : COMPLETED)
                        : (!e.cancellationRequested() && evidence == TerminationEvidence.WORKER_ONLY
                                ? COMPLETED
                                : UNKNOWN);
        put(
                new Row(
                        new ExternalExecutionRegistry.EntrySnapshot(
                                id, session, e.kind(), next, e.cancellationRequested(), evidence),
                        0));
        if (next == UNKNOWN) fault = "external_execution_unconfirmed";
    }

    private void sweep() {
        long now = clock.millis();
        for (String id : List.copyOf(deadlines.keySet())) {
            if (now < deadlines.get(id)) continue;
            var e = entries.get(id).entry();
            put(
                    new Row(
                            new ExternalExecutionRegistry.EntrySnapshot(
                                    id,
                                    e.sessionId(),
                                    e.kind(),
                                    UNKNOWN,
                                    true,
                                    TerminationEvidence.UNKNOWN),
                            0));
            fault = "external_execution_unconfirmed";
        }
    }

    public synchronized void checkActive() {
        sweep();
        if (fault != null) throw new GovernanceException(fault);
    }

    public synchronized Checkpoint checkpoint() {
        sweep();
        return new Checkpoint(taskId, revision, fault, List.copyOf(entries.values()));
    }

    public synchronized Summary summary() {
        sweep();
        return new Summary(
                taskId,
                revision,
                entries.size(),
                active,
                pending,
                unknown,
                unconfirmed == 0,
                fault);
    }

    public synchronized Page page(long expectedRevision, int offset) {
        sweep();
        if (expectedRevision != revision)
            throw new GovernanceException("external_execution_snapshot_changed");
        if (offset < 0 || offset > entries.size())
            throw new GovernanceException("external_execution_page_denied");
        var rows = new ArrayList<Row>();
        for (int i = offset; i < Math.min(entries.size(), offset + PAGE_SIZE); i++)
            rows.add(entries.get(order.get(i)));
        return new Page(revision, offset, entries.size(), rows);
    }

    public synchronized Row row(String id) {
        var row = entries.get(id);
        if (row == null) throw new GovernanceException("external_execution_owner_denied");
        return row;
    }

    public synchronized ExternalExecutionRegistry.Snapshot snapshot() {
        var summary = summary();
        return new ExternalExecutionRegistry.Snapshot(
                taskId,
                entries.values().stream().map(Row::entry).toList(),
                summary.active(),
                summary.pendingCancellation(),
                summary.unknown(),
                summary.allTerminationConfirmed(),
                fault);
    }
}
