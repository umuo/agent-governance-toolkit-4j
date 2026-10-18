package io.agt.approval.jdbc;

import io.agt.core.approval.ApprovalRequest;

/** 先持久记录查看，再向已认证审批人交付完整有效动作；reviewSecret 不应进入通用日志。 */
public record ApprovalReview(ApprovalRequest request, long revision, String reviewSecret) {
    public String toString() {
        return "ApprovalReview[redacted]";
    }
}
