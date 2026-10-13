package io.agt.core.data;

import io.agt.core.*;
import java.util.*;

/**
 * 默认拒绝的数据授权规则；资源类型、租户、所有者和各操作权限均显式校验。
 *
 * <p>操作权限逐项检查；不存在从读权限推导写权限。
 */
public record DataRule(
    DataResource.Kind kind, Scope scope, Map<DataOperation, Set<String>> permissions) {
  public enum Scope {
    TENANT,
    PRINCIPAL
  }

  public DataRule {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(scope);
    Objects.requireNonNull(permissions);
    var copy = new EnumMap<DataOperation, Set<String>>(DataOperation.class);
    permissions.forEach(
        (operation, required) -> {
          if ((kind == DataResource.Kind.RETRIEVAL) != (operation == DataOperation.RETRIEVE)
              || required == null
              || required.isEmpty()) {
            throw new IllegalArgumentException("Invalid data permission");
          }
          required.forEach(Identifiers::require);
          copy.put(operation, Set.copyOf(required));
        });
    permissions = Collections.unmodifiableMap(copy);
  }

  public void validate(PolicyRequest request) {
    var resource = DataResource.resource(request.target());
    var target = (Map<?, ?>) request.target();
    if (!kind.name().equals(resource.get("kind"))) {
      throw new GovernanceException("data_kind_denied");
    }
    if (!request.context().tenantId().equals(resource.get("tenantId"))) {
      throw new GovernanceException("data_tenant_denied");
    }
    if (scope == Scope.PRINCIPAL
        && !request.context().principalId().equals(resource.get("principalId"))) {
      throw new GovernanceException("data_owner_denied");
    }
    DataOperation operation;
    try {
      operation = DataOperation.valueOf((String) target.get("operation"));
    } catch (RuntimeException e) {
      throw new GovernanceException("invalid_data_target");
    }
    var required = permissions.get(operation);
    if (required == null) {
      throw new GovernanceException("data_operation_denied");
    }
    if (!request.context().permissions().containsAll(required)) {
      throw new GovernanceException("data_permission_denied");
    }
  }
}
