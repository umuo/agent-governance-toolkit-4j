package io.agt.approval.jdbc;

import io.agt.core.Identifiers;

/** 申请者持有的私有单次消费/取消能力；不能进入 Trace、模型参数或浏览器列表。 */
public record ApprovalClaim(String requestId, String actionIdentity, String secret) {
    public ApprovalClaim {
        Identifiers.require(requestId);
        if (actionIdentity == null
                || !actionIdentity.matches("sha256:[0-9a-f]{64}")
                || secret == null
                || !secret.matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("Invalid approval claim");
    }

    public String toString() {
        return "ApprovalClaim[redacted]";
    }
}
