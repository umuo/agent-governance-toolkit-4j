package io.agt.core;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.policy.StructuredPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 先发生的生命周期终止和执行错误不能被迟到清理原因覆盖。 */
class FailureCauseTest {
    @ParameterizedTest
    @ValueSource(strings = {"active", "revoke", "close"})
    void knownLifecycleOrFirstExecutionFailureKeepsPriority(String phase) {
        try (var control =
                        AgentControl.builder(StructuredPolicy.builder("failure-priority").build())
                                .audit(r -> {})
                                .build();
                var session =
                        control.openDetachedSession(
                                new TrustedContext(
                                        "agent", "tenant", "user", java.util.Set.of()))) {
            if (phase.equals("revoke")) {
                session.revoke();
            }
            if (phase.equals("close")) {
                session.close();
            }
            session.noteFailureCause("sandbox_timeout");
            session.noteFailureCause("external_execution_unconfirmed");
            assertEquals(
                    phase.equals("active")
                            ? "sandbox_timeout"
                            : phase.equals("revoke") ? "session_revoked" : "session_closed",
                    session.failureCode());
        }
    }
}
