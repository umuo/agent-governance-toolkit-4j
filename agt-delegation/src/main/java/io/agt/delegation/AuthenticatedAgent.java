package io.agt.delegation;

import io.agt.core.Identifiers;

/** 宿主从 mTLS/IAM 等认证结果解析的工作负载身份；不得从 Token、请求体或 Trace 头自行复制。 */
public record AuthenticatedAgent(String agentId) {
    public AuthenticatedAgent {
        Identifiers.require(agentId);
    }

    @Override
    public String toString() {
        return "AuthenticatedAgent[redacted]";
    }
}
