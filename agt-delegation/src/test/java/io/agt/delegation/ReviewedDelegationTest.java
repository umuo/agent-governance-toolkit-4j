package io.agt.delegation;

import static io.agt.delegation.DelegationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.flow.*;
import io.agt.core.policy.*;
import io.agt.core.storage.StorageCrypto;
import io.agt.core.tools.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 远端签名版本映射与权威提交；旧接口不能丢弃版本，正文不进入状态协议。 */
class ReviewedDelegationTest {
    static final byte[] ARTIFACT =
            "reviewed-lookup".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static final Map<String, Object> SPEC =
            Map.of("name", "lookup", "description", "Lookup approved customer");
    static final TrustedContext AUDITOR =
            new TrustedContext("auditor", "tenant", "user", Set.of("governance:inspect"));

    @BeforeAll
    static void setup() throws Exception {
        keyPair();
    }

    static ReviewedToolCatalog catalog(String version) {
        return new ReviewedToolCatalog(
                List.of(
                        new ToolDescriptor(
                                "lookup",
                                "host",
                                version,
                                "sha256:" + StorageCrypto.hash(ARTIFACT),
                                SPEC,
                                Set.of("read"))),
                1024);
    }

    static ToolLease lease(ReviewedToolCatalog catalog) {
        return catalog.register("lookup", ARTIFACT, SPEC);
    }

    static StructuredPolicy policy(ToolLease lease) {
        return StructuredPolicy.builder("reviewed-grant-v1")
                .budget(CALLS)
                .tool(
                        "lookup",
                        new ToolRule(
                                Set.of("read"),
                                args("customer-a", "customer-b"),
                                false,
                                null,
                                lease.identity()))
                .tool("slow", new ToolRule(Set.of("read"), ParameterRule.object(Map.of()), false))
                .build();
    }

    static AgentControl control(ToolLease lease) {
        return AgentControl.builder(policy(lease))
                .reviewedTools(List.of(lease))
                .audit(r -> {})
                .build();
    }

    static DataFlowPolicy flow() {
        return new DataFlowPolicy(
                Map.of(),
                Map.of(),
                Map.of(),
                Set.of("lookup", "slow"),
                DataLabel.PUBLIC,
                DataLabel.PUBLIC,
                DataLabel.PUBLIC);
    }

    static TaskLimits limits(boolean flow, int depth) {
        return new TaskLimits(CALLS, 1000, 10, depth, flow ? flow() : null);
    }

    static String invoke(HostSession session, ToolLease lease, boolean async) throws Exception {
        var input = Map.of("resource", "customer-a");
        return async
                ? session.reviewedToolOperationAsync(
                                lease,
                                input,
                                v -> v,
                                v -> CompletableFuture.completedFuture("ok"),
                                v -> v,
                                (o, v) -> (String) v)
                        .get(5, TimeUnit.SECONDS)
                : session.reviewedToolOperation(
                        lease, input, v -> v, v -> "ok", v -> v, (o, v) -> (String) v);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void httpSdkCommitChecksSignedVersionAndSharedFlow(boolean withFlow) throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        var requests = new CopyOnWriteArrayList<String>();
        try (var senderCatalog = catalog("v1");
                var receiverCatalog = catalog("v1");
                var sender = control(lease(senderCatalog));
                var source = sender.openTaskSession(SOURCE, limits(withFlow, 3), null, scope());
                var issuer = authority(registry, clock);
                var root = issuer.register(source);
                var endpoint = new TestGrantEndpoint(registry, requests::add);
                var verifier =
                        verifier(
                                HttpGrantStatusClient.loopbackForTesting(
                                        endpoint.uri(), () -> endpoint.credential),
                                clock);
                var receiver = control(lease(receiverCatalog))) {
            var token =
                    issuer.issue(
                            root.lease(),
                            "reader",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            try (var session =
                    receiver.openAuthorizedSession(
                            verifier.accept(token, new AuthenticatedAgent("reader")),
                            limits(false, 2),
                            null)) {
                for (boolean async : List.of(false, true))
                    assertEquals("ok", invoke(session, lease(receiverCatalog), async));
                assertEquals(
                        "plain",
                        session.operation(
                                true,
                                "slow",
                                Map.of(),
                                v -> "plain",
                                v -> v,
                                (o, v) -> (String) v));
            }
            assertEquals(3, registry.inspect(root.lease().grantId(), AUDITOR).toolCalls());
            var commits =
                    requests.stream()
                            .map(JsonValues::parseObject)
                            .filter(r -> "reviewed_commit".equals(r.get("op")))
                            .toList();
            assertEquals(3, commits.size());
            assertTrue(
                    commits.stream()
                            .allMatch(
                                    r ->
                                            "slow".equals(r.get("tool"))
                                                    ? r.get("binding") == null
                                                    : lease(senderCatalog)
                                                            .identity()
                                                            .equals(r.get("binding"))));
            for (var r : commits) assertFalse(JsonValues.write(r).contains("customer-a"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authorityRejectsOldPathsWrongBindingsAndChangedActionAcrossRestore(boolean withFlow) {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var catalog = catalog("v1");
                var sender = control(lease(catalog));
                var source = sender.openTaskSession(SOURCE, limits(withFlow, 3), null, scope());
                var issuer = authority(registry, clock);
                var root = issuer.register(source);
                var verifier = verifier(registry, clock)) {
            var token =
                    issuer.issue(
                            root.lease(),
                            "reader",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            try (var permit = verifier.accept(token, new AuthenticatedAgent("reader"))) {
                registry.bind(permit.lease(), "sdk-session");
                String digest = JsonValues.digest("request");
                String binding = lease(catalog).identity();
                Object projection = withFlow ? Map.of() : null;
                assertEquals(
                        "delegation_tool_binding_required",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                registry.commit(
                                                        permit.lease(),
                                                        "sdk-session",
                                                        "action",
                                                        "tool",
                                                        digest))
                                .code());
                if (withFlow)
                    assertEquals(
                            "delegation_tool_binding_required",
                            assertThrows(
                                            GovernanceException.class,
                                            () ->
                                                    registry.guardedCommit(
                                                            permit.lease(),
                                                            "sdk-session",
                                                            "action",
                                                            InterventionPoint.PRE_TOOL_CALL,
                                                            "lookup",
                                                            projection,
                                                            digest))
                                    .code());
                for (String invalid : Arrays.asList(null, JsonValues.digest("wrong")))
                    assertEquals(
                            "delegation_tool_binding_denied",
                            assertThrows(
                                            GovernanceException.class,
                                            () ->
                                                    registry.reviewedCommit(
                                                            permit.lease(),
                                                            "sdk-session",
                                                            "action",
                                                            "lookup",
                                                            invalid,
                                                            projection,
                                                            digest))
                                    .code());
                assertEquals(
                        "delegation_tool_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                registry.reviewedCommit(
                                                        permit.lease(),
                                                        "sdk-session",
                                                        "action",
                                                        "missing",
                                                        null,
                                                        projection,
                                                        digest))
                                .code());
                registry.reviewedCommit(
                        permit.lease(),
                        "sdk-session",
                        "action",
                        "lookup",
                        binding,
                        projection,
                        digest);
                var restored =
                        InMemoryGrantRegistry.restored(
                                registry.checkpoint(root.lease().grantId()), clock, null);
                restored.reviewedCommit(
                        permit.lease(),
                        "sdk-session",
                        "action",
                        "lookup",
                        binding,
                        projection,
                        digest);
                assertEquals(
                        "delegation_action_mismatch_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                restored.reviewedCommit(
                                                        permit.lease(),
                                                        "sdk-session",
                                                        "action",
                                                        "lookup",
                                                        binding,
                                                        projection,
                                                        JsonValues.digest("changed")))
                                .code());
                assertEquals(1, restored.inspect(root.lease().grantId(), AUDITOR).toolCalls());
            }
        }
    }

