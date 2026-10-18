package io.agt.delegation;

import static io.agt.delegation.DelegationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.execution.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ExternalWorkProtocolTest {
    @BeforeAll
    static void keys() throws Exception {
        keyPair();
    }

    @Test
    void authenticatedHttpReaderFetchesAllPagesWithoutTruncationOrLeakingLeaseOwnership()
            throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = control();
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var authority = authority(registry, clock);
                var root = authority.register(source);
                var endpoint = new TestGrantEndpoint(registry);
                var verifier = verifier(registry, clock);
                var receiver = control()) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            try (var permit = verifier.accept(token, new AuthenticatedAgent("reader"));
                    var session = receiver.openAuthorizedSession(permit, receiverLimits(), null)) {
                var client =
                        HttpGrantStatusClient.loopbackForTesting(
                                endpoint.uri(), () -> endpoint.credential);
                for (int i = 0; i < 257; i++) {
                    client.workRegister(
                            permit.lease(), session.sessionId(), "work-" + i, "REMOTE_JOB");
                    client.workFinish(
                            permit.lease(),
                            session.sessionId(),
                            "work-" + i,
                            TerminationEvidence.NOT_STARTED);
                }
                var view =
                        new RemoteExternalLedger(
                                client,
                                permit.lease(),
                                session::sessionId,
                                source.taskSnapshot().taskId());
                var state = view.snapshot();
                assertEquals(257, state.entries().size());
                assertEquals(0, state.active());
                assertTrue(state.allTerminationConfirmed());
                assertEquals(
                        257,
                        new HashSet<>(
                                        state.entries().stream()
                                                .map(
                                                        ExternalExecutionRegistry.EntrySnapshot
                                                                ::executionId)
                                                .toList())
                                .size());
                assertEquals(257, source.externalExecutionSnapshot().entries().size());
                assertEquals(
                        "delegation_lease_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                client.workFinish(
                                                        permit.lease(),
                                                        "wrong-session",
                                                        "work-0",
                                                        TerminationEvidence.CONFIRMED))
                                .code());
            }
        }
    }

    @Test
    void codecRejectsTypeCoercionUnknownFieldsAndNumericEvidence() {
        var summary = new ExternalExecutionState.Summary("task", 0, 0, 0, 0, 0, true, null);
        String valid = ExternalWorkCodec.encode(summary);
        assertEquals(summary, ExternalWorkCodec.summary(valid));
        assertThrows(
                GovernanceException.class,
                () -> ExternalWorkCodec.summary(valid.replace(":true", ":\"true\"")));
        assertThrows(
                GovernanceException.class,
                () ->
                        ExternalWorkCodec.summary(
                                valid.replace("\"revision\":0", "\"revision\":0.5")));
        var extra = new LinkedHashMap<>(JsonValues.parseObject(valid));
        extra.put("body", "untrusted");
        assertThrows(
                GovernanceException.class,
                () -> ExternalWorkCodec.summary(JsonValues.write(extra)));
        var state = new ExternalExecutionState("task", Clock.systemUTC());
        state.register("work", "session", "REMOTE_JOB");
        String page = ExternalWorkCodec.encode(state.page(state.summary().revision(), 0));
        assertThrows(
                GovernanceException.class,
                () -> ExternalWorkCodec.page(page.replace("\"evidence\":null", "\"evidence\":1")));
        assertThrows(
                GovernanceException.class,
                () ->
                        ExternalWorkCodec.page(
                                page.replace(
                                        "\"cancellationRequested\":false",
                                        "\"cancellationRequested\":\"false\"")));
    }
}
