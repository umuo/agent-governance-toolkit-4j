package io.agt.core.data;

/**
 * 会话数据访问额度和单次返回数量限制配置。
 *
 * <p>不把数据访问记成模型或工具执行。maxCalls 计受控源接口调用，maxItems 限制单次消息或文档数量。
 */
public record DataLimits(int maxCalls, int maxItems) {
    public static DataLimits defaults() {
        return new DataLimits(1000, 1000);
    }

    public DataLimits {
        if (maxCalls < 1 || maxCalls > 1_000_000 || maxItems < 1 || maxItems > 10_000) {
            throw new IllegalArgumentException("Invalid data limits");
        }
    }
}
