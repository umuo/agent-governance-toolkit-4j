package io.agt.core;

/**
 * 治理标识符校验工具类。
 *
 * <p>确保所有策略版本、原因代码、动作与会话 ID 满足严格字符集与长度限制。
 */
public final class Identifiers {
    private Identifiers() {}

    public static String require(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_.:@/-]{1,128}")) {
            throw new IllegalArgumentException("Invalid governance identifier");
        }
        return value;
    }
}
