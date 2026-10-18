package io.agt.identity;

import io.agt.core.*;
import java.net.URI;
import java.util.*;

/** 固定访问令牌 profile；身份源需配置 agt_tenant/agt_roles/agt_actor_kind 声明映射。 */
public record IdentityPolicy(
        String issuer,
        String audience,
        Set<String> clients,
        Map<String, Set<String>> rolePermissions,
        long maxTokenLifetimeMillis,
        long maxHumanAuthAgeMillis,
        Set<String> humanAcr,
        int statusTimeoutMillis) {
    public IdentityPolicy {
        var uri = URI.create(issuer);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null
                || issuer.length() > 2048)
            throw new IllegalArgumentException("A fixed HTTPS issuer is required");
        if (audience == null
                || audience.isBlank()
                || audience.length() > 256
                || audience.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Invalid identity audience");
        clients = Set.copyOf(clients);
        humanAcr = Set.copyOf(humanAcr);
        if (clients.isEmpty() || clients.size() > 32 || humanAcr.isEmpty() || humanAcr.size() > 16)
            throw new IllegalArgumentException("Invalid identity profiles");
        clients.forEach(Identifiers::require);
        humanAcr.forEach(Identifiers::require);
        var reviewed = new TreeMap<String, Set<String>>();
        rolePermissions.forEach(
                (role, permissions) -> {
                    Identifiers.require(role);
                    var copy = Set.copyOf(permissions);
                    copy.forEach(Identifiers::require);
                    if (copy.size() > 128)
                        throw new IllegalArgumentException("Too many mapped permissions");
                    reviewed.put(role, copy);
                });
        if (reviewed.isEmpty() || reviewed.size() > 64)
            throw new IllegalArgumentException("Invalid reviewed role map");
        rolePermissions = Collections.unmodifiableMap(reviewed);
        if (maxTokenLifetimeMillis < 1000
                || maxTokenLifetimeMillis > 3600000
                || maxHumanAuthAgeMillis < 1000
                || maxHumanAuthAgeMillis > 3600000
                || statusTimeoutMillis < 1
                || statusTimeoutMillis > 5000)
            throw new IllegalArgumentException("Invalid identity deadlines");
    }

    public String principal(String subject) {
        return "p-"
                + JsonValues.digest(List.of("AGT-IDENTITY-SUBJECT-V1", issuer, subject))
                        .substring(7);
    }

    public String identity() {
        return JsonValues.digest(
                Map.of(
                        "issuer",
                        issuer,
                        "audience",
                        audience,
                        "clients",
                        clients.stream().sorted().toList(),
                        "roles",
                        rolePermissions.entrySet().stream()
                                .map(
                                        e ->
                                                List.of(
                                                        e.getKey(),
                                                        e.getValue().stream().sorted().toList()))
                                .toList(),
                        "lifetime",
                        maxTokenLifetimeMillis,
                        "humanAge",
                        maxHumanAuthAgeMillis,
                        "humanAcr",
                        humanAcr.stream().sorted().toList(),
                        "statusTimeout",
                        statusTimeoutMillis));
    }
}
