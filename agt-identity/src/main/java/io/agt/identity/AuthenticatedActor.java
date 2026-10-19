package io.agt.identity;

import io.agt.core.*;
import io.agt.core.delegation.DelegationScope;
import io.agt.core.policy.StructuredPolicy;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/** 已验签且持续受在线状态约束的身份；JWT/Agent 标签不能自行声称人工审批资格。 */
public final class AuthenticatedActor {
    final JwtIdentityVerifier verifier;
    final VerifiedToken token;
    private final AtomicLong observed;
    private final long started = System.nanoTime(), initialRemaining;
    private final AtomicReference<String> refused = new AtomicReference<>();

    AuthenticatedActor(JwtIdentityVerifier verifier, VerifiedToken token, long now) {
        this.verifier = verifier;
        this.token = token;
        observed = new AtomicLong(now);
        initialRemaining = token.expiresAt - now;
    }

    long now() {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        long monotonic = token.expiresAt - initialRemaining + elapsed;
        return observed.accumulateAndGet(Math.max(verifier.clock.millis(), monotonic), Math::max);
    }

    public long remainingMillis() {
        return Math.max(
                0,
                Math.min(
                        token.expiresAt - now(),
                        initialRemaining
                                - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
    }

    public void checkActive() {
        String failed = refused.get();
        if (failed != null) throw new GovernanceException(failed);
        try {
            if (remainingMillis() <= 0) throw new GovernanceException("identity_token_expired");
            verifier.checkStatus(token);
            if (remainingMillis() <= 0) throw new GovernanceException("identity_token_expired");
            // 并发检查已经拒绝该身份时，较晚到达的 active=true 不能将其恢复。
            String concurrentFailure = refused.get();
            if (concurrentFailure != null) throw new GovernanceException(concurrentFailure);
        } catch (GovernanceException failure) {
            // 调用线程取消不构成身份失效证据；本次仍拒绝，下次必须重新在线校验。
            // 已观察的撤销、超时和不可用继续保持不可逆，不能由较晚的有效响应恢复。
            if (!"identity_status_cancelled".equals(failure.code())
                    || !Thread.currentThread().isInterrupted())
                refused.compareAndSet(null, failure.code());
            throw failure;
        }
    }

    public TrustedContext context(String agentId) {
        checkActive();
        return new TrustedContext(agentId, token.tenant, principalId(), token.permissions);
    }

    public String principalId() {
        return verifier.policy.principal(token.subject);
    }

    public String tenantId() {
        return token.tenant;
    }

    public String tokenIdentity() {
        return token.tokenIdentity();
    }

    /** 供可信审批/授权适配器比较固定身份规则，不从令牌自报字段获取。 */
    public String identityPolicyId() {
        return verifier.policy.identity();
    }

    /** 在线有效身份的单调观察时间，供同一宿主的持久审批期限比较。 */
    public long currentTimeMillis() {
        checkActive();
        return now();
    }

    public void requirePermission(String permission) {
        checkActive();
        if (!token.permissions.contains(permission))
            throw new GovernanceException("identity_permission_denied");
    }

    public void requireHuman() {
        checkActive();
        long now = now();
        if (!"human".equals(token.kind)
                || token.authTime < 0
                || token.authTime > now
                || now - token.authTime > verifier.policy.maxHumanAuthAgeMillis()
                || !verifier.policy.humanAcr().contains(token.acr)
                || !token.amr.contains("mfa"))
            throw new GovernanceException("identity_human_assurance_denied");
    }

    public IdentitySession permit(String agentId, StructuredPolicy policy, DelegationScope scope) {
        checkActive();
        return verifier.permit(this, agentId, policy, scope);
    }

    @Override
    public String toString() {
        return "AuthenticatedActor[redacted]";
    }
}
