package io.agt.core.audit;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.policy.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class AuditCaptureTest {
    static final TrustedContext ID = new TrustedContext("agent", "tenant", "user", Set.of("read"));

    static AuditBodyPolicy bodies() {
        return new AuditBodyPolicy(
                Set.of(InterventionPoint.PRE_TOOL_CALL, InterventionPoint.POST_TOOL_CALL),
                Set.of("payload", "headers"),
                true,
                10000,
                4096);
    }

    static StructuredPolicy policy() {
        return StructuredPolicy.builder("audit-v1")
                .tool(
                        "read",
                        new ToolRule(
                                Set.of("read"),
                                ParameterRule.object(
                                        Map.of("payload", ParameterRule.string(null)), "payload"),
                                false))
                .build();
    }

    @Test
    void defaultLegacySinkNeverReceivesBodyAndRemainsFunctional() {
        var records = new ArrayList<AuditRecord>();
        try (var control = AgentControl.builder(policy()).audit(records::add).build();
                var session = control.openSession(ID)) {
            session.operation(
                    true,
                    "read",
                    Map.of("payload", "synthetic-private-content"),
                    value -> "result",
                    value -> value,
                    (a, b) -> (String) b);
        }
        assertFalse(records.isEmpty());
        assertFalse(records.toString().contains("synthetic-private-content"));
        assertNull(
                AuditBodyPolicy.NONE
                        .capture(InterventionPoint.PRE_TOOL_CALL, new Object(), new Object())
                        .content());
    }

    @Test
    void hostScopeCannotBeReplacedByFieldsInToolPayloadAndPolicyIsSnapshotted() {
        var events = new ArrayList<AuditEvent>();
        var config = new AtomicReference<>(bodies());
        var reads = new AtomicInteger();
        AuditSink sink =
                new AuditSink() {
                    public AuditBodyPolicy bodyPolicy() {
                        reads.incrementAndGet();
                        return config.get();
                    }

                    public void write(AuditRecord ignored) {
                        fail("Contextual event required");
                    }

                    public void write(AuditEvent event) {
                        events.add(event);
                    }
                };
        try (var control = AgentControl.builder(policy()).audit(sink).build();
                var session = control.openSession(ID)) {
            config.set(AuditBodyPolicy.NONE);
            session.operation(
                    true,
                    "read",
                    Map.of("payload", "{\"tenantId\":\"forged\",\"password\":\"test-secret\"}"),
                    value -> "Bearer synthetic-test-credential",
                    value -> value,
                    (a, b) -> (String) b);
            var event =
                    events.stream()
                            .filter(e -> e.record().point() == InterventionPoint.PRE_TOOL_CALL)
                            .findFirst()
                            .orElseThrow();
            assertEquals("tenant", event.scope().tenantId());
            assertEquals("user", event.scope().principalId());
            assertEquals(session.taskSnapshot().taskId(), event.scope().taskId());
            assertEquals("captured", event.body().status());
            assertFalse(JsonValues.write(event.body().content()).contains("test-secret"));
            assertFalse(event.toString().contains("forged"));
        }
        assertEquals(1, reads.get());
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e.record().point() == InterventionPoint.POST_TOOL_CALL
                                                && !JsonValues.write(e.body().content())
                                                        .contains("synthetic-test-credential")));
    }

    @Test
    void selectedFieldsRedactNestedCredentialsAndNeverIncludeHttpHeaders() {
        var target =
                Map.of(
                        "payload",
                        Map.of(
                                "Authorization",
                                "Bearer fake",
                                "api_key",
                                "test-key",
                                "safe",
                                "hello"),
                        "headers",
                        Map.of("anything", "hidden-header"),
                        "unselected",
                        "private-extra");
        var captured = bodies().capture(InterventionPoint.PRE_TOOL_CALL, target, target);
        String text = JsonValues.write(captured.content());
        assertTrue(text.contains("hello"));
        for (var secret : List.of("Bearer fake", "test-key", "hidden-header", "private-extra"))
            assertFalse(text.contains(secret));
    }

    @Test
    void textIsOptInAndLimitsOmitWholeBodyRatherThanUnredactedPrefix() {
        var selected =
                new AuditBodyPolicy(
                        Set.of(InterventionPoint.PRE_TOOL_CALL),
                        Set.of("payload"),
                        false,
                        1000,
                        1000);
        assertFalse(
                JsonValues.write(
                                selected.capture(
                                                InterventionPoint.PRE_TOOL_CALL,
                                                Map.of("payload", "private"),
                                                Map.of("payload", "private"))
                                        .content())
                        .contains("private"));
        var small =
                new AuditBodyPolicy(
                        Set.of(InterventionPoint.PRE_TOOL_CALL), Set.of(), true, 5, 1000);
        assertEquals(
                "input_limit",
                small.capture(InterventionPoint.PRE_TOOL_CALL, "long-input", "long-input")
                        .status());
        var bytes =
                new AuditBodyPolicy(
                        Set.of(InterventionPoint.POST_TOOL_CALL), Set.of(), true, 1000, 40);
        assertEquals(
                "storage_limit",
                bytes.capture(InterventionPoint.POST_TOOL_CALL, "多字节正文", "多字节正文").status());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AuditBodyPolicy(
                                Set.of(InterventionPoint.AGENT_STARTUP),
                                Set.of(),
                                true,
                                1000,
                                1000));
    }

    @Test
    void operatorExclusionsAndUnicodeCredentialNamesCannotLeakSelectedSubtree() {
        var policy =
                new AuditBodyPolicy(
                        Set.of(InterventionPoint.PRE_TOOL_CALL),
                        Set.of("payload"),
                        true,
                        10000,
                        4096,
                        Set.of("contactEmail", "业务密钥"));
        var body =
                Map.of(
                        "payload",
                        Map.of(
                                "contactEmail",
                                "synthetic@example.invalid",
                                "业务密钥",
                                "fake-business-secret",
                                "ＡｐｉＫｅｙ",
                                "fullwidth-secret",
                                "visible",
                                "retained"));
        String captured =
                JsonValues.write(
                        policy.capture(InterventionPoint.PRE_TOOL_CALL, body, body).content());
        assertTrue(captured.contains("retained"));
        for (String privateValue :
                List.of("synthetic@example.invalid", "fake-business-secret", "fullwidth-secret"))
            assertFalse(captured.contains(privateValue));
    }

    @Test
    void captureProjectionCapacityOmitsContentWithoutFailingExecution() {
        Object nested = "leaf";
        for (int i = 0; i < 32; i++) nested = Map.of("next", nested);
        var policy =
                new AuditBodyPolicy(
                        Set.of(InterventionPoint.PRE_TOOL_CALL), Set.of("next"), true, 10000, 4096);
        var capture = policy.capture(InterventionPoint.PRE_TOOL_CALL, nested, nested);
        assertEquals("projection_limit", capture.status());
        assertNull(capture.content());
    }

    @Test
    void explicitlyCollectedBodyCannotSilentlyFallBackToLegacyWriter() {
        var writes = new AtomicInteger();
        var executes = new AtomicInteger();
        AuditSink incorrectlyBound =
                new AuditSink() {
                    public AuditBodyPolicy bodyPolicy() {
                        return bodies();
                    }

                    public void write(AuditRecord record) {
                        writes.incrementAndGet();
                    }
                };
        try (var control = AgentControl.builder(policy()).audit(incorrectlyBound).build()) {
            var session = control.openSession(ID);
            assertEquals(
                    "audit_failed",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            session.operation(
                                                    true,
                                                    "read",
                                                    Map.of("payload", "private"),
                                                    value -> {
                                                        executes.incrementAndGet();
                                                        return "result";
                                                    },
                                                    value -> value,
                                                    (a, b) -> (String) b))
                            .code());
            assertThrows(GovernanceException.class, session::checkActive);
            assertEquals(
                    "audit_failed", assertThrows(GovernanceException.class, session::close).code());
        }
        assertEquals(0, executes.get());
        assertTrue(writes.get() > 0);
    }
}