    @Test
    void legacyStatusImplementationCannotSilentlyDowngradeVersionedDispatch() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        GrantStatusClient legacy =
                new GrantStatusClient() {
                    public GrantDocument claim(
                            String id,
                            String digest,
                            String agent,
                            String audience,
                            GrantLease lease) {
                        return registry.claim(id, digest, agent, audience, lease);
                    }

                    public void bind(GrantLease lease, String session) {
                        registry.bind(lease, session);
                    }

                    public void check(GrantLease lease, String session) {
                        registry.check(lease, session);
                    }

                    public void commit(
                            GrantLease lease,
                            String session,
                            String action,
                            String kind,
                            String digest) {
                        registry.commit(lease, session, action, kind, digest);
                    }

                    public void complete(GrantLease lease, String session) {
                        registry.complete(lease, session);
                    }
                };
        try (var a = catalog("v1");
                var b = catalog("v1");
                var sender = control(lease(a));
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var issuer = authority(registry, clock);
                var root = issuer.register(source);
                var verifier = verifier(legacy, clock);
                var receiver = control(lease(b))) {
            var token =
                    issuer.issue(
                            root.lease(),
                            "reader",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            var calls = new AtomicInteger();
            try (var session =
                    receiver.openAuthorizedSession(
                            verifier.accept(token, new AuthenticatedAgent("reader")),
                            receiverLimits(),
                            null)) {
                assertEquals(
                        "delegation_tool_binding_unsupported",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                session.reviewedToolOperation(
                                                        lease(b),
                                                        Map.of("resource", "customer-a"),
                                                        v -> v,
                                                        v -> calls.incrementAndGet(),
                                                        v -> v,
                                                        (o, v) -> (Integer) v))
                                .code());
            }
            assertEquals(0, calls.get());
            assertEquals(0, registry.inspect(root.lease().grantId(), AUDITOR).toolCalls());
        }
    }

    @Test
    void legacyDocumentKeepsAbsentVersionFieldAndOriginalDigest() {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = DelegationTest.control();
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var issuer = authority(registry, clock);
                var root = issuer.register(source)) {
            var document = registry.parent(root.lease());
            String encoded = GrantCodec.encode(document);
            assertFalse(JsonValues.parseObject(encoded).containsKey("toolBindings"));
            assertEquals(Map.of(), GrantCodec.decode(encoded).toolBindings());
            assertEquals(
                    JsonValues.digest(JsonValues.parse(encoded)),
                    GrantCodec.decode(encoded).digest());
        }
    }

    @Test
    void receiverCannotReplaceReviewedVersionEvenWithMatchingToolName() {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var a = catalog("v1");
                var b = catalog("v2");
                var sender = control(lease(a));
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var issuer = authority(registry, clock);
                var root = issuer.register(source);
                var verifier = verifier(registry, clock);
                var receiver = control(lease(b))) {
            var token =
                    issuer.issue(
                            root.lease(),
                            "reader",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            try (var permit = verifier.accept(token, new AuthenticatedAgent("reader"))) {
                assertEquals(
                        "delegation_runtime_policy_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                receiver.openAuthorizedSession(
                                                        permit, receiverLimits(), null))
                                .code());
            }
            var document = registry.parent(root.lease());
            assertEquals(document, GrantCodec.decode(GrantCodec.encode(document)));
            var changed = new LinkedHashMap<>(JsonValues.parseObject(GrantCodec.encode(document)));
            changed.put("toolBindings", Map.of("lookup", lease(b).identity()));
            assertNotEquals(
                    document.digest(), GrantCodec.decode(JsonValues.write(changed)).digest());
        }
    }
}
