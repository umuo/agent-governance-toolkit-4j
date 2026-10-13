package io.agt.core.approval;

import io.agt.core.PolicyRequest;

public record ApprovalRequest(
    String requestId,
    String actionIdentity,
    String policyVersion,
    PolicyRequest action,
    long expiresAtEpochMillis) {
  @Override
  public String toString() {
    return "ApprovalRequest[" + requestId + "]";
  }
}
