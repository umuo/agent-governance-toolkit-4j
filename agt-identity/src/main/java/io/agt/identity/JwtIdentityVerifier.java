package io.agt.identity;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.agt.core.*;
import io.agt.core.delegation.DelegationScope;
import io.agt.core.policy.StructuredPolicy;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 固定 RS256/at+jwt 信任配置，Nimbus 执行验签，不从 token 的 URL 下载密钥。 */
public final class JwtIdentityVerifier implements AutoCloseable {
    final IdentityPolicy policy;
    final Clock clock;
    final IdentityLimits limits;
    private final Map<String, RSAPublicKey> keys;
    private final TokenStatus status;
    private final ThreadPoolExecutor checks;
    final ScheduledExecutorService monitor;
    final Set<IdentitySession> sessions = ConcurrentHashMap.newKeySet();
    private final Semaphore slots;
    private final AtomicBoolean closed = new AtomicBoolean();

    public JwtIdentityVerifier(
            IdentityPolicy policy,
            Map<String, RSAPublicKey> keys,
            TokenStatus status,
            Clock clock) {
        this(policy, keys, status, clock, IdentityLimits.DEFAULT);
    }

    public JwtIdentityVerifier(
            IdentityPolicy policy,
            Map<String, RSAPublicKey> keys,
            TokenStatus status,
            Clock clock,
            IdentityLimits limits) {
        this.limits = Objects.requireNonNull(limits);
        slots = new Semaphore(limits.maxSessions());
        this.policy = Objects.requireNonNull(policy);
        this.keys = Map.copyOf(keys);
        this.status = Objects.requireNonNull(status);
        this.clock = Objects.requireNonNull(clock);
        if (keys.isEmpty() || keys.size() > 32)
            throw new IllegalArgumentException("Invalid identity keys");
        keys.forEach(
                (kid, key) -> {
                    Identifiers.require(kid);
                    if (key.getModulus().bitLength() < 2048 || key.getModulus().bitLength() > 8192)
                        throw new IllegalArgumentException("Invalid identity RSA key");
                });
        checks =
                new ThreadPoolExecutor(
                        limits.statusWorkers(),
                        limits.statusWorkers(),
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(limits.statusQueue()),
                        task -> {
                            var thread = new Thread(task, "agt-identity-status");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        var scheduler =
                new ScheduledThreadPoolExecutor(
                        2,
                        task -> {
                            var thread = new Thread(task, "agt-identity-monitor");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.setRemoveOnCancelPolicy(true);
        monitor = scheduler;
    }

    public AuthenticatedActor verify(AccessToken token) {
        if (closed.get()) throw new GovernanceException("identity_verifier_closed");
        try {
            String[] parts = token.value().split("\\.", -1);
            if (parts.length != 3) throw new IllegalArgumentException();
            var header = json(parts[0], 4096);
            var claims = json(parts[1], 16384);
            decoded(parts[2], 2048);
            if (!header.keySet().equals(Set.of("alg", "typ", "kid"))
                    || !"RS256".equals(header.get("alg"))
                    || !(header.get("typ") instanceof String type)
                    || !Set.of("at+jwt", "application/at+jwt")
                            .contains(type.toLowerCase(Locale.ROOT))
                    || !(header.get("kid") instanceof String kid)
                    || !keys.containsKey(kid)) throw new IllegalArgumentException();
            if (!SignedJWT.parse(token.value()).verify(new RSASSAVerifier(keys.get(kid))))
                throw new IllegalArgumentException();
            long now = clock.millis(),
                    issued = millis(claims.get("iat")),
                    expires = millis(claims.get("exp"));
            long before = claims.containsKey("nbf") ? millis(claims.get("nbf")) : issued;
            if (!policy.issuer().equals(claims.get("iss"))
                    || (!policy.audience().equals(claims.get("aud"))
                            && !List.of(policy.audience()).equals(claims.get("aud")))
                    || issued > now
                    || issued < 0
                    || before > now
                    || expires <= now
                    || expires <= issued
                    || expires - issued > policy.maxTokenLifetimeMillis())
                throw new IllegalArgumentException();
            String subject = text(claims.get("sub"), 1024),
                    tenant = Identifiers.require((String) claims.get("agt_tenant")),
                    id = Identifiers.require((String) claims.get("jti")),
                    kind = (String) claims.get("agt_actor_kind"),
                    client = (String) claims.get("client_id");
            if (!Set.of("human", "service").contains(kind) || !policy.clients().contains(client))
                throw new IllegalArgumentException();
            Set<String> roles = strings(claims.get("agt_roles"));
            var permissions = new HashSet<String>();
            for (String role : roles) {
                var mapped = policy.rolePermissions().get(role);
                if (mapped == null) throw new IllegalArgumentException();
                permissions.addAll(mapped);
            }
            if (permissions.size() > 128) throw new IllegalArgumentException();
            String acr = claims.containsKey("acr") ? text(claims.get("acr"), 128) : "";
            long auth = claims.containsKey("auth_time") ? millis(claims.get("auth_time")) : -1;
            if (auth > now) throw new IllegalArgumentException();
            Set<String> amr = claims.containsKey("amr") ? strings(claims.get("amr")) : Set.of();
            var verified =
                    new VerifiedToken(
                            token,
                            claims,
                            policy.issuer(),
                            policy.audience(),
                            subject,
                            tenant,
                            id,
                            kind,
                            client,
                            roles,
                            permissions,
                            amr,
                            acr,
                            issued,
                            expires,
                            auth);
            var actor = new AuthenticatedActor(this, verified, now);
            actor.checkActive();
            return actor;
        } catch (GovernanceException refused) {
            if (refused.code().startsWith("identity_")) throw refused;
            throw new GovernanceException("identity_token_denied");
        } catch (Exception invalid) {
            throw new GovernanceException("identity_token_denied");
        }
    }

    void checkStatus(VerifiedToken token) {
        if (closed.get()) throw new GovernanceException("identity_verifier_closed");
        Future<Boolean> future;
        try {
            future = checks.submit(() -> status.active(token));
        } catch (RejectedExecutionException full) {
            throw new GovernanceException("identity_status_capacity");
        }
        try {
            if (!Boolean.TRUE.equals(
                    future.get(policy.statusTimeoutMillis(), TimeUnit.MILLISECONDS)))
                throw new GovernanceException("identity_token_inactive");
            if (closed.get()) throw new GovernanceException("identity_verifier_closed");
        } catch (TimeoutException slow) {
            throw new GovernanceException("identity_status_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GovernanceException("identity_status_cancelled");
        } catch (ExecutionException failed) {
            throw new GovernanceException("identity_status_unavailable");
        } finally {
            future.cancel(true);
            checks.remove((Runnable) future);
        }
    }

    IdentitySession permit(
            AuthenticatedActor actor,
            String agentId,
            StructuredPolicy policy,
            DelegationScope scope) {
        if (closed.get()) throw new GovernanceException("identity_verifier_closed");
        if (!slots.tryAcquire()) throw new GovernanceException("identity_session_capacity");
        IdentitySession permit = null;
        boolean registered = false;
        try {
            permit = new IdentitySession(this, actor, agentId, policy, scope);
            sessions.add(permit);
            registered = true;
            if (closed.get()) throw new GovernanceException("identity_verifier_closed");
            permit.expireUnattached();
            return permit;
        } catch (RuntimeException failed) {
            if (registered) permit.close();
            else slots.release();
            throw failed;
        }
    }

    void release(IdentitySession session) {
        if (sessions.remove(session)) slots.release();
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String text)
                || text.isBlank()
                || text.length() > max
                || text.codePoints().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException();
        return text;
    }

    private static Set<String> strings(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 64)
            throw new IllegalArgumentException();
        var result = new HashSet<String>();
        for (Object entry : list)
            if (!result.add(Identifiers.require((String) entry)))
                throw new IllegalArgumentException();
        return Set.copyOf(result);
    }

    private static long millis(Object value) {
        return Math.multiplyExact(((BigInteger) value).longValueExact(), 1000);
    }

    private static Map<String, Object> json(String encoded, int max)
            throws CharacterCodingException {
        return JsonValues.parseObject(
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(decoded(encoded, max)))
                        .toString());
    }

    private static byte[] decoded(String value, int max) {
        if (!value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (bytes.length > max
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))
            throw new IllegalArgumentException();
        return bytes;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        List.copyOf(sessions).forEach(IdentitySession::revoked);
        monitor.shutdownNow();
        checks.shutdownNow();
    }
}
