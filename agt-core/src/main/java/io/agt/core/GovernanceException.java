package io.agt.core;

/** 公开错误仅携带稳定代码，不透传原文、策略消息或 delegate 异常。 */
public final class GovernanceException extends RuntimeException {
  private final String code;

  public GovernanceException(String code) {
    super("Governance operation refused (" + Identifiers.require(code) + ").");
    this.code = code;
  }

  public String code() {
    return code;
  }
}
