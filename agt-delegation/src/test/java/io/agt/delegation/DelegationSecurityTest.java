package io.agt.delegation;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.delegation.DelegationScope;
import io.agt.core.flow.*;
import io.agt.core.policy.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

class DelegationSecurityTest {
    @BeforeAll
    static void keys() throws Exception {
        DelegationTest.keyPair();
    }

    private static String text(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static DelegationToken signed(String header, String claims) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String input =
                encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))
                        + "."
                        + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(DelegationTest.keys.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return new DelegationToken(input + "." + encoder.encodeToString(signer.sign()));
    }

    @Test
    void signedButConfusedHeadersOrDuplicateClaimsNeverConsumeTheCredential() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = DelegationTest.control();
                var source =
                        sender.openTaskSession(
                                DelegationTest.SOURCE,
                                DelegationTest.sourceLimits(),
                                null,
                                DelegationTest.scope());
                var authority = DelegationTest.authority(registry, clock);
                var root = authority.register(source);
                var verifier = DelegationTest.verifier(registry, clock)) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            DelegationTest.AUDIENCE,
                            Set.of("read"),
                            DelegationTest.scope(),
                            Duration.ofSeconds(30));
            var parts = token.value().split("\\.");
            String header = text(parts[0]), claims = text(parts[1]);
            var invalid = new ArrayList<DelegationToken>();
            for (String changed :
                    List.of(
                            header.replace("RS256", "HS256"),
                            header.replace("key-1", "unknown"),
                            header.replace("agt-delegation+jwt", "JWT"),
                            header.replace("}", ",\"jku\":\"https://untrusted.invalid/key\"}"),
                            header.replace("}", ",\"crit\":[\"x\"],\"x\":true}"),
                            header.replace("}", ",\"kid\":\"key-1\"}")))
                invalid.add(signed(changed, claims));
            invalid.add(signed(header, claims.replace("}", ",\"iss\":\"duplicate\"}")));
            invalid.add(
                    new DelegationToken(
                            parts[0] + "." + parts[1] + "." + "A".repeat(parts[2].length())));
            for (var candidate : invalid)
                assertEquals(
                        "delegation_token_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                verifier.accept(
                                                        candidate,
                                                        new AuthenticatedAgent("reader")))
                                .code());
            try (var accepted = verifier.accept(token, new AuthenticatedAgent("reader"))) {
                assertEquals("reader", accepted.identity().agentId());
            }
        }
    }

    @Test
    void wrongAudienceIssuerKeyAndExpiredOrFutureTokenAreDenied() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = DelegationTest.control();
                var source =
                        sender.openTaskSession(
                                DelegationTest.SOURCE,
                                DelegationTest.sourceLimits(),
                                null,
                                DelegationTest.scope());
                var authority = DelegationTest.authority(registry, clock);
                var root = authority.register(source)) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            DelegationTest.AUDIENCE,
                            Set.of("read"),
                            DelegationTest.scope(),
                            Duration.ofSeconds(30));
            var keys = Map.of("key-1", (RSAPublicKey) DelegationTest.keys.getPublic());
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var otherKeys = Map.of("key-1", (RSAPublicKey) generator.generateKeyPair().getPublic());
            assertThrows(
                    GovernanceException.class,
                    () ->
                            DelegationJwt.verify(
                                    token, "wrong", DelegationTest.AUDIENCE, keys, clock));
            assertThrows(
                    GovernanceException.class,
                    () -> DelegationJwt.verify(token, DelegationTest.ISSUER, "wrong", keys, clock));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            DelegationJwt.verify(
                                    token,
                                    DelegationTest.ISSUER,
                                    DelegationTest.AUDIENCE,
                                    otherKeys,
                                    clock));
            for (long offset : List.of(-5000L, 31000L))
                assertThrows(
                        GovernanceException.class,
                        () ->
                                DelegationJwt.verify(
                                        token,
                                        DelegationTest.ISSUER,
                                        DelegationTest.AUDIENCE,
                                        keys,
                                        Clock.offset(clock, Duration.ofMillis(offset))));
        }
    }

    @Test
    void expiredHeartbeatCannotBeRevivedEvenWithValidSignatureAndLeaseSecret() throws Exception {
        var clock = new MutableClock();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = DelegationTest.control();
                var source =
                        sender.openTaskSession(
                                DelegationTest.SOURCE,
                                DelegationTest.sourceLimits(),
                                null,
                                DelegationTest.scope());
                var authority = DelegationTest.authority(registry, clock);
                var root = authority.register(source)) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            DelegationTest.AUDIENCE,
                            Set.of("read"),
                            DelegationTest.scope(),
                            Duration.ofSeconds(30));
            var claims =
                    DelegationJwt.verify(
                            token,
                            DelegationTest.ISSUER,
                            DelegationTest.AUDIENCE,
                            Map.of("key-1", (RSAPublicKey) DelegationTest.keys.getPublic()),
                            clock);
            var lease = LeaseSecrets.create(claims.grantId());
            registry.claim(
                    claims.grantId(), claims.digest(), "reader", DelegationTest.AUDIENCE, lease);
            registry.bind(lease, "session");
            clock.millis.addAndGet(2500);
            assertEquals(
                    "delegation_inactive_denied",
                    assertThrows(GovernanceException.class, () -> registry.check(lease, "session"))
                            .code());
            assertThrows(GovernanceException.class, () -> registry.heartbeatRoot(root.lease()));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            registry.commit(
                                    lease,
                                    "session",
                                    "new-action",
                                    "tool",
                                    JsonValues.digest("payload")));
            var restarted = new InMemoryGrantRegistry(clock);
            assertEquals(
                    "delegation_unknown_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            restarted.claim(
                                                    claims.grantId(),
                                                    claims.digest(),
                                                    "reader",
                                                    DelegationTest.AUDIENCE,
                                                    lease))
                            .code());
        }
    }

    @Test
    void originalFlowStateAndOutputClearanceSurviveExport() throws Exception {
        var flow =
                new DataFlowPolicy(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of("lookup", "slow"),
                        DataLabel.CONFIDENTIAL,
                        DataLabel.PUBLIC,
                        DataLabel.PUBLIC);
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = DelegationTest.control();
                var source =
                        sender.openTaskSession(
                                DelegationTest.SOURCE,
                                new TaskLimits(DelegationTest.CALLS, 1000, 10, 3, flow),
                                null,
                                DelegationTest.scope());
                var authority = DelegationTest.authority(registry, clock);
                var root = authority.register(source);
                var verifier = DelegationTest.verifier(registry, clock);
                var receiver = DelegationTest.control()) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            DelegationTest.AUDIENCE,
                            Set.of("read"),
                            DelegationTest.scope(),
                            Duration.ofSeconds(30));
            try (var permit = verifier.accept(token, new AuthenticatedAgent("reader"));
                    var session =
                            receiver.openAuthorizedSession(
                                    permit, DelegationTest.receiverLimits(), null)) {
                assertEquals(DataLabel.CONFIDENTIAL, session.dataFlowSnapshot().label());
                assertEquals(root.flowSnapshot(), session.dataFlowSnapshot());
                assertEquals(
                        "ok",
                        DelegationTest.lookup(
                                session,
                                "customer-a",
                                new java.util.concurrent.atomic.AtomicInteger()));
                assertEquals(
                        "data_flow_output_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                session.disclose(
                                                        "hidden",
                                                        value -> value,
                                                        (original, value) -> (String) value))
                                .code());
            }
        }
    }

    @Test
    void originalPolicyIdentityBindingsAreNotReboundToTheReceiverAgent() throws Exception {
        var policy =
                StructuredPolicy.builder("identity-bound")
                        .budget(DelegationTest.CALLS)
                        .tool(
                                "lookup",
                                new ToolRule(
                                        Set.of("read"),
                                        ParameterRule.object(
                                                Map.of("resource", ParameterRule.string("agentId")),
                                                "resource"),
                                        false))
                        .build();
        var scope =
                new DelegationScope(
                        "customer-summary",
                        false,
                        Map.of("lookup", DelegationTest.args("coordinator", "reader")),
                        Map.of());
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = AgentControl.builder(policy).audit(record -> {}).build();
                var source =
                        sender.openTaskSession(
                                DelegationTest.SOURCE, DelegationTest.sourceLimits(), null, scope);
                var authority = DelegationTest.authority(registry, clock);
                var root = authority.register(source);
                var verifier = DelegationTest.verifier(registry, clock);
                var receiver = AgentControl.builder(policy).audit(record -> {}).build()) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            DelegationTest.AUDIENCE,
                            Set.of("read"),
                            scope,
                            Duration.ofSeconds(30));
            try (var session =
                    receiver.openAuthorizedSession(
                            verifier.accept(token, new AuthenticatedAgent("reader")),
                            DelegationTest.receiverLimits(),
                            null)) {
                var effects = new java.util.concurrent.atomic.AtomicInteger();
                assertEquals(
                        "context_binding_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> DelegationTest.lookup(session, "reader", effects))
                                .code());
                assertEquals(0, effects.get());
            }
        }
    }

    private static final class MutableClock extends Clock {
        final AtomicLong millis = new AtomicLong(System.currentTimeMillis());

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public long millis() {
            return millis.get();
        }

        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }
    }
}
