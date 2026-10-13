package io.agt.core.approval;

import io.agt.core.PolicyRequest;

/**
 * 人工或外部系统审批请求。
 *
 * <p>包含请求标识、动作摘要、策略版本及超时过期绝对时间戳。
 */
public record ApprovalRequest(
        String requestId,
        String actionIdentity,
        String policyVersion,
        PolicyRequest action,
        long expiresAtEpochMillis) {
    @Override
    public String toString() {
        return "ApprovalRequest[" + requestId + "]";
    }
}
