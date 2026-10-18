package io.agt.delegation;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.delegation.*;
import io.agt.core.policy.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class DelegationTest {
    static KeyPair keys;
    static final TrustedContext SOURCE =
            new TrustedContext("coordinator", "tenant", "user", Set.of("read"));
    static final String ISSUER = "urn:agt:test:issuer", AUDIENCE = "worker-service";
    static final BudgetLimits CALLS = new BudgetLimits(5, 3, 60000);

    @BeforeAll
    static void keyPair() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    static ParameterRule args(String... values) {
        return ParameterRule.object(
                Map.of(
                        "resource",
                        new ParameterRule(
                                ParameterRule.Type.STRING,
                                null,
                                null,
                                new ArrayList<>(List.of(values)),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)),
                "resource");
    }

    static StructuredPolicy policy() {
        return StructuredPolicy.builder("grant-v1")
                .budget(CALLS)
                .tool(
                        "lookup",
                        new ToolRule(Set.of("read"), args("customer-a", "customer-b"), false))
                .tool("slow", new ToolRule(Set.of("read"), ParameterRule.object(Map.of()), false))
                .build();
    }

    static DelegationScope scope() {
        return new DelegationScope(
                "customer-summary",
                false,
                Map.of("lookup", args("customer-a"), "slow", ParameterRule.object(Map.of())),
                Map.of());
    }

    static TaskLimits sourceLimits() {
        return new TaskLimits(CALLS, 1000, 10, 3);
    }

    static TaskLimits receiverLimits() {
        return new TaskLimits(CALLS, 1000, 10, 2);
    }

    static AgentControl control() {
        return AgentControl.builder(policy()).audit(record -> {}).build();
    }

    static DelegationAuthority authority(GrantRegistry registry, Clock clock) {
        return new DelegationAuthority(
                ISSUER, "key-1", (RSAPrivateKey) keys.getPrivate(), registry, clock);
    }

    static DelegationVerifier verifier(GrantStatusClient registry, Clock clock) {
        return new DelegationVerifier(
                ISSUER,
                AUDIENCE,
                Map.of("key-1", (RSAPublicKey) keys.getPublic()),
                registry,
                clock);
    }

    static String lookup(HostSession session, String resource, AtomicInteger effects) {
        return session.operation(
                true,
                "lookup",
                Map.of("resource", resource),
                input -> {
                    effects.incrementAndGet();
                    return "ok";
                },
                value -> value,
                (original, value) -> (String) value);
    }

    @Test
    void signedGrantBindsIdentityTaskScopePolicyAndSingleAdmission() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = control();
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var authority = authority(registry, clock);
                var root = authority.register(source);
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
            assertFalse(token.toString().contains(token.value()));
            assertEquals(
                    "delegation_actor_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> verifier.accept(token, new AuthenticatedAgent("other")))
                            .code());
            try (var permit = verifier.accept(token, new AuthenticatedAgent("reader"));
                    var session = receiver.openAuthorizedSession(permit, receiverLimits(), null)) {
                assertEquals(source.taskSnapshot().taskId(), session.taskSnapshot().taskId());
                assertEquals("reader", session.context().agentId());
                var effects = new AtomicInteger();
                assertEquals("ok", lookup(session, "customer-a", effects));
                assertThrows(
                        GovernanceException.class,
                        () -> verifier.accept(token, new AuthenticatedAgent("reader")));
                assertEquals(
                        "delegation_arguments_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> lookup(session, "customer-b", effects))
                                .code());
                assertEquals(1, effects.get());
            }
        }
    }

    @Test
    void independentReceiversCannotMultiplyTransferredRootCallBudget() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = control();
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var authority = authority(registry, clock);
                var root = authority.register(source);
                var first = verifier(registry, clock);
                var second = verifier(registry, clock);
                var receiverA = control();
                var receiverB = control()) {
            var tokenA =
                    authority.issue(
                            root.lease(),
                            "reader-a",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            var tokenB =
                    authority.issue(
                            root.lease(),
                            "reader-b",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            try (var a =
                            receiverA.openAuthorizedSession(
                                    first.accept(tokenA, new AuthenticatedAgent("reader-a")),
                                    receiverLimits(),
                                    null);
                    var b =
                            receiverB.openAuthorizedSession(
                                    second.accept(tokenB, new AuthenticatedAgent("reader-b")),
                                    receiverLimits(),
                                    null)) {
                var effects = new AtomicInteger();
                lookup(a, "customer-a", effects);
                lookup(b, "customer-a", effects);
                lookup(a, "customer-a", effects);
                assertEquals(
                        "task_tool_budget_exceeded",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> lookup(b, "customer-a", effects))
                                .code());
                assertEquals(3, effects.get());
                assertEquals(
                        "delegation_budget_exported_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> lookup(source, "customer-a", effects))
                                .code());
                assertEquals(3, effects.get());
            }
        }
    }

    @Test
    void rootRevocationStopsAlreadyWaitingReceiverPromise() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = control();
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var authority = authority(registry, clock);
                var root = authority.register(source);
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
            try (var session =
                    receiver.openAuthorizedSession(
                            verifier.accept(token, new AuthenticatedAgent("reader")),
                            receiverLimits(),
                            null)) {
                var entered = new CountDownLatch(1);
                var call =
                        session.operationAsyncPrepared(
                                true,
                                "slow",
                                Map.of(),
                                value -> value,
                                value -> {
                                    entered.countDown();
                                    return new CompletableFuture<String>();
                                },
                                value -> value,
                                (original, value) -> (String) value);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                root.close();
                assertThrows(ExecutionException.class, () -> call.get(3, TimeUnit.SECONDS));
                var effects = new AtomicInteger();
                assertThrows(
                        GovernanceException.class, () -> lookup(session, "customer-a", effects));
                assertEquals(0, effects.get());
            }
        }
    }

    @Test
    void policyMismatchAndObservationModeCannotRelaxSourceAuthorization() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = control();
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var authority = authority(registry, clock);
                var root = authority.register(source);
                var verifier = verifier(registry, clock);
                var observation =
                        AgentControl.builder(policy())
                                .mode(EnforcementMode.EVALUATE_ONLY)
                                .audit(record -> {})
                                .build();
                var receiver =
                        AgentControl.builder(
                                        StructuredPolicy.builder("grant-v1").budget(CALLS).build())
                                .audit(record -> {})
                                .build()) {
            var token =
                    authority.issue(
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
            assertThrows(
                    GovernanceException.class,
                    () ->
                            authority.issue(
                                    root.lease(),
                                    "reader",
                                    AUDIENCE,
                                    Set.of("admin"),
                                    scope(),
                                    Duration.ofSeconds(30)));
            var observationToken =
                    authority.issue(
                            root.lease(),
                            "reader",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            try (var permit = verifier.accept(observationToken, new AuthenticatedAgent("reader"))) {
                assertEquals(
                        "delegation_runtime_policy_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                observation.openAuthorizedSession(
                                                        permit, receiverLimits(), null))
                                .code());
            }
        }
    }

    @Test
    void unboundCredentialCannotDelegateAndCommitIdentityIncludesActionKind() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = control();
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var authority = authority(registry, clock);
                var root = authority.register(source);
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
            try (var permit = verifier.accept(token, new AuthenticatedAgent("reader"))) {
                assertEquals(
                        "delegation_session_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                authority.issue(
                                                        permit.lease(),
                                                        "next",
                                                        AUDIENCE,
                                                        Set.of("read"),
                                                        scope(),
                                                        Duration.ofSeconds(10)))
                                .code());
                try (var session = receiver.openAuthorizedSession(permit, receiverLimits(), null)) {
                    String digest = JsonValues.digest(Map.of("operation", "test"));
                    registry.commit(permit.lease(), session.sessionId(), "action", "tool", digest);
                    registry.commit(permit.lease(), session.sessionId(), "action", "tool", digest);
                    assertEquals(
                            "delegation_action_mismatch_denied",
                            assertThrows(
                                            GovernanceException.class,
                                            () ->
                                                    registry.commit(
                                                            permit.lease(),
                                                            session.sessionId(),
                                                            "action",
                                                            "model",
                                                            digest))
                                    .code());
                    assertNotNull(
                            authority.issue(
                                    permit.lease(),
                                    "next",
                                    AUDIENCE,
                                    Set.of("read"),
                                    scope(),
                                    Duration.ofSeconds(10)));
                }
            }
        }
    }

    @Test
    void remoteProcessesShareRemainingSessionSlotsAndCannotCreateUnregisteredLocalChildren()
            throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = control();
                var source =
                        sender.openTaskSession(
                                SOURCE, new TaskLimits(CALLS, 1000, 2, 3), null, scope());
                var authority = authority(registry, clock);
                var root = authority.register(source);
                var first = verifier(registry, clock);
                var second = verifier(registry, clock);
                var receiver = control()) {
            var a =
                    authority.issue(
                            root.lease(),
                            "reader-a",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            var b =
                    authority.issue(
                            root.lease(),
                            "reader-b",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            try (var session =
                    receiver.openAuthorizedSession(
                            first.accept(a, new AuthenticatedAgent("reader-a")),
                            receiverLimits(),
                            null)) {
                assertEquals(
                        "task_session_limit",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> second.accept(b, new AuthenticatedAgent("reader-b")))
                                .code());
                assertEquals(
                        "delegation_remote_child_requires_grant",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                receiver.openDelegatedSession(
                                                        session,
                                                        new TrustedContext(
                                                                "child",
                                                                "tenant",
                                                                "user",
                                                                Set.of("read"))))
                                .code());
                assertEquals("ok", lookup(session, "customer-a", new AtomicInteger()));
            }
            try (var accepted = second.accept(b, new AuthenticatedAgent("reader-b"))) {
                assertEquals("reader-b", accepted.identity().agentId());
            }
            assertEquals(
                    "delegation_budget_exported_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> sender.openDelegatedSession(source, SOURCE))
                            .code());
        }
    }

    @Test
    void onlyOneConcurrentReceiverCanClaimTheSameCredential() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = control();
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var authority = authority(registry, clock);
                var root = authority.register(source);
                var verifier = verifier(registry, clock)) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            var pool = Executors.newFixedThreadPool(12);
            var gate = new CountDownLatch(1);
            var accepted = new CopyOnWriteArrayList<DelegationVerifier.Permit>();
            try {
                var futures = new ArrayList<Future<?>>();
                for (int i = 0; i < 12; i++)
                    futures.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            gate.await();
                                            accepted.add(
                                                    verifier.accept(
                                                            token,
                                                            new AuthenticatedAgent("reader")));
                                        } catch (GovernanceException denied) {
                                            assertEquals("delegation_replay_denied", denied.code());
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                            throw new RuntimeException(interrupted);
                                        }
                                    }));
                gate.countDown();
                for (var future : futures) future.get(5, TimeUnit.SECONDS);
                assertEquals(1, accepted.size());
            } finally {
                pool.shutdownNow();
                accepted.forEach(DelegationVerifier.Permit::close);
            }
        }
    }

    @Test
    void canonicalGrantRoundTripPreservesBindingsAndOrderedPolicySemantics() throws Exception {
        var first = new LinkedHashMap<String, String>();
        first.put("secret", "masked");
        first.put("masked", "public");
        var reversed = new LinkedHashMap<String, String>();
        reversed.put("masked", "public");
        reversed.put("secret", "masked");
        var a =
                StructuredPolicy.builder("v1")
                        .content(
                                new ContentRule(
                                        "rewrite",
                                        Set.of(InterventionPoint.OUTPUT),
                                        List.of(),
                                        first))
                        .build();
        var b =
                StructuredPolicy.builder("v1")
                        .content(
                                new ContentRule(
                                        "rewrite",
                                        Set.of(InterventionPoint.OUTPUT),
                                        List.of(),
                                        reversed))
                        .build();
        assertNotEquals(
                GrantCodec.policyDigest(a.document()), GrantCodec.policyDigest(b.document()));
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = control();
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var authority = authority(registry, clock);
                var root = authority.register(source)) {
            var doc = registry.parent(root.lease());
            assertEquals(doc.digest(), GrantCodec.decode(GrantCodec.encode(doc)).digest());
        }
    }
}
