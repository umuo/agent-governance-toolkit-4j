package io.agt.identity;

import java.util.*;

/** 只能由验证器创建的已验签 profile，访问原 Bearer 仅供可信 introspection 适配器。 */
public final class VerifiedToken {
    private final AccessToken token;
    private final Map<String, Object> claims;
    final String issuer, audience, subject, tenant, jti, kind, client;
    final Set<String> roles, permissions, amr;
    final String acr;
    final long issuedAt, expiresAt, authTime;

    VerifiedToken(
            AccessToken token,
            Map<String, Object> claims,
            String issuer,
            String audience,
            String subject,
            String tenant,
            String jti,
            String kind,
            String client,
            Set<String> roles,
            Set<String> permissions,
            Set<String> amr,
            String acr,
            long issuedAt,
            long expiresAt,
            long authTime) {
        this.token = token;
        this.claims = claims;
        this.issuer = issuer;
        this.audience = audience;
        this.subject = subject;
        this.tenant = tenant;
        this.jti = jti;
        this.kind = kind;
        this.client = client;
        this.roles = Set.copyOf(roles);
        this.permissions = Set.copyOf(permissions);
        this.amr = Set.copyOf(amr);
        this.acr = acr;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.authTime = authTime;
    }

    public AccessToken credential() {
        return token;
    }

    public String tokenIdentity() {
        return io.agt.core.JsonValues.digest(List.of("AGT-IDENTITY-TOKEN-V1", issuer, jti));
    }

    public boolean matchesIntrospection(Map<String, Object> response) {
        for (String field :
                List.of("iss", "sub", "client_id", "jti", "exp", "agt_tenant", "agt_actor_kind"))
            if (!Objects.equals(claims.get(field), response.get(field))) return false;
        if (!audience.equals(response.get("aud")) && !List.of(audience).equals(response.get("aud")))
            return false;
        if (!(response.get("agt_roles") instanceof List<?> returnedRoles)
                || returnedRoles.size() > 64
                || returnedRoles.stream().anyMatch(role -> !(role instanceof String))
                || new HashSet<>(returnedRoles).size() != returnedRoles.size()
                || !roles.equals(new HashSet<>(returnedRoles))) return false;
        for (String field : List.of("acr", "auth_time", "amr"))
            if (claims.containsKey(field)
                    && !Objects.equals(claims.get(field), response.get(field))) return false;
        return true;
    }

    @Override
    public String toString() {
        return "VerifiedToken[redacted]";
    }
}
