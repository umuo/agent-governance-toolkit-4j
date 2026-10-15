package io.agt.langchain4j.internal.data;

import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.InterventionPoint;

/**
 * 记忆与检索共享的数据限额和失败终止语义。
 *
 * <p>仅用于适配器内部；不依赖具体记忆或检索实现。
 */
public final class DataAccessSupport {
    private DataAccessSupport() {}

    public static void require(
            AgentControl control,
            io.agt.core.data.DataResource resource,
            io.agt.core.data.DataResource.Kind kind) {
        control.requirePoints(
                java.util.Set.of(
                        InterventionPoint.PRE_DATA_ACCESS, InterventionPoint.POST_DATA_ACCESS));
        if (resource.kind() != kind) {
            throw new GovernanceException("data_resource_kind_mismatch");
        }
    }

    public static void limit(int size, HostSession session) {
        if (size > session.dataLimits().maxItems()) {
            throw new GovernanceException("data_items_exceeded");
        }
    }

    public static GovernanceException failure(HostSession session, RuntimeException error) {
        String code = error instanceof GovernanceException g ? g.code() : "data_access_failed";
        if (session.budgetSnapshot().failed()) {
            return session.refuse(code);
        }
        return session.fail(InterventionPoint.PRE_DATA_ACCESS, code);
    }
}
