package io.agt.delegation;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import io.agt.core.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.*;
import java.security.interfaces.*;
import java.time.Clock;
import java.util.*;

/** 版本固定的 JWS 授权概要；不是通用 OAuth 令牌端点或任意 JWT 验证器。 */
final class DelegationJwt {
    static final JOSEObjectType TYPE = new JOSEObjectType("agt-delegation+jwt");
    private static final Set<String> CLAIMS =
            Set.of(
                    "iss",
                    "aud",
                    "sub",
                    "jti",
                    "iat",
                    "nbf",
                    "exp",
                    "act",
                    "tenant",
                    "task",
                    "grant_hash");

    record Claims(
            String grantId,
            String digest,
            String actor,
            String subject,
            String tenant,
            String task,
            long issuedAt,
            long expiresAt) {}

    private DelegationJwt() {}

    static void requireKey(RSAKey key) {
        if (key.getModulus().bitLength() < 2048 || key.getModulus().bitLength() > 8192)
            throw new IllegalArgumentException("Invalid RSA key size");
    }

    static DelegationToken sign(GrantDocument grant, String kid, RSAPrivateKey key) {
        try {
            requireKey(key);
            var claims =
                    new JWTClaimsSet.Builder()
                            .issuer(grant.issuer())
                            .audience(grant.audience())
                            .subject(grant.identity().principalId())
                            .jwtID(grant.id())
                            .issueTime(new Date(grant.issuedAt()))
                            .notBeforeTime(new Date(grant.issuedAt()))
                            .expirationTime(new Date(grant.expiresAt()))
                            .claim("act", Map.of("sub", grant.identity().agentId()))
                            .claim("tenant", grant.identity().tenantId())
                            .claim("task", grant.taskId())
                            .claim("grant_hash", grant.digest())
                            .build();
            var jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256).type(TYPE).keyID(kid).build(),
                            claims);
            jwt.sign(new RSASSASigner(key));
            return new DelegationToken(jwt.serialize());
        } catch (Exception error) {
            throw new GovernanceException("delegation_signing_failed");
        }
    }

    static Claims verify(
            DelegationToken token,
            String issuer,
            String audience,
            Map<String, RSAPublicKey> keys,
            Clock clock) {
        try {
            var parts = token.value().split("\\.", -1);
            if (parts.length != 3) throw new IllegalArgumentException();
            var header = json(parts[0]);
            var claims = json(parts[1]);
            if (!header.keySet().equals(Set.of("alg", "typ", "kid"))
                    || !"RS256".equals(header.get("alg"))
                    || !TYPE.toString().equals(header.get("typ"))
                    || !(header.get("kid") instanceof String kid)
                    || !keys.containsKey(kid)
                    || !claims.keySet().equals(CLAIMS)) throw new IllegalArgumentException();
            var jwt = SignedJWT.parse(token.value());
            if (!jwt.verify(new RSASSAVerifier(keys.get(kid))))
                throw new IllegalArgumentException();
            long issued = millis(claims.get("iat")),
                    notBefore = millis(claims.get("nbf")),
                    expires = millis(claims.get("exp"));
            long now = clock.millis();
            if (!issuer.equals(claims.get("iss"))
                    || !(audience.equals(claims.get("aud"))
                            || List.of(audience).equals(claims.get("aud")))
                    || issued > now
                    || notBefore != issued
                    || expires <= now
                    || expires <= issued
                    || expires - issued > 600000
                    || !(claims.get("act") instanceof Map<?, ?> actor)
                    || !actor.keySet().equals(Set.of("sub"))) throw new IllegalArgumentException();
            String id = identifier(claims.get("jti")), actorId = identifier(actor.get("sub"));
            String subject = identifier(claims.get("sub")),
                    tenant = identifier(claims.get("tenant")),
                    task = identifier(claims.get("task"));
            if (!(claims.get("grant_hash") instanceof String digest)
                    || !digest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException();
            return new Claims(id, digest, actorId, subject, tenant, task, issued, expires);
        } catch (Exception invalid) {
            throw new GovernanceException("delegation_token_denied");
        }
    }

    private static String identifier(Object value) {
        return Identifiers.require((String) value);
    }

    private static long millis(Object number) {
        return Math.multiplyExact(((BigInteger) number).longValueExact(), 1000);
    }

    private static Map<String, Object> json(String encoded) throws CharacterCodingException {
        if (!encoded.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
        var decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        return JsonValues.parseObject(
                decoder.decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded))).toString());
    }
}
