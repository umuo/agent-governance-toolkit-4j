package io.agt.core.delegation;

import io.agt.core.*;
import java.util.Objects;

/** 一层能力约束及其签发上下文，跨进程传播时不能改按接收方身份求值。 */
public record AuthorityFrame(TrustedContext owner, DelegationScope scope) {
    public AuthorityFrame {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(scope);
    }

    public void validate(PolicyRequest request) {
        scope.validate(
                new PolicyRequest(
                        request.point(),
                        owner,
                        request.sessionId(),
                        request.actionId(),
                        request.toolName(),
                        request.target()));
    }

    @Override
    public String toString() {
        return "AuthorityFrame[redacted]";
    }
}
