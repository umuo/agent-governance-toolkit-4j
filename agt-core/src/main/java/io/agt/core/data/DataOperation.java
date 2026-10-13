package io.agt.core.data;

/**
 * 数据源操作类型枚举。
 *
 * <p>数据源操作独立于模型和工具调用；同一资源的读、写、替换和清空分别授权。作为策略与审计的稳定操作标识，不根据调用参数更换操作。
 */
public enum DataOperation {
    RETRIEVE,
    MEMORY_READ,
    MEMORY_ADD,
    MEMORY_SET,
    MEMORY_CLEAR
}
