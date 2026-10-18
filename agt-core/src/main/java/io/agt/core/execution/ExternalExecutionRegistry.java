package io.agt.core.execution;

import io.agt.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 单 JVM 根任务共享的有界回执；未知取消不释放执行名额、不声称外部已停止。 */
public final class ExternalExecutionRegistry {
    public enum State {
        PREPARED,
        RUNNING,
        CANCEL_REQUESTED,
        COMPLETED,
        CANCEL_CONFIRMED,
        UNKNOWN
    }

    public record EntrySnapshot(
            String executionId,
            String sessionId,
            String kind,
            State state,
            boolean cancellationRequested,
            TerminationEvidence evidence) {}

    public record Snapshot(
            String taskId,
            List<EntrySnapshot> entries,
            int active,
            int pendingCancellation,
            int unknown,
            boolean allTerminationConfirmed,
            String fault) {
        public Snapshot {
            entries = List.copyOf(entries);
        }
    }

    private static final int MAX_RECORDS = 100000, MAX_ACTIVE = 128;
    private final String taskId;
    private final Executor executor;
    private final ScheduledExecutorService timer;
    private final Consumer<String> failure;
    private final Set<ExternalExecutionRegistry> live;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private String fault;
    private String replicationFault;
    private final ExternalExecutionLedger mirror;

    private static final class Entry {
        final String id = UUID.randomUUID().toString(), owner, kind;
        State state = State.PREPARED;
        boolean cancelled;
        TerminationEvidence evidence;
        ExternalCancellation cancellation;
        ScheduledFuture<?> timeout;

        Entry(String owner, String kind, ExternalCancellation cancellation) {
            this.owner = owner;
            this.kind = kind;
            this.cancellation = cancellation;
        }
    }

    public ExternalExecutionRegistry(
            String taskId,
            Executor executor,
            ScheduledExecutorService timer,
            Consumer<String> failure,
            Set<ExternalExecutionRegistry> live) {
        this(taskId, executor, timer, failure, live, null);
    }

    public ExternalExecutionRegistry(
            String taskId,
            Executor executor,
            ScheduledExecutorService timer,
            Consumer<String> failure,
            Set<ExternalExecutionRegistry> live,
            ExternalExecutionLedger mirror) {
        this.taskId = Identifiers.require(taskId);
        this.executor = Objects.requireNonNull(executor);
        this.timer = Objects.requireNonNull(timer);
        this.failure = Objects.requireNonNull(failure);
        this.live = Objects.requireNonNull(live);
        this.mirror = mirror;
    }

    public synchronized ExternalExecution register(
            String owner, String kind, ExternalCancellation cancellation, Runnable active) {
        checkActive();
        if (entries.size() >= MAX_RECORDS || activeCount() >= MAX_ACTIVE)
            throw new GovernanceException("external_execution_capacity_exceeded");
        var entry =
                new Entry(
                        Identifiers.require(owner),
                        Identifiers.require(kind),
                        Objects.requireNonNull(cancellation));
        if (mirror != null) mirror.register(entry.id, entry.owner, entry.kind);
        entries.put(entry.id, entry);
        live.add(this);
        return new ExternalExecution(this, entry.id, active);
    }

    synchronized void start(String id) {
        checkActive();
        var entry = entries.get(id);
        if (entry.state != State.PREPARED)
            throw new GovernanceException("external_execution_start_denied");
        if (mirror != null) mirror.start(id, entry.owner);
        entry.state = State.RUNNING;
    }

    public synchronized void checkActive() {
        if (fault != null) throw new GovernanceException(fault);
        if (replicationFault != null) throw new GovernanceException(replicationFault);
    }

    public synchronized boolean empty() {
        return entries.isEmpty();
    }

    public void cancelOwner(String owner) {
        List<String> ids;
        synchronized (this) {
            ids =
                    entries.values().stream()
                            .filter(e -> e.owner.equals(owner))
                            .map(e -> e.id)
                            .toList();
        }
        ids.forEach(this::cancel);
    }

