package io.agt.identity;

import static io.agt.identity.IdentityFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import io.agt.core.*;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class JwtIdentityVerifierTest {
    @Test
    void mapsOnlyReviewedRolesAndRedactsCredentials() {
        var claims = claims();
        claims.put("permissions", List.of("admin"));
        var token = token(claims);
        try (var verifier = verifier(t -> true, new MutableClock())) {
            var actor = verifier.verify(token);
            var context = actor.context("agent");
            assertEquals(Set.of("read"), context.permissions());
            assertEquals("tenant-a", context.tenantId());
            assertEquals(policy(1000).principal((String) claims.get("sub")), context.principalId());
            assertFalse(context.principalId().contains("subject"));
            actor.requireHuman();
            actor.requirePermission("read");
            assertThrows(GovernanceException.class, () -> actor.requirePermission("admin"));
            assertFalse(token.toString().contains(token.value()));
            assertEquals("AuthenticatedActor[redacted]", actor.toString());
            var other =
                    new IdentityPolicy(
                            "https://other.example.invalid",
                            "agent-api",
                            Set.of("runtime"),
                            Map.of("reader", Set.of("read")),
                            60000,
                            10000,
                            Set.of("mfa-level"),
                            1000);
            assertNotEquals(context.principalId(), other.principal((String) claims.get("sub")));
        }
    }

    @Test
    void rejectsWrongClaimProfilesBeforeOnlineCheck() {
        var checked = new AtomicInteger();
        try (var verifier =
                verifier(
                        t -> {
                            checked.incrementAndGet();
                            return true;
                        },
                        new MutableClock())) {
            for (var change :
                    List.<Map.Entry<String, Object>>of(
                            Map.entry("iss", "https://evil.example.invalid"),
                            Map.entry("aud", List.of("agent-api", "other")),
                            Map.entry("client_id", "evil"),
                            Map.entry("agt_roles", List.of("admin")),
                            Map.entry("agt_roles", List.of("reader", "reader")),
                            Map.entry("agt_actor_kind", "agent"),
                            Map.entry("iat", NOW / 1000 + 1),
                            Map.entry("nbf", NOW / 1000 + 1),
                            Map.entry("exp", NOW / 1000),
                            Map.entry("exp", NOW / 1000 + 61),
                            Map.entry("auth_time", NOW / 1000 + 1))) {
                var claims = claims();
                claims.put(change.getKey(), change.getValue());
                assertEquals(
                        "identity_token_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> verifier.verify(token(claims)))
                                .code(),
                        change.getKey());
            }
            assertEquals(0, checked.get());
        }
    }

    @Test
    void rejectsIdTokensUnknownKeysRemoteKeyUrlsAndForgedSignatures() {
        var raw = JsonValues.write(claims());
        var checked = new AtomicInteger();
        try (var verifier =
                verifier(
                        t -> {
                            checked.incrementAndGet();
                            return true;
                        },
                        new MutableClock())) {
            for (var header :
                    List.of(
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .type(JOSEObjectType.JWT)
                                    .keyID("key-a")
                                    .build(),
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .type(new JOSEObjectType("at+jwt"))
                                    .keyID("unknown")
                                    .build(),
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .type(new JOSEObjectType("at+jwt"))
                                    .keyID("key-a")
                                    .jwkURL(URI.create("https://evil.example.invalid/keys"))
                                    .build()))
                assertThrows(
                        GovernanceException.class, () -> verifier.verify(signed(header, raw, KEY)));
            var normal =
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(new JOSEObjectType("at+jwt"))
                            .keyID("key-a")
                            .build();
            assertThrows(
                    GovernanceException.class, () -> verifier.verify(signed(normal, raw, key())));
            assertThrows(
                    GovernanceException.class,
                    () ->
                            verifier.verify(
                                    signed(
                                            normal,
                                            raw.substring(0, raw.length() - 1)
                                                    + ",\"iss\":\"duplicate\"}",
                                            KEY)));
            assertThrows(GovernanceException.class, () -> new AccessToken("Bearer invalid"));
            assertEquals(0, checked.get());
        }
    }

    @Test
    void humanAssuranceCannotBeClaimedByServiceOrStaleAuthentication() {
        try (var verifier = verifier(t -> true, new MutableClock())) {
            for (var change :
                    List.<Map.Entry<String, Object>>of(
                            Map.entry("agt_actor_kind", "service"),
                            Map.entry("auth_time", NOW / 1000 - 11),
                            Map.entry("amr", List.of("pwd", "otp")),
                            Map.entry("acr", "unreviewed"))) {
                var claims = claims();
                claims.put("agt_roles", List.of("reviewer"));
                claims.put(change.getKey(), change.getValue());
                var actor = verifier.verify(token(claims));
                actor.requirePermission("approval:decide");
                assertEquals(
                        "identity_human_assurance_denied",
                        assertThrows(GovernanceException.class, actor::requireHuman).code());
            }
        }
    }

    @Test
    void clockRollbackAndLaterActiveResponseCannotReviveRefusedActor() {
        var clock = new MutableClock();
        var active = new AtomicBoolean(true);
        try (var verifier = verifier(t -> active.get(), clock)) {
            var actor = verifier.verify(token(claims()));
            clock.time.addAndGet(60000);
            assertEquals(
                    "identity_token_expired",
                    assertThrows(GovernanceException.class, actor::checkActive).code());
            clock.time.set(NOW);
            assertEquals(0, actor.remainingMillis());
            assertThrows(GovernanceException.class, actor::checkActive);
            var second = verifier.verify(token(claims()));
            active.set(false);
            assertThrows(GovernanceException.class, second::checkActive);
            active.set(true);
            assertThrows(GovernanceException.class, second::checkActive);
        }
    }

    @Test
    void lateConcurrentActiveCheckDoesNotUndoAnotherChecksDenial() throws Exception {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var verifier =
                verifier(
                        t -> {
                            int call = calls.incrementAndGet();
                            if (call == 2) {
                                entered.countDown();
                                release.await();
                                return true;
                            }
                            return call == 1;
                        },
                        new MutableClock())) {
            var actor = verifier.verify(token(claims()));
            var worker = Executors.newSingleThreadExecutor();
            try {
                var late =
                        worker.submit(
                                () -> {
                                    actor.checkActive();
                                    return true;
                                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertThrows(GovernanceException.class, actor::checkActive);
                release.countDown();
                assertInstanceOf(
                        GovernanceException.class,
                        assertThrows(ExecutionException.class, () -> late.get(2, TimeUnit.SECONDS))
                                .getCause());
            } finally {
                release.countDown();
                worker.shutdownNow();
            }
        }
    }

    @Test
    void cancelledStatusWaitRejectsCurrentOperationWithoutPoisoningSharedActor() throws Exception {
        var calls = new AtomicInteger();
        var active = new AtomicBoolean(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var error = new AtomicReference<GovernanceException>();
        var interrupted = new AtomicBoolean();
        try (var verifier =
                verifier(
                        t -> {
                            if (calls.incrementAndGet() == 2) {
                                entered.countDown();
                                release.await();
                            }
                            return active.get();
                        },
                        new MutableClock())) {
            var actor = verifier.verify(token(claims()));
            var waiting =
                    new Thread(
                            () -> {
                                try {
                                    actor.checkActive();
                                } catch (GovernanceException denied) {
                                    error.set(denied);
                                    interrupted.set(Thread.currentThread().isInterrupted());
                                }
                            });
            try {
                waiting.start();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                waiting.interrupt();
                waiting.join(2000);
                assertFalse(waiting.isAlive());
                assertNotNull(error.get());
                assertEquals("identity_status_cancelled", error.get().code());
                assertTrue(interrupted.get());
                release.countDown();
                assertEquals(Set.of("read"), actor.context("next-request").permissions());
                assertTrue(calls.get() >= 3, "Reuse requires a new online check");
                active.set(false);
                assertEquals(
                        "identity_token_inactive",
                        assertThrows(GovernanceException.class, actor::checkActive).code());
                active.set(true);
                assertThrows(GovernanceException.class, actor::checkActive);
            } finally {
                release.countDown();
                waiting.interrupt();
                waiting.join(2000);
            }
        }
    }

    @Test
    void blockingStatusAdapterUsesBoundedWorkersAndHasNoOfflineFallback() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var verifier =
                new JwtIdentityVerifier(
                        policy(80),
                        Map.of("key-a", (RSAPublicKey) KEY.getPublic()),
                        t -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            while (release.getCount() > 0) {
                                try {
                                    release.await();
                                } catch (InterruptedException ignored) {
                                }
                            }
                            return true;
                        },
                        new MutableClock(),
                        new IdentityLimits(1, 1, 1, 100, 100))) {
            assertEquals(
                    "identity_status_timeout",
                    assertThrows(GovernanceException.class, () -> verifier.verify(token(claims())))
                            .code());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++)
                assertThrows(GovernanceException.class, () -> verifier.verify(token(claims())));
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
        }
    }
}
