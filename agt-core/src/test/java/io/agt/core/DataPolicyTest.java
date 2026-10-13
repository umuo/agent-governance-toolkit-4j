package io.agt.core;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.data.*;
import io.agt.core.policy.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DataPolicyTest {
  static final TrustedContext CTX =
      new TrustedContext("agent", "tenant-a", "alice", Set.of("read"));
  static final DataResource SOURCE =
      new DataResource("knowledge", DataResource.Kind.RETRIEVAL, "tenant-a", null, "index-a");
  static final String YAML =
      """
      apiVersion: agt.java/v1
      version: data-test
      interventionPoints: [agent_startup, input, pre_model_call, post_model_call, pre_tool_call, post_tool_call, output, agent_shutdown, pre_data_access, post_data_access]
      budget: {maxModelCalls: 20, maxToolCalls: 20, maxDurationMillis: 300000}
      dataLimits: {maxCalls: 1, maxItems: 10}
      tools: {}
      resources:
        knowledge:
          kind: RETRIEVAL
          scope: TENANT
          permissions:
            RETRIEVE: [read]
      contentRules:
        - id: rewrite
          points: [pre_data_access]
          denyContains: []
          replacements: {old: new}
      """;

  @Test
  void yamlAndProgrammaticPoliciesUseSameDecisionAndEffectiveValue() {
    var yaml = StructuredPolicy.fromYaml(YAML);
    var java =
        StructuredPolicy.builder("data-test")
            .dataLimits(new DataLimits(1, 10))
            .resource(
                "knowledge",
                new DataRule(
                    DataResource.Kind.RETRIEVAL,
                    DataRule.Scope.TENANT,
                    Map.of(DataOperation.RETRIEVE, Set.of("read"))))
            .content(
                new ContentRule(
                    "rewrite",
                    Set.of(InterventionPoint.PRE_DATA_ACCESS),
                    List.of(),
                    Map.of("old", "new")))
            .build();
    var request =
        new PolicyRequest(
            InterventionPoint.PRE_DATA_ACCESS,
            CTX,
            "session",
            "action",
            null,
            SOURCE.target(DataOperation.RETRIEVE, "old"));
    assertEquals(java.evaluate(request), yaml.evaluate(request));
    assertEquals("new", SOURCE.value(yaml.evaluate(request).replacement(), DataOperation.RETRIEVE));
  }

  @Test
  void invalidResourcesAndUnknownActionsFailAtLoad() {
    for (String invalid :
        List.of(
            YAML.replace("RETRIEVE: [read]", "DELETE_ALL: [read]"),
            YAML.replace("RETRIEVE: [read]", "MEMORY_READ: [read]"),
            YAML.replace("TENANT", "UNKNOWN"),
            YAML.replace("RETRIEVE: [read]", "RETRIEVE: []"),
            YAML.replace("maxCalls: 1", "maxCalls: 0"),
            YAML.replace("scope: TENANT", "scope: TENANT\n    expression: 'permitAll()'"),
            YAML.replace(", pre_data_access, post_data_access", ""))) {
      assertEquals(
          "invalid_policy",
          assertThrows(GovernanceException.class, () -> StructuredPolicy.fromYaml(invalid)).code());
    }
  }

  @Test
  void absentResourceOrOperationDeniedWithZeroEffects() {
    var p = StructuredPolicy.builder("deny").points(EnumSet.allOf(InterventionPoint.class)).build();
    try (var control = AgentControl.builder(p).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var effects = new AtomicInteger();
      assertEquals(
          "data_resource_denied",
          assertThrows(
                  GovernanceException.class,
                  () ->
                      session.dataOperationPrepared(
                          SOURCE,
                          DataOperation.RETRIEVE,
                          "q",
                          v -> v,
                          v -> {
                            effects.incrementAndGet();
                            return "x";
                          },
                          v -> v,
                          (v, e) -> (String) e))
              .code());
      assertEquals(0, effects.get());
      assertEquals(0, session.budgetSnapshot().dataCalls());
    }
  }

  @Test
  void failedActualDataIoStillCountsAndAsyncDoesNotDoubleCount() throws Exception {
    var p = StructuredPolicy.fromYaml(YAML);
    try (var control = AgentControl.builder(p).audit(r -> {}).build();
        var session = control.openSession(CTX)) {
      var result =
          session.dataOperationAsyncPrepared(
              SOURCE,
              DataOperation.RETRIEVE,
              "q",
              v -> v,
              v -> CompletableFuture.failedFuture(new GovernanceException("storage_unavailable")),
              v -> v,
              (v, e) -> e);
      assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
      assertEquals(1, session.budgetSnapshot().dataCalls());
      assertEquals(0, session.budgetSnapshot().modelCalls());
      assertEquals(0, session.budgetSnapshot().toolCalls());
    }
  }

  @Test
  void observingModeRecordsWithoutRewritingOrBlocking() {
    var identity = new TrustedContext("agent", "tenant-b", "bob", Set.of());
    try (var control =
            AgentControl.builder(StructuredPolicy.fromYaml(YAML))
                .mode(EnforcementMode.EVALUATE_ONLY)
                .audit(r -> {})
                .build();
        var session = control.openSession(identity)) {
      String result =
          session.dataOperationPrepared(
              SOURCE,
              DataOperation.RETRIEVE,
              "old",
              v -> (String) v,
              v -> v,
              v -> v,
              (v, e) -> (String) e);
      assertEquals("old", result);
      assertEquals(1, session.budgetSnapshot().dataCalls());
    }
  }
}
