package io.agt.core.execution;

/** 宿主在发出外部操作前登记的生命周期句柄；本身不授予执行权限。 */
public final class ExternalExecution implements AutoCloseable {
    private final ExternalExecutionRegistry registry;
    private final String id;
    private final Runnable active;

    ExternalExecution(ExternalExecutionRegistry registry, String id, Runnable active) {
        this.registry = registry;
        this.id = id;
        this.active = active;
    }

    public String id() {
        return id;
    }

    public void started() {
        active.run();
        registry.start(id);
    }

    public void finished(TerminationEvidence evidence) {
        registry.finish(id, evidence);
    }

    public void requestCancellation() {
        registry.cancel(id);
    }

    public void close() {
        requestCancellation();
    }
}
