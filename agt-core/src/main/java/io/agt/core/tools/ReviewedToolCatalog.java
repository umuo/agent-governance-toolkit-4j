package io.agt.core.tools;

import io.agt.core.*;
import io.agt.core.storage.StorageCrypto;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 可信宿主固定审核清单；只校验实际提供的制品字节，不自动加载/执行插件或声称远端代码证明。 */
public final class ReviewedToolCatalog implements AutoCloseable {
    private final Map<String, ToolDescriptor> reviewed;
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int maxArtifactBytes;
    private final Map<String, ToolLease> registrations = new HashMap<>();
    private int retained;
    private final ToolReviewAuthority authority;

    public ReviewedToolCatalog(Collection<ToolDescriptor> reviewed, int maxArtifactBytes) {
        this(reviewed, maxArtifactBytes, null);
    }

    public ReviewedToolCatalog(
            Collection<ToolDescriptor> reviewed,
            int maxArtifactBytes,
            ToolReviewAuthority authority) {
        this.authority = authority;
        if (reviewed.isEmpty()
                || reviewed.size() > 128
                || maxArtifactBytes < 1
                || maxArtifactBytes > 16777216)
            throw new IllegalArgumentException("Invalid bounded tool catalog");
        var snapshot = new LinkedHashMap<String, ToolDescriptor>();
        for (var descriptor : reviewed) {
            if (!Objects.equals(
                    descriptor.reviewAuthority(),
                    authority == null ? null : authority.identity())) {
                throw denied("tool_review_authority_required");
            }
            if (snapshot.putIfAbsent(descriptor.name(), descriptor) != null)
                throw new IllegalArgumentException("Duplicate reviewed tool");
        }
        this.reviewed = Map.copyOf(snapshot);
        this.maxArtifactBytes = maxArtifactBytes;
    }

    public synchronized ToolLease register(
            String name, byte[] actualArtifact, Map<String, Object> actualSpecification) {
        var descriptor = reviewed.get(Identifiers.require(name));
        if (descriptor == null) throw denied("tool_version_unreviewed");
        check(descriptor);
        if (Objects.requireNonNull(actualArtifact).length > maxArtifactBytes)
            throw denied("tool_artifact_capacity");
        byte[] artifact = actualArtifact.clone();
        if (!descriptor.artifactDigest().equals("sha256:" + StorageCrypto.hash(artifact)))
            throw denied("tool_artifact_mismatch");
        if (!descriptor
                .specification()
                .equals(JsonValues.parseObject(JsonValues.write(actualSpecification))))
            throw denied("tool_specification_mismatch");
        check(descriptor);
        var prior = registrations.get(name);
        if (prior != null) return prior;
        if (artifact.length > maxArtifactBytes - retained) throw denied("tool_artifact_capacity");
        var lease = new ToolLease(this, descriptor, artifact);
        registrations.put(name, lease);
        retained += artifact.length;
        return lease;
    }

    public void revoke(String name) {
        if (!reviewed.containsKey(Identifiers.require(name)))
            throw denied("tool_version_unreviewed");
        revoked.add(name);
    }

    void check(ToolDescriptor descriptor) {
        if (closed.get()
                || revoked.contains(descriptor.name())
                || !descriptor.equals(reviewed.get(descriptor.name())))
            throw denied("tool_version_revoked");
        if (authority != null) {
            authority.check(descriptor);
        }
    }

    void validateContext(TrustedContext context) {
        if (authority != null) {
            authority.validateContext(context);
        }
    }

    public void close() {
        closed.set(true);
    }

    static GovernanceException denied(String code) {
        return new GovernanceException(code);
    }
}
