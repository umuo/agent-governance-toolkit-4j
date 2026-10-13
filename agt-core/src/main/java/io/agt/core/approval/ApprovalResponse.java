package io.agt.core.approval;

/**
 * 外部审批决策响应。
 *
 * <p>包含关联的审批请求标识、动作摘要哈希以及是否批准的布尔结论。
 */
public record ApprovalResponse(String requestId, String actionIdentity, boolean approved) {
    public static ApprovalResponse allow(ApprovalRequest r) {
        return new ApprovalResponse(r.requestId(), r.actionIdentity(), true);
    }

    public static ApprovalResponse deny(ApprovalRequest r) {
        return new ApprovalResponse(r.requestId(), r.actionIdentity(), false);
    }
}
