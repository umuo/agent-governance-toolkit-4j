package io.agt.core.execution;

/** 外部执行权威元数据接口；实现不得重放实际工作或在后台自动授予新权限。 */
public interface ExternalExecutionLedger {
    void register(String executionId, String sessionId, String kind);

    void start(String executionId, String sessionId);

    void cancel(String executionId, String sessionId);

    void finish(String executionId, String sessionId, TerminationEvidence evidence);

    ExternalExecutionRegistry.Snapshot snapshot();
}
