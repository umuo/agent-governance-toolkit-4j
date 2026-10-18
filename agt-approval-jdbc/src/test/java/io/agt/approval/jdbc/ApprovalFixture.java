package io.agt.approval.jdbc;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import io.agt.audit.jdbc.*;
import io.agt.core.*;
import io.agt.core.approval.*;
import io.agt.core.policy.*;
import io.agt.core.storage.*;
import io.agt.identity.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.spec.SecretKeySpec;
import org.h2.jdbcx.JdbcDataSource;

final class ApprovalFixture implements AutoCloseable {
    static final KeyPair RSA = key();
    final MutableClock clock = new MutableClock();
    final Set<String> revoked = ConcurrentHashMap.newKeySet();
    final JdbcDataSource primary = db(), witnessData = db();
    final JdbcAuditWitness witness = new JdbcAuditWitness(witnessData, 2);
    final StorageKeys keys = keys();
    final IdentityPolicy identity =
            new IdentityPolicy(
                    "https://issuer.example.invalid",
                    "approval-api",
                    Set.of("runtime", "review-ui"),
                    Map.of(
                            "requester",
                            Set.of("read", "approval:request"),
                            "reviewer",
                            Set.of("approval:decide")),
                    120000,
                    30000,
                    Set.of("mfa-level"),
                    1000);
    final JwtIdentityVerifier verifier =
            new JwtIdentityVerifier(
                    identity,
                    Map.of("key-a", (RSAPublicKey) RSA.getPublic()),
                    token -> !revoked.contains(token.tokenIdentity()),
                    clock);
    final BudgetLimits budget = new BudgetLimits(5, 5, 60000);
    final StructuredPolicy runtime =
            StructuredPolicy.builder("approval-v1")
                    .budget(budget)
                    .tool(
                            "read",
                            new ToolRule(
                                    Set.of("read"),
                                    ParameterRule.object(
                                            Map.of("value", ParameterRule.string(null)), "value"),
                                    true))
                    .build();
    final ApprovalPolicy policy =
            new ApprovalPolicy(
                    identity.identity(),
                    runtime.document(),
                    Set.of("approval:request"),
                    Map.of("read", Set.of("approval:decide")),
                    100,
                    65536,
                    10000,
                    4,
                    2,
                    50);
    final JdbcApprovalStore store;

    ApprovalFixture() {
        witness.initializeSchema();
        store = store(witness);
        store.initializeSchema();
    }

    JdbcApprovalStore store(AuditWitness anchor) {
        return new JdbcApprovalStore(
                primary, "approval-demo", "reviewed-catalog", policy, keys, anchor, clock, 2);
    }

    AuthenticatedActor actor(String subject, String tenant, String kind, String... roles) {
        return verifier.verify(sign(claims(subject, tenant, kind, roles)));
    }

    Map<String, Object> claims(String subject, String tenant, String kind, String... roles) {
        long now = clock.millis() / 1000;
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", identity.issuer());
        claims.put("aud", identity.audience());
        claims.put("sub", subject);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", now - 1);
        claims.put("exp", now + 60);
        claims.put("client_id", "runtime");
        claims.put("agt_tenant", tenant);
        claims.put("agt_actor_kind", kind);
        claims.put("agt_roles", List.of(roles));
        claims.put("auth_time", now);
        claims.put("acr", "mfa-level");
        claims.put("amr", List.of("mfa"));
        return claims;
    }

    AuthenticatedActor requester() {
        return actor("requester", "tenant-a", "human", "requester");
    }

    AuthenticatedActor reviewer() {
        return actor("reviewer", "tenant-a", "human", "reviewer");
    }

    ApprovalRequest request(AuthenticatedActor requester) {
        return request(requester, Map.of("value", "private-review-body"));
    }

    ApprovalRequest request(AuthenticatedActor requester, Object target) {
        var action =
                new PolicyRequest(
                        InterventionPoint.PRE_TOOL_CALL,
                        requester.context("agent"),
                        "session-a",
                        UUID.randomUUID().toString(),
                        "read",
                        target);
        return new ApprovalRequest(
                UUID.randomUUID().toString(),
                ActionIdentity.of(runtime.version(), action),
                runtime.version(),
                action,
                clock.millis() + 5000);
    }

    static AccessToken sign(Map<String, Object> claims) {
        try {
            var jws =
                    new JWSObject(
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .type(new JOSEObjectType("at+jwt"))
                                    .keyID("key-a")
                                    .build(),
                            new Payload(JsonValues.write(claims)));
            jws.sign(new RSASSASigner((RSAPrivateKey) RSA.getPrivate()));
            return new AccessToken(jws.serialize());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    static KeyPair key() {
        try {
            var g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    static JdbcDataSource db() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000");
        return data;
    }

    static StorageKeys keys() {
        var random = new SecureRandom();
        byte[] aes = new byte[32], mac = new byte[32];
        random.nextBytes(aes);
        random.nextBytes(mac);
        var key =
                new StorageKeys.Version(
                        "storage-key-a",
                        new SecretKeySpec(aes, "AES"),
                        new SecretKeySpec(mac, "HmacSHA256"));
        return new StorageKeys() {
            public Version active() {
                return key;
            }

            public Version find(String id) {
                return key.id().equals(id) ? key : null;
            }
        };
    }

    public void close() {
        verifier.close();
    }

    static final class MutableClock extends Clock {
        final AtomicLong value = new AtomicLong(System.currentTimeMillis());

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public long millis() {
            return value.get();
        }

        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }
    }
}
