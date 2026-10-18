package io.agt.core.flow;

import io.agt.core.PolicyRequest;

/** 共享信息流状态；远端实现只发送必要授权元数据，不发送模型或数据正文。 */
public interface DataFlowLedger {
    void validate(PolicyRequest request);

    void beforeTool(String name, Object arguments);

    void beforeModel();

    void beforeData(String actionId, Object target);

    void completedTool(String actionId, String name);

    DataFlowState.Snapshot snapshot();
}
