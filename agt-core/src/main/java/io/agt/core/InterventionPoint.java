package io.agt.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 治理生命周期干预点枚举。
 *
 * <p>定义 Agent 启动、输入输出、模型调用前后、工具调用前后、数据访问前后及退出等拦截点。
 */
public enum InterventionPoint {
    AGENT_STARTUP,
    INPUT,
    PRE_MODEL_CALL,
    POST_MODEL_CALL,
    PRE_TOOL_CALL,
    POST_TOOL_CALL,
    OUTPUT,
    AGENT_SHUTDOWN,
    PRE_DATA_ACCESS,
    POST_DATA_ACCESS;

    public static Set<InterventionPoint> lifecyclePoints() {
        return Collections.unmodifiableSet(EnumSet.range(AGENT_STARTUP, AGENT_SHUTDOWN));
    }

    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static InterventionPoint parse(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }
}
