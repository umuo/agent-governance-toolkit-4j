package io.agt.identity;

/** Bearer 凭据仅供可信身份适配器使用，不进入日志或模型正文。 */
public final class AccessToken {
    private final String value;

    public AccessToken(String value) {
        if (value == null
                || value.length() > 32768
                || value.length() < 16
                || !value.matches("[A-Za-z0-9_.-]+"))
            throw new io.agt.core.GovernanceException("identity_token_denied");
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "AccessToken[redacted]";
    }
}
