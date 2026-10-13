package io.agt.core.approval;

public record ApprovalResponse(String requestId, String actionIdentity, boolean approved) {
  public static ApprovalResponse allow(ApprovalRequest r) {
    return new ApprovalResponse(r.requestId(), r.actionIdentity(), true);
  }

  public static ApprovalResponse deny(ApprovalRequest r) {
    return new ApprovalResponse(r.requestId(), r.actionIdentity(), false);
  }
}
