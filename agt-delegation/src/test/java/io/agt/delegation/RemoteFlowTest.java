package io.agt.delegation;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.data.*;
import io.agt.core.delegation.*;
import io.agt.core.flow.*;
import io.agt.core.policy.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class RemoteFlowTest {
    static final BudgetLimits CALLS = new BudgetLimits(10, 20, 60000);
    static final TrustedContext ID =
            new TrustedContext("coordinator", "tenant", "user", Set.of("data", "send"));
    static final DataResource SECRET =
            new DataResource(
                    "records", DataResource.Kind.RETRIEVAL, "tenant", "user", "secret-partition");
    static final DataResource MEMORY =
            new DataResource("memory", DataResource.Kind.MEMORY, "tenant", "user", "stable-memory");

    @BeforeAll
    static void keys() throws Exception {
        DelegationTest.keyPair();
    }

    static ParameterRule sendArgs() {
        return ParameterRule.object(
                Map.of(
                        "destination",
                        ParameterRule.string(null),
                        "body",
                        ParameterRule.string(null)),
                "destination",
                "body");
    }

    static StructuredPolicy policy() {
        var builder =
                StructuredPolicy.builder("remote-flow")
                        .budget(CALLS)
                        .resource(
                                "records",
                                new DataRule(
                                        DataResource.Kind.RETRIEVAL,
                                        DataRule.Scope.PRINCIPAL,
                                        Map.of(DataOperation.RETRIEVE, Set.of("data"))))
                        .resource(
                                "memory",
                                new DataRule(
                                        DataResource.Kind.MEMORY,
                                        DataRule.Scope.PRINCIPAL,
                                        Map.of(
                                                DataOperation.MEMORY_READ,
                                                Set.of("data"),
                                                DataOperation.MEMORY_ADD,
                                                Set.of("data"))))
                        .tool("send", new ToolRule(Set.of("send"), sendArgs(), false));
        for (String tool : List.of("review", "fetch", "unknownAlias"))
            builder.tool(tool, new ToolRule(Set.of("data"), ParameterRule.object(Map.of()), false));
        return builder.build();
    }

    static DelegationScope scope() {
        return new DelegationScope(
                "remote-flow",
                true,
                Map.of(
                        "send",
                        sendArgs(),
                        "review",
                        ParameterRule.object(Map.of()),
                        "fetch",
                        ParameterRule.object(Map.of()),
                        "unknownAlias",
                        ParameterRule.object(Map.of())),
                Map.of(
                        SECRET,
                        Set.of(DataOperation.RETRIEVE),
                        MEMORY,
                        Set.of(DataOperation.MEMORY_READ, DataOperation.MEMORY_ADD)));
    }

    static DataFlowPolicy flow(boolean review) {
        return new DataFlowPolicy(
                Map.of(SECRET, DataLabel.CONFIDENTIAL, MEMORY, DataLabel.CONFIDENTIAL),
                Map.of("fetch", DataLabel.CONFIDENTIAL),
                Map.of(
                        "send",
                        new DataFlowPolicy.SinkRule(
                                DataLabel.SECRET,
                                "destination",
                                Map.of("public", DataLabel.PUBLIC, "internal", DataLabel.SECRET),
                                review ? Set.of("review") : Set.of())),
                Set.of("review"),
                DataLabel.PUBLIC,
                DataLabel.PUBLIC,
                DataLabel.SECRET);
    }

    static TaskLimits receiverLimits() {
        return new TaskLimits(CALLS, 20, 3, 2);
    }

    static String tool(HostSession session, String name, Object input, AtomicInteger effects) {
        return session.operation(
                true,
                name,
                input,
                value -> {
                    effects.incrementAndGet();
                    return "ok";
                },
                value -> value,
                (original, value) -> (String) value);
    }

    static String read(HostSession session, String query, String response) {
        return session.dataOperationPrepared(
                SECRET,
                DataOperation.RETRIEVE,
                query,
                value -> value,
                value -> response,
                value -> value,
                (original, value) -> (String) value);
    }

    static final class Fixture implements AutoCloseable {
        final Clock clock = Clock.systemUTC();
        final InMemoryGrantRegistry registry = new InMemoryGrantRegistry(clock);
        final AgentControl sourceControl =
                AgentControl.builder(policy()).audit(record -> {}).build();
        final HostSession source;
        final DelegationAuthority authority;
        final DelegationAuthority.Root root;
        final List<String> requestBodies = new CopyOnWriteArrayList<>();
        final TestGrantEndpoint endpoint;
        final HttpGrantStatusClient client;
        final DelegationVerifier verifier;
        final List<HostSession> sessions = new ArrayList<>();
        final List<AgentControl> controls = new ArrayList<>();

        Fixture(boolean review, boolean usage) throws Exception {
            source =
                    sourceControl.openTaskSession(
                            ID,
                            new TaskLimits(
                                    CALLS,
                                    20,
                                    10,
                                    3,
                                    flow(review),
                                    usage ? RemoteUsageTest.limits(1000, "1") : null),
                            null,
                            scope());
            authority = DelegationTest.authority(registry, clock);
            root = authority.register(source);
            endpoint = new TestGrantEndpoint(registry, requestBodies::add);
            client =
                    HttpGrantStatusClient.loopbackForTesting(
                            endpoint.uri(), () -> endpoint.credential);
            verifier = DelegationTest.verifier(client, clock);
        }

        DelegationVerifier.Permit permit() {
            return verifier.accept(
                    authority.issue(
                            root.lease(),
                            "reader",
                            DelegationTest.AUDIENCE,
                            ID.permissions(),
                            scope(),
                            Duration.ofSeconds(30)),
                    new AuthenticatedAgent("reader"));
        }

        HostSession receiver() {
            return receiver(AgentControl.builder(policy()).audit(record -> {}).build());
        }

        HostSession receiver(AgentControl control) {
            controls.add(control);
            var session = control.openAuthorizedSession(permit(), receiverLimits(), null);
            sessions.add(session);
            return session;
        }

        public void close() {
            sessions.forEach(HostSession::close);
            verifier.close();
            root.close();
            authority.close();
            source.close();
            controls.forEach(AgentControl::close);
            sourceControl.close();
            endpoint.close();
        }
    }

    @Test
    void readInOneReceiverBlocksSiblingExternalSendAndBodiesDoNotReachAuthority() throws Exception {
        try (var f = new Fixture(false, false)) {
            var a = f.receiver();
            var b = f.receiver();
            var effects = new AtomicInteger();
            assertEquals(
                    "ok",
                    tool(
                            b,
                            "send",
                            Map.of("destination", "public", "body", "public-body"),
                            effects));
            assertEquals(
                    "private-result-needle",
                    read(a, "private-query-needle", "private-result-needle"));
            assertEquals(DataLabel.CONFIDENTIAL, f.root.flowSnapshot().label());
            assertEquals(f.root.flowSnapshot(), b.dataFlowSnapshot());
            assertEquals(
                    "ok",
                    tool(
                            a,
                            "send",
                            Map.of("destination", "internal", "body", "private-body-needle"),
                            effects));
            assertEquals(
                    "private-output-needle",
                    a.disclose(
                            "private-output-needle",
                            value -> value,
                            (original, value) -> (String) value));
            assertEquals(
                    "data_flow_egress_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            tool(
                                                    b,
                                                    "send",
                                                    Map.of(
                                                            "destination",
                                                            "public",
                                                            "body",
                                                            "public-body"),
                                                    effects))
                            .code());
            assertEquals(2, effects.get());
            assertEquals(
                    "data_flow_destination_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            tool(
                                                    a,
                                                    "send",
                                                    Map.of(
                                                            "destination",
                                                            "private-destination-needle",
                                                            "body",
                                                            "fixture"),
                                                    effects))
                            .code());
            assertFalse(
                    f.requestBodies.stream()
                            .anyMatch(
                                    body ->
                                            body.contains("private-query-needle")
                                                    || body.contains("private-result-needle")
                                                    || body.contains("private-body-needle")
                                                    || body.contains("private-output-needle")
                                                    || body.contains(
                                                            "private-destination-needle")));
        }
    }

    @Test
    void sensitiveReadDuringPreparationIsRecheckedBeforeExternalCommit() throws Exception {
        try (var f = new Fixture(false, false)) {
            var sender = f.receiver();
            var reader = f.receiver();
            var effects = new AtomicInteger();
            assertEquals(
                    "data_flow_egress_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            sender.operationPrepared(
                                                    true,
                                                    "send",
                                                    Map.of(
                                                            "destination",
                                                            "public",
                                                            "body",
                                                            "prepared-public"),
                                                    value -> {
                                                        read(reader, "query", "fixture-sensitive");
                                                        return value;
                                                    },
                                                    value -> {
                                                        effects.incrementAndGet();
                                                        return "sent";
                                                    },
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
            assertEquals(0, effects.get());
        }
    }

    @Test
    void completedStepsRequireAnAdmittedSuccessfulAndAuditedTool() throws Exception {
        try (var f = new Fixture(true, false)) {
            var permit = f.permit();
            try (permit;
                    var control = AgentControl.builder(policy()).audit(record -> {}).build();
                    var session = control.openAuthorizedSession(permit, receiverLimits(), null)) {
                assertEquals(
                        "delegation_completion_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                f.client.flowComplete(
                                                        permit.lease(),
                                                        session.sessionId(),
                                                        "invented",
                                                        "review"))
                                .code());
            }
            var failedReview = f.receiver();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            failedReview.operation(
                                    true,
                                    "review",
                                    Map.of(),
                                    value -> {
                                        throw new IllegalStateException("fixture-review-failed");
                                    },
                                    value -> value,
                                    (original, value) -> value));
            assertTrue(f.root.flowSnapshot().completedTools().isEmpty());
            var sender = f.receiver();
            var effects = new AtomicInteger();
            assertEquals(
                    "data_flow_prerequisite_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            tool(
                                                    sender,
                                                    "send",
                                                    Map.of(
                                                            "destination",
                                                            "public",
                                                            "body",
                                                            "fixture"),
                                                    effects))
                            .code());
            var successful = f.receiver();
            tool(successful, "review", Map.of(), new AtomicInteger());
            assertEquals(Set.of("review"), f.root.flowSnapshot().completedTools());
            assertEquals(
                    "ok",
                    tool(
                            f.receiver(),
                            "send",
                            Map.of("destination", "public", "body", "fixture"),
                            effects));
            assertEquals(1, effects.get());
        }
    }

    @Test
    void auditFailureDoesNotRegisterACompletedPrerequisite() throws Exception {
        try (var f = new Fixture(true, false)) {
            var reviewer =
                    f.receiver(
                            AgentControl.builder(policy())
                                    .audit(
                                            record -> {
                                                if (record.point()
                                                                == InterventionPoint.POST_TOOL_CALL
                                                        && "EXECUTION".equals(record.verdict()))
                                                    throw new GovernanceException(
                                                            "fixture_audit_unavailable");
                                            })
                                    .build());
            assertThrows(
                    GovernanceException.class,
                    () -> tool(reviewer, "review", Map.of(), new AtomicInteger()));
            assertTrue(f.root.flowSnapshot().completedTools().isEmpty());
            assertEquals(
                    "audit_failed",
                    assertThrows(GovernanceException.class, reviewer::close).code());
        }
    }

    @Test
    void flowDenialAfterRemoteUsageReservationRefundsBeforeDispatch() throws Exception {
        try (var f = new Fixture(false, true)) {
            var model = f.receiver();
            var reader = f.receiver();
            var effects = new AtomicInteger();
            assertEquals(
                    "data_flow_model_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            model.operationPrepared(
                                                    false,
                                                    null,
                                                    Map.of("model", "fixture"),
                                                    value -> {
                                                        var prepared =
                                                                new RemoteUsageTest.Input(
                                                                        model.reserveModelUsage(
                                                                                "fixture", 100,
                                                                                50));
                                                        tool(
                                                                reader,
                                                                "fetch",
                                                                Map.of(),
                                                                new AtomicInteger());
                                                        return prepared;
                                                    },
                                                    value -> {
                                                        effects.incrementAndGet();
                                                        return "generated";
                                                    },
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
            assertEquals(0, effects.get());
            assertEquals(0, f.root.usageSnapshot().heldTokens());
            assertEquals(0, f.root.usageSnapshot().uncertainCalls());
            assertNull(f.root.usageSnapshot().fault());
        }
    }

    @Test
    void legacyAdmissionAndIndependentLocalFlowCannotBypassTheSharedState() throws Exception {
        try (var f = new Fixture(false, false)) {
            try (var permit = f.permit();
                    var control = AgentControl.builder(policy()).audit(record -> {}).build();
                    var session = control.openAuthorizedSession(permit, receiverLimits(), null)) {
                assertEquals(
                        "delegation_flow_commit_required",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                f.client.commit(
                                                        permit.lease(),
                                                        session.sessionId(),
                                                        "legacy",
                                                        "tool",
                                                        JsonValues.digest("fixture")))
                                .code());
                assertEquals(
                        "data_flow_tool_unclassified_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                tool(
                                                        session,
                                                        "unknownAlias",
                                                        Map.of(),
                                                        new AtomicInteger()))
                                .code());
            }
            try (var permit = f.permit();
                    var control = AgentControl.builder(policy()).audit(record -> {}).build()) {
                assertEquals(
                        "data_flow_local_override_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                control.openAuthorizedSession(
                                                        permit,
                                                        new TaskLimits(
                                                                CALLS, 20, 3, 2, flow(false)),
                                                        null))
                                .code());
            }
        }
    }

    @Test
    void replayedAdmissionCannotReuseAnEarlierLowerLabelOrChangeItsDestination() throws Exception {
        try (var f = new Fixture(false, false)) {
            try (var permit = f.permit();
                    var control = AgentControl.builder(policy()).audit(record -> {}).build();
                    var session = control.openAuthorizedSession(permit, receiverLimits(), null)) {
                String digest = JsonValues.digest("full-effective-request");
                f.client.guardedCommit(
                        permit.lease(),
                        session.sessionId(),
                        "send",
                        InterventionPoint.PRE_TOOL_CALL,
                        "send",
                        Map.of("destination", "public"),
                        digest);
                f.client.guardedCommit(
                        permit.lease(),
                        session.sessionId(),
                        "send",
                        InterventionPoint.PRE_TOOL_CALL,
                        "send",
                        Map.of("destination", "public"),
                        digest);
                assertThrows(
                        GovernanceException.class,
                        () ->
                                f.client.guardedCommit(
                                        permit.lease(),
                                        session.sessionId(),
                                        "send",
                                        InterventionPoint.PRE_TOOL_CALL,
                                        "send",
                                        Map.of("destination", "internal"),
                                        digest));
                read(f.receiver(), "query", "fixture-sensitive");
                assertEquals(
                        "data_flow_state_changed_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                f.client.guardedCommit(
                                                        permit.lease(),
                                                        session.sessionId(),
                                                        "send",
                                                        InterventionPoint.PRE_TOOL_CALL,
                                                        "send",
                                                        Map.of("destination", "public"),
                                                        digest))
                                .code());
            }
        }
    }

    @Test
    void receiverCannotReplaceTheAuthorityCatalogWithAnIndependentLabelStore() throws Exception {
        try (var f = new Fixture(false, false);
                var permit = f.permit()) {
            LabelStore local =
                    new LabelStore() {
                        public Optional<StoredLabel> read(DataResource resource) {
                            throw new AssertionError("Independent catalog used");
                        }

                        public StoredLabel record(
                                DataResource resource,
                                DataLabel label,
                                LabelProvenance provenance) {
                            throw new AssertionError("Independent catalog used");
                        }

                        public Optional<LabelHistory> history(
                                DataResource resource, long revision) {
                            throw new AssertionError("Independent catalog used");
                        }
                    };
            try (var control =
                    AgentControl.builder(policy()).labelStore(local).audit(record -> {}).build()) {
                assertEquals(
                        "data_flow_label_store_override_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                control.openAuthorizedSession(
                                                        permit, receiverLimits(), null))
                                .code());
            }
        }
    }
}
