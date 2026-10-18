package io.agt.identity;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

final class IdentityFixture {
    static final KeyPair KEY = key();
    static final long NOW = 1800000000000L;

    static KeyPair key() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static IdentityPolicy policy(int timeout) {
        return new IdentityPolicy(
                "https://issuer.example.invalid",
                "agent-api",
                Set.of("runtime", "review-ui"),
                Map.of("reader", Set.of("read"), "reviewer", Set.of("approval:decide")),
                60000,
                10000,
                Set.of("mfa-level"),
                timeout);
    }

    static Map<String, Object> claims() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("iss", policy(1000).issuer());
        fields.put("aud", "agent-api");
        fields.put("sub", "external|subject@example.invalid");
        fields.put("jti", UUID.randomUUID().toString());
        fields.put("iat", NOW / 1000);
        fields.put("exp", NOW / 1000 + 60);
        fields.put("client_id", "runtime");
        fields.put("agt_tenant", "tenant-a");
        fields.put("agt_actor_kind", "human");
        fields.put("agt_roles", List.of("reader"));
        fields.put("acr", "mfa-level");
        fields.put("amr", List.of("mfa"));
        fields.put("auth_time", NOW / 1000);
        return fields;
    }

    static AccessToken token(Map<String, Object> fields) {
        return signed(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(new JOSEObjectType("at+jwt"))
                        .keyID("key-a")
                        .build(),
                io.agt.core.JsonValues.write(fields),
                KEY);
    }

    static AccessToken signed(JWSHeader header, String json, KeyPair key) {
        try {
            var jws = new JWSObject(header, new Payload(json));
            jws.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
            return new AccessToken(jws.serialize());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static JwtIdentityVerifier verifier(TokenStatus status, Clock clock) {
        return new JwtIdentityVerifier(
                policy(1000), Map.of("key-a", (RSAPublicKey) KEY.getPublic()), status, clock);
    }

    static final class MutableClock extends Clock {
        final AtomicLong time = new AtomicLong(NOW);

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
}
