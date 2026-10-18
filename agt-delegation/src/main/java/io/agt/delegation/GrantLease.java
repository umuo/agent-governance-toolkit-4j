package io.agt.delegation;

import io.agt.core.Identifiers;

/** 在线授权的私有租约凭据，仅宿主/认证通道持有，不能放进提示词、Trace 或 URL。 */
public record GrantLease(String grantId, String secret) {
    public GrantLease {
        Identifiers.require(grantId);
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("Invalid grant lease");
    }

    @Override
    public String toString() {
        return "GrantLease[redacted]";
    }
}
