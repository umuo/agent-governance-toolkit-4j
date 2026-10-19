package io.agt.tools.jdbc;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import io.agt.audit.jdbc.*;
import io.agt.core.*;
import io.agt.core.storage.*;
import io.agt.core.tools.*;
import io.agt.identity.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.spec.SecretKeySpec;
import org.h2.jdbcx.JdbcDataSource;

/** 真实 JWT、两份独立逻辑库及固定测试密钥；不依赖开发者凭据。 */
final class ReleaseFixture implements AutoCloseable {
    static final KeyPair RSA = key();
    static final byte[] ARTIFACT =
            "release-test-artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final MutableClock clock = new MutableClock();
    final JdbcDataSource primary = data(), anchors = data();
    final JdbcAuditWitness witness = new JdbcAuditWitness(anchors, 2);
    final StorageKeys keys = keys();
    final Set<String> revoked = ConcurrentHashMap.newKeySet();
    final IdentityPolicy identity;
    final JwtIdentityVerifier verifier;
    final ToolReleasePolicy policy;
    final JdbcToolReleaseStore store;

    ReleaseFixture() {
        this(16, 4, 262144, 5000);
    }

    ReleaseFixture(int releases, int reviews, int bytes, int timeout) {
        this(releases, reviews, bytes, timeout, 3600000);
    }

    ReleaseFixture(int releases, int reviews, int bytes, int timeout, long humanAge) {
        identity =
                new IdentityPolicy(
                        "https://identity.example.invalid",
                        "release-api",
                        Set.of("release-ui"),
                        Map.of(
                                "publisher",
                                Set.of("tools:publish"),
                                "reviewer",
                                Set.of("tools:review"),
                                "revoker",
                                Set.of("tools:revoke")),
                        3600000,
                        humanAge,
                        Set.of("human-mfa"),
                        1000);
        verifier =
                new JwtIdentityVerifier(
                        identity,
                        Map.of("key", (RSAPublicKey) RSA.getPublic()),
                        t -> !revoked.contains(t.tokenIdentity()),
                        clock);

        policy =
                new ToolReleasePolicy(
                        "tenant",
                        identity.identity(),
                        Set.of("tools:publish"),
                        Set.of("tools:review"),
                        Set.of("tools:revoke"),
                        releases,
                        reviews,
                        bytes,
                        timeout);
        witness.initializeSchema();
        store = store(witness);
        store.initializeSchema();
    }

    JdbcToolReleaseStore store(AuditWitness anchor) {
        return new JdbcToolReleaseStore(
                primary, "tool-releases", "independent-tools", policy, keys, anchor, clock);
    }

    ToolDescriptor descriptor(String version) {
        return descriptor(store, version, "approved lookup");
    }

    ToolDescriptor descriptor(JdbcToolReleaseStore catalog, String version, String description) {
        return new ToolDescriptor(
                "lookup",
                "host-provider",
                version,
                "sha256:" + StorageCrypto.hash(ARTIFACT),
                Map.of("name", "lookup", "description", description),
                Set.of("read"),
                catalog.identity());
    }

    AuthenticatedActor publisher() {
        return actor("publisher", "tenant", "service", "publisher");
    }

    AuthenticatedActor reviewer() {
        return actor("reviewer", "tenant", "human", "reviewer");
    }

    AuthenticatedActor revoker() {
        return actor("security", "tenant", "service", "revoker");
    }

    AuthenticatedActor actor(String subject, String tenant, String kind, String... roles) {
        long now = clock.millis() / 1000;
        var fields = new LinkedHashMap<String, Object>();
        fields.put("iss", identity.issuer());
        fields.put("aud", identity.audience());
        fields.put("sub", subject);
        fields.put("jti", UUID.randomUUID().toString());
        fields.put("iat", now);
        fields.put("exp", now + 1800);
        fields.put("client_id", "release-ui");
        fields.put("agt_tenant", tenant);
        fields.put("agt_actor_kind", kind);
        fields.put("agt_roles", List.of(roles));
        fields.put("acr", "human-mfa");
        fields.put("amr", List.of("mfa"));
        fields.put("auth_time", now);
        try {
            var jwt =
                    new JWSObject(
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .type(new JOSEObjectType("at+jwt"))
                                    .keyID("key")
                                    .build(),
                            new Payload(JsonValues.write(fields)));
            jwt.sign(new RSASSASigner((RSAPrivateKey) RSA.getPrivate()));
            return verifier.verify(new AccessToken(jwt.serialize()));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    void approve(ToolDescriptor descriptor) {
        store.submit(publisher(), descriptor);
        var actor = reviewer();
        store.decide(actor, store.review(actor, descriptor.identity()), true);
    }

    long revision() throws Exception {
        try (var c = primary.getConnection();
                var q = c.createStatement();
                var r = q.executeQuery("SELECT seq FROM agt_tool_releases")) {
            assert r.next();
            return r.getLong(1);
        }
    }

    static KeyPair key() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new AssertionError(failure);
        }
    }

    static JdbcDataSource data() {
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:release-"
                        + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000");
        return ds;
    }

    static StorageKeys keys() {
        var random = new SecureRandom();
        byte[] aes = new byte[32], mac = new byte[32];
        random.nextBytes(aes);
        random.nextBytes(mac);
        var key =
                new StorageKeys.Version(
                        "v1", new SecretKeySpec(aes, "AES"), new SecretKeySpec(mac, "HmacSHA256"));
        return new StorageKeys() {
            public Version active() {
                return key;
            }

            public Version find(String id) {
                return "v1".equals(id) ? key : null;
            }
        };
    }

    static final class MutableClock extends Clock {
        final AtomicLong time = new AtomicLong(System.currentTimeMillis());

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        public long millis() {
            return time.get();
        }
    }

    public void close() {
        store.close();
        verifier.close();
    }
}
