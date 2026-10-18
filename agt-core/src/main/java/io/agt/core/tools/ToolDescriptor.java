package io.agt.core.tools;

import io.agt.core.*;
import java.util.*;

/** 宿主审核的不可变工具清单；描述/schema/制品版本和权限均参与摘要，不接受模型自报。 */
public record ToolDescriptor(
        String name,
        String provider,
        String version,
        String artifactDigest,
        Map<String, Object> specification,
        Set<String> permissions) {
    public ToolDescriptor {
        Identifiers.require(name);
        Identifiers.require(provider);
        Identifiers.require(version);
        if (artifactDigest == null || !artifactDigest.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid artifact digest");
        specification = JsonValues.parseObject(JsonValues.write(specification));
        permissions = Set.copyOf(permissions);
        if (!name.equals(specification.get("name"))
                || io.agt.core.storage.StorageCrypto.json(specification).length > 65536
                || permissions.size() > 128)
            throw new IllegalArgumentException("Invalid tool descriptor");
        permissions.forEach(Identifiers::require);
    }

    public String identity() {
        return JsonValues.digest(
                List.of(
                        "AGT-TOOL-DESCRIPTOR-V1",
                        name,
                        provider,
                        version,
                        artifactDigest,
                        specification,
                        permissions.stream().sorted().toList()));
    }

    public String toString() {
        return "ToolDescriptor[" + name + ",redacted]";
    }
}
