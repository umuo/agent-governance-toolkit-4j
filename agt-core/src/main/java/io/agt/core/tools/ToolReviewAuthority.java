package io.agt.core.tools;

import io.agt.core.TrustedContext;

/** 宿主可信的在线发布审核目录；必须有界查询，未知状态拒绝，不能缓存成永久批准。 */
public interface ToolReviewAuthority {
    String identity();

    void check(ToolDescriptor descriptor);

    void validateContext(TrustedContext context);
}
