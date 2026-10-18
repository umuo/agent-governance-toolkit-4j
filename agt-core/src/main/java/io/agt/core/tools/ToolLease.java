package io.agt.core.tools;

import io.agt.core.*;
import java.util.*;

/** 只有实际制品与审核清单一致才创建；宿主工厂使用快照装配执行器，不能用它声明任意 delegate 已验签。 */
public final class ToolLease {
    private final ReviewedToolCatalog catalog;
    private final ToolDescriptor descriptor;
    private final byte[] artifact;

    ToolLease(ReviewedToolCatalog catalog, ToolDescriptor descriptor, byte[] artifact) {
        this.catalog = catalog;
        this.descriptor = descriptor;
        this.artifact = artifact;
    }

    public String name() {
        return descriptor.name();
    }

    public String identity() {
        return descriptor.identity();
    }

    public Map<String, Object> specification() {
        check();
        return descriptor.specification();
    }

    public byte[] artifactSnapshot() {
        check();
        return artifact.clone();
    }

    public void check() {
        catalog.check(descriptor);
    }

    public void validate(TrustedContext context) {
        check();
        if (!context.permissions().containsAll(descriptor.permissions()))
            throw ReviewedToolCatalog.denied("tool_version_permission_denied");
    }

    public void validateSpecification(Map<String, Object> actual) {
        check();
        if (!descriptor.specification().equals(JsonValues.parseObject(JsonValues.write(actual))))
            throw ReviewedToolCatalog.denied("tool_specification_mismatch");
    }

    public String toString() {
        return "ToolLease[redacted]";
    }
}
