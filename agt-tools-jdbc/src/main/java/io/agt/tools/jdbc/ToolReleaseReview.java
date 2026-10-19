package io.agt.tools.jdbc;

import io.agt.core.tools.ToolDescriptor;

/** 仅在查看事件持久确认后交给可信审批宿主；私有凭据不能从请求 JSON 自建。 */
public final class ToolReleaseReview {
    final String authority, binding, reviewer, token, id, secret;
    final long expires;
    private final ToolDescriptor descriptor;

    ToolReleaseReview(
            String authority,
            String binding,
            String reviewer,
            String token,
            String id,
            String secret,
            long expires,
            ToolDescriptor descriptor) {
        this.authority = authority;
        this.binding = binding;
        this.reviewer = reviewer;
        this.token = token;
        this.id = id;
        this.secret = secret;
        this.expires = expires;
        this.descriptor = descriptor;
    }

    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public String toString() {
        return "ToolReleaseReview[redacted]";
    }
}
