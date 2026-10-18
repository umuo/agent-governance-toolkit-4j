package io.agt.approval.jdbc;

import io.agt.core.*;
import io.agt.core.policy.*;
import java.util.*;

/** 宿主固定的审批边界；没有默认审批角色、工具或身份源。 */
public record ApprovalPolicy(
        String identityPolicyId,
        StructuredPolicy.Document runtimePolicy,
        Set<String> requesterPermissions,
        Map<String, Set<String>> reviewerPermissions,
        int maxRequests,
        int maxRequestBytes,
        int maxPendingMillis,
        int maxReviews,
        int maxWaiters,
        int pollMillis) {
    public ApprovalPolicy {
        if (identityPolicyId == null || !identityPolicyId.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid identity policy");
        var reviewedRuntime =
                new StructuredPolicy(Objects.requireNonNull(runtimePolicy)).document();
        runtimePolicy = reviewedRuntime;
        requesterPermissions = Set.copyOf(requesterPermissions);
        if (requesterPermissions.isEmpty() || requesterPermissions.size() > 16)
            throw new IllegalArgumentException("Requester permissions are required");
        requesterPermissions.forEach(Identifiers::require);
        var copy = new TreeMap<String, Set<String>>();
        reviewerPermissions.forEach(
                (tool, permissions) -> {
                    Identifiers.require(tool);
                    var rule = reviewedRuntime.tools().get(tool);
                    var allowed = Set.copyOf(permissions);
                    if (rule == null
                            || !rule.approvalRequired()
                            || allowed.isEmpty()
                            || allowed.size() > 16)
                        throw new IllegalArgumentException("Reviewed approval tool required");
                    allowed.forEach(Identifiers::require);
                    copy.put(tool, allowed);
                });
        if (copy.isEmpty()
                || copy.size() > 128
                || maxRequests < 1
                || maxRequests > 10000
                || maxRequestBytes < 1024
                || maxRequestBytes > 65536
                || maxPendingMillis < 1000
                || maxPendingMillis > 300000
                || maxReviews < 1
                || maxReviews > 16
                || maxWaiters < 1
                || maxWaiters > 32
                || pollMillis < 50
                || pollMillis > 1000)
            throw new IllegalArgumentException("Invalid bounded approval policy");
        reviewerPermissions = Collections.unmodifiableMap(copy);
    }

    public String identity() {
        return JsonValues.digest(
                List.of(
                        "AGT-APPROVAL-POLICY-V1",
                        identityPolicyId,
                        PolicyFingerprints.digest(runtimePolicy),
                        requesterPermissions.stream().sorted().toList(),
                        reviewerPermissions.entrySet().stream()
                                .map(
                                        e ->
                                                List.of(
                                                        e.getKey(),
                                                        e.getValue().stream().sorted().toList()))
                                .toList(),
                        maxRequests,
                        maxRequestBytes,
                        maxPendingMillis,
                        maxReviews,
                        maxWaiters,
                        pollMillis));
    }
}