    void cancel(String id) {
        ExternalCancellation handler;
        boolean scheduled = true;
        synchronized (this) {
            var entry = entries.get(id);
            if (entry.state == State.COMPLETED
                    || entry.state == State.CANCEL_CONFIRMED
                    || entry.cancelled) return;
            entry.cancelled = true;
            if (entry.state == State.PREPARED) {
                entry.state = State.CANCEL_CONFIRMED;
                entry.evidence = TerminationEvidence.NOT_STARTED;
                entry.cancellation = null;
                if (activeCount() == 0) live.remove(this);
                publish(
                        () -> {
                            mirror.cancel(id, entry.owner);
                            mirror.finish(id, entry.owner, TerminationEvidence.NOT_STARTED);
                        });
                return;
            }
            entry.state = State.CANCEL_REQUESTED;
            handler = entry.cancellation;
            try {
                entry.timeout =
                        timer.schedule(
                                () -> finish(id, TerminationEvidence.UNKNOWN), 2, TimeUnit.SECONDS);
            } catch (RejectedExecutionException unavailable) {
                scheduled = false;
            }
        }
        if (!scheduled) finish(id, TerminationEvidence.UNKNOWN);
        try {
            executor.execute(
                    () -> {
                        try {
                            var receipt = Objects.requireNonNull(handler.requestCancellation());
                            if (mirror != null) {
                                try {
                                    mirror.cancel(id, owner(id));
                                } catch (RuntimeException unavailable) {
                                    unavailable();
                                }
                            }
                            receipt.whenComplete(
                                    (proof, error) ->
                                            finish(
                                                    id,
                                                    error == null && proof != null
                                                            ? proof
                                                            : TerminationEvidence.UNKNOWN));
                        } catch (Throwable unavailable) {
                            finish(id, TerminationEvidence.UNKNOWN);
                        }
                    });
        } catch (RejectedExecutionException unavailable) {
            finish(id, TerminationEvidence.UNKNOWN);
        }
    }

    void finish(String id, TerminationEvidence evidence) {
        boolean failed = false;
        boolean cancelled;
        synchronized (this) {
            Objects.requireNonNull(evidence);
            var entry = entries.get(id);
            if (entry.state == State.COMPLETED || entry.state == State.CANCEL_CONFIRMED) return;
            cancelled = entry.cancelled;
            if (entry.state == State.PREPARED && evidence != TerminationEvidence.NOT_STARTED)
                throw new GovernanceException("external_execution_receipt_denied");
            if (entry.timeout != null) entry.timeout.cancel(false);
            entry.evidence = evidence;
            if (evidence == TerminationEvidence.CONFIRMED
                    || evidence == TerminationEvidence.NOT_STARTED) {
                entry.state = entry.cancelled ? State.CANCEL_CONFIRMED : State.COMPLETED;
                entry.cancellation = null;
            } else if (!entry.cancelled && evidence == TerminationEvidence.WORKER_ONLY) {
                entry.state = State.COMPLETED;
                entry.cancellation = null;
            } else {
                entry.state = State.UNKNOWN;
                if (fault == null) {
                    fault = "external_execution_unconfirmed";
                    failed = true;
                }
            }
            if (activeCount() == 0) live.remove(this);
        }
        // 消费者会调度根任务级终止；不能在目录锁内获取会话锁。
        if (failed) failure.accept("external_execution_unconfirmed");
        publish(
                () -> {
                    // 进程退出回调可能抢先于取消线程发布；同一回执先补齐取消事实。
                    if (cancelled) mirror.cancel(id, owner(id));
                    mirror.finish(id, owner(id), evidence);
                });
    }

    private synchronized String owner(String id) {
        return entries.get(id).owner;
    }

    private void publish(Runnable operation) {
        if (mirror == null) return;
        try {
            executor.execute(
                    () -> {
                        try {
                            operation.run();
                        } catch (RuntimeException unavailable) {
                            unavailable();
                        }
                    });
        } catch (RejectedExecutionException unavailable) {
            unavailable();
        }
    }

    private void unavailable() {
        boolean notify;
        synchronized (this) {
            notify = replicationFault == null;
            replicationFault = "external_execution_registry_unavailable";
        }
        if (notify) failure.accept("external_execution_registry_unavailable");
    }

    public synchronized ExternalExecutionState.Checkpoint settledCheckpoint() {
        return ExternalExecutionState.settled(snapshot());
    }

    public void runtimeClosed() {
        List<String> ids;
        synchronized (this) {
            ids =
                    entries.values().stream()
                            .filter(
                                    e ->
                                            e.state != State.COMPLETED
                                                    && e.state != State.CANCEL_CONFIRMED)
                            .map(e -> e.id)
                            .toList();
        }
        for (String id : ids) {
            cancel(id);
            finish(id, TerminationEvidence.UNKNOWN);
        }
    }

    private int activeCount() {
        return (int)
                entries.values().stream()
                        .filter(
                                e ->
                                        e.state != State.COMPLETED
                                                && e.state != State.CANCEL_CONFIRMED)
                        .count();
    }

    public synchronized Snapshot snapshot() {
        var values =
                entries.values().stream()
                        .map(
                                e ->
                                        new EntrySnapshot(
                                                e.id,
                                                e.owner,
                                                e.kind,
                                                e.state,
                                                e.cancelled,
                                                e.evidence))
                        .toList();
        return new Snapshot(
                taskId,
                values,
                activeCount(),
                (int) values.stream().filter(e -> e.state() == State.CANCEL_REQUESTED).count(),
                (int) values.stream().filter(e -> e.state() == State.UNKNOWN).count(),
                values.stream()
                        .allMatch(
                                e ->
                                        e.evidence() == TerminationEvidence.CONFIRMED
                                                || e.evidence() == TerminationEvidence.NOT_STARTED),
                fault == null ? replicationFault : fault);
    }
}
