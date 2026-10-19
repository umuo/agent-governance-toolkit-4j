package io.agt.tools.jdbc;

import io.agt.core.*;
import java.util.*;

/** 宿主固定的租户、身份源和发布职责；容量与超时也进入目录身份，不能由接收者降级。 */
public record ToolReleasePolicy(
        String tenantId,
        String identityPolicyId,
        Set<String> publishers,
        Set<String> reviewers,
        Set<String> revokers,
        int maxReleases,
        int maxReviews,
        int maxStateBytes,
        int operationTimeoutMillis) {
    public ToolReleasePolicy {
        Identifiers.require(tenantId);
        if (identityPolicyId == null || !identityPolicyId.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid identity policy");
        }
        publishers = permissions(publishers);
        reviewers = permissions(reviewers);
        revokers = permissions(revokers);
        if (maxReleases < 1
                || maxReleases > 128
                || maxReviews < 1
                || maxReviews > 16
                || maxStateBytes < maxReleases * 4096 + 8192
                || maxStateBytes > 1048548
                || operationTimeoutMillis < 100
                || operationTimeoutMillis > 30000) {
            throw new IllegalArgumentException("Invalid bounded release policy");
        }
    }

    private static Set<String> permissions(Set<String> input) {
        var copy = Set.copyOf(input);
        if (copy.isEmpty() || copy.size() > 16) {
            throw new IllegalArgumentException("Explicit release permissions required");
        }
        copy.forEach(Identifiers::require);
        return copy;
    }

    public String identity() {
        return JsonValues.digest(
                List.of(
                        "AGT-TOOL-RELEASE-POLICY-V1",
                        tenantId,
                        identityPolicyId,
                        publishers.stream().sorted().toList(),
                        reviewers.stream().sorted().toList(),
                        revokers.stream().sorted().toList(),
                        maxReleases,
                        maxReviews,
                        maxStateBytes,
                        operationTimeoutMillis));
    }
}
