package io.agt.core.approval;

import java.util.concurrent.CompletionStage;

/** 宿主应立即返回 stage；等待由治理运行时有界处理。 */
@FunctionalInterface
public interface ApprovalResolver {
  CompletionStage<ApprovalResponse> resolve(ApprovalRequest request);
}
