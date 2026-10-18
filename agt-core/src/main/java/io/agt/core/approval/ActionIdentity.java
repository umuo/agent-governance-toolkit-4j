package io.agt.core.approval;

import io.agt.core.*;
import java.util.*;

/** 核心审批动作的既有摘要规范；身份、策略、有效参数和宿主副作用目标均参与绑定。 */
public final class ActionIdentity {
    private ActionIdentity() {}

    public static String of(String policyVersion, PolicyRequest request) {
        Identifiers.require(policyVersion);
        var context = request.context();
        var fields =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "point",
                                request.point().wireName(),
                                "sessionId",
                                request.sessionId(),
                                "actionId",
                                request.actionId(),
                                "toolName",
                                request.toolName() == null ? "" : request.toolName(),
                                "context",
                                Map.of(
                                        "agent",
                                        context.agentId(),
                                        "tenant",
                                        context.tenantId(),
                                        "principal",
                                        context.principalId(),
                                        "permissions",
                                        context.permissions().stream().sorted().toList()),
                                "policyVersion",
                                policyVersion,
                                "target",
                                Collections.singletonList(request.target())));
        if (request.effect() != null) fields.put("effect", request.effect().identity());
        if (request.toolBinding() != null) fields.put("toolBinding", request.toolBinding());
        return JsonValues.digest(fields);
    }
}
