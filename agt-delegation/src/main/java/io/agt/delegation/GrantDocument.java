package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.data.DataLimits;
import io.agt.core.delegation.AuthorityFrame;
import io.agt.core.delegation.DelegationQuota;
import io.agt.core.usage.UsageLimits;
import java.util.List;

/** 由授权服务保存的不可变授权；令牌仅携带其签名摘要，不携带凭据或完整资源信息。 */
public record GrantDocument(
        String id,
        String issuer,
        String audience,
        TrustedContext identity,
        String taskId,
        String parentId,
        int depth,
        int maxDepth,
        long issuedAt,
        long expiresAt,
        List<AuthorityFrame> frames,
        DelegationQuota quota,
        String policyDigest,
        TrustedContext policyOwner,
        BudgetLimits sessionBudget,
        DataLimits dataLimits,
        UsageLimits usageLimits,
        String flowPolicy,
        java.util.Map<String, String> toolBindings) {
    public GrantDocument(
            String id,
            String issuer,
            String audience,
            TrustedContext identity,
            String taskId,
            String parentId,
            int depth,
            int maxDepth,
            long issuedAt,
            long expiresAt,
            List<AuthorityFrame> frames,
            DelegationQuota quota,
            String policyDigest,
            TrustedContext policyOwner,
            BudgetLimits sessionBudget,
            DataLimits dataLimits,
            UsageLimits usageLimits,
            String flowPolicy) {
        this(
                id,
                issuer,
                audience,
                identity,
                taskId,
                parentId,
                depth,
                maxDepth,
                issuedAt,
                expiresAt,
                frames,
                quota,
                policyDigest,
                policyOwner,
                sessionBudget,
                dataLimits,
                usageLimits,
                flowPolicy,
                java.util.Map.of());
    }

    public GrantDocument(
            String id,
            String issuer,
            String audience,
            TrustedContext identity,
            String taskId,
            String parentId,
            int depth,
            int maxDepth,
            long issuedAt,
            long expiresAt,
            List<AuthorityFrame> frames,
            DelegationQuota quota,
            String policyDigest,
            TrustedContext policyOwner,
            BudgetLimits sessionBudget,
            DataLimits dataLimits,
            UsageLimits usageLimits) {
        this(
                id,
                issuer,
                audience,
                identity,
                taskId,
                parentId,
                depth,
                maxDepth,
                issuedAt,
                expiresAt,
                frames,
                quota,
                policyDigest,
                policyOwner,
                sessionBudget,
                dataLimits,
                usageLimits,
                null);
    }

    public GrantDocument {
        toolBindings =
                toolBindings == null ? java.util.Map.of() : java.util.Map.copyOf(toolBindings);
        if (toolBindings.size() > 128) throw new IllegalArgumentException("Too many tool bindings");
        toolBindings.forEach(
                (name, binding) -> {
                    Identifiers.require(name);
                    if (!binding.matches("sha256:[0-9a-f]{64}"))
                        throw new IllegalArgumentException("Invalid tool binding");
                });
        Identifiers.require(id);
        Identifiers.require(issuer);
        Identifiers.require(audience);
        Identifiers.require(taskId);
        if (parentId != null) Identifiers.require(parentId);
        java.util.Objects.requireNonNull(identity);
        java.util.Objects.requireNonNull(quota);
        java.util.Objects.requireNonNull(policyOwner);
        java.util.Objects.requireNonNull(sessionBudget);
        java.util.Objects.requireNonNull(dataLimits);
        if (flowPolicy != null
                && !GrantCodec.encodeFlowPolicy(GrantCodec.decodeFlowPolicy(flowPolicy))
                        .equals(flowPolicy))
            throw new IllegalArgumentException("Noncanonical flow policy");
        if (policyDigest == null || !policyDigest.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid policy digest");
        frames = List.copyOf(frames);
        if (depth < 0
                || maxDepth < depth
                || maxDepth > 32
                || issuedAt < 0
                || expiresAt <= issuedAt
                || expiresAt - issuedAt > 600000
                || frames.isEmpty()
                || frames.size() > 33) {
            throw new IllegalArgumentException("Invalid delegation grant");
        }
        String task = frames.get(0).scope().taskName();
        if (!policyOwner.tenantId().equals(identity.tenantId())
                || !policyOwner.principalId().equals(identity.principalId())
                || frames.stream()
                        .anyMatch(
                                frame ->
                                        !frame.scope().taskName().equals(task)
                                                || !frame.owner()
                                                        .tenantId()
                                                        .equals(identity.tenantId())
                                                || !frame.owner()
                                                        .principalId()
                                                        .equals(identity.principalId()))) {
            throw new IllegalArgumentException("Invalid delegation lineage");
        }
    }

    public String digest() {
        return GrantCodec.digest(this);
    }

    @Override
    public String toString() {
        return "GrantDocument[redacted]";
    }
}
