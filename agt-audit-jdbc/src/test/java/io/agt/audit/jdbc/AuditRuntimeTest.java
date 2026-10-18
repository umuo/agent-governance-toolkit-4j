package io.agt.audit.jdbc;

import static io.agt.audit.jdbc.JdbcAuditVaultTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.policy.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class AuditRuntimeTest {
    static final TrustedContext ID = new TrustedContext("agent", "tenant", "user", Set.of("read"));

    static StructuredPolicy policy() {
        return StructuredPolicy.builder("audit-runtime-v1")
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
    void actualToolOnlyRunsAfterAuditAndWitnessAreCommittedAndScopeIsHostBound() throws Exception {
        var f = new Fixture();
        var invoked = new AtomicInteger();
        try (var control = AgentControl.builder(policy()).audit(f.vault).build();
                var session = control.openSession(ID)) {
            assertEquals(
                    "result",
                    session.operation(
                            true,
                            "read",
                            Map.of("payload", "hello-private-body"),
                            value -> {
                                var rows = f.vault.readMetadata(READER, 0, 10);
                                assertTrue(
                                        rows.stream()
                                                .anyMatch(
                                                        r ->
                                                                JsonValues.write(r.metadata())
                                                                        .contains(
                                                                                "pre_tool_call")));
                                invoked.incrementAndGet();
                                return "result";
                            },
                            value -> value,
                            (a, b) -> (String) b));
        }
        assertEquals(1, invoked.get());
        var records = f.reopen(f.witness).readMetadata(READER, 0, 100);
        var before =
                records.stream()
                        .filter(
                                r ->
                                        r.kind().equals("SDK_EVENT")
                                                && JsonValues.write(r.metadata())
                                                        .contains("pre_tool_call"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                JsonValues.write(f.vault.readBody(ADMIN, before.sequence()))
                        .contains("hello-private-body"));
        assertFalse(JsonValues.write(before.metadata()).contains("hello-private-body"));
    }

    @Test
    void unconfirmedPreAuditFailsWholeRuntimeBeforeToolMutation() throws Exception {
        var f = new Fixture();
        var mutations = new AtomicInteger();
        AuditWitness stopAtTool =
                new AuditWitness() {
                    public AuditCheckpoint read(String c, String s) {
                        return f.witness.read(c, s);
                    }

                    public boolean advance(AuditCheckpoint before, AuditCheckpoint after) {
                        if (after.sequence() >= 2)
                            throw new GovernanceException("audit_witness_unavailable");
                        return f.witness.advance(before, after);
                    }
                };
        try (var control = AgentControl.builder(policy()).audit(f.reopen(stopAtTool)).build()) {
            var session = control.openSession(ID);
            denied(
                    "audit_failed",
                    () ->
                            session.operation(
                                    true,
                                    "read",
                                    Map.of("payload", "private"),
                                    value -> {
                                        mutations.incrementAndGet();
                                        return "result";
                                    },
                                    value -> value,
                                    (a, b) -> (String) b));
            denied("audit_failed", () -> control.openSession(ID));
            assertThrows(GovernanceException.class, session::close);
        }
        assertEquals(0, mutations.get());
        assertEquals(2, f.entries());
        assertEquals(2, f.reopen(f.witness).verify(READER, null).sequence());
    }

    @Test
    void decryptedBodyIsNotDeliveredWhenItsAccessAuditCannotBeWitnessed() throws Exception {
        var f = new Fixture();
        f.vault.append(f.event("event-1"));
        AuditWitness stopAtRead =
                new AuditWitness() {
                    public AuditCheckpoint read(String c, String s) {
                        return f.witness.read(c, s);
                    }

                    public boolean advance(AuditCheckpoint before, AuditCheckpoint after) {
                        if (after.sequence() > 1)
                            throw new GovernanceException("audit_witness_unavailable");
                        return f.witness.advance(before, after);
                    }
                };
        denied("audit_witness_unavailable", () -> f.reopen(stopAtRead).readBody(ADMIN, 1));
        assertEquals(2, f.entries());
        assertTrue(JsonValues.write(f.vault.readBody(ADMIN, 1)).contains("hello-private-body"));
        assertEquals(3, f.entries());
    }
}
