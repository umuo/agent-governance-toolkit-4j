package io.agt.delegation;

/** 此值是凭据，不是模型上下文；默认日志显示不包含 compact token。 */
public record DelegationToken(String value) {
    public DelegationToken {
        if (value == null || value.length() > 16384)
            throw new IllegalArgumentException("Invalid token length");
    }

    @Override
    public String toString() {
        return "DelegationToken[redacted]";
    }
}
