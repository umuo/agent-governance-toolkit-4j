package io.agt.core;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.data.*;
import io.agt.core.delegation.DelegationScope;
import io.agt.core.policy.ParameterRule;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class DelegationScopeTest {
    private static final TrustedContext IDENTITY =
            new TrustedContext("agent", "tenant", "user", Set.of("read"));
    private static final TaskLimits LIMITS =
            new TaskLimits(new BudgetLimits(20, 20, 60000), 20, 10, 4);
    private static final DataResource MEMORY =
            new DataResource(
                    "memory", DataResource.Kind.MEMORY, "tenant", "user", "conversation-a");

    private AgentControl runtime(
            Function<PolicyRequest, PolicyDecision> decisions, EnforcementMode mode) {
        return AgentControl.builder(
                        new PolicyEvaluator() {
                            public String version() {
                                return "scope-test";
                            }

                            public Set<InterventionPoint> interventionPoints() {
                                return EnumSet.allOf(InterventionPoint.class);
                            }

                            public PolicyDecision evaluate(PolicyRequest request) {
                                return decisions.apply(request);
                            }
                        })
                .mode(mode)
                .audit(record -> {})
                .build();
    }

    private AgentControl runtime() {
        return runtime(request -> PolicyDecision.allow(), EnforcementMode.ENFORCE);
    }

    private ParameterRule arguments(String... resources) {
        return ParameterRule.object(
                Map.of(
                        "resource",
                        new ParameterRule(
                                ParameterRule.Type.STRING,
                                null,
                                null,
                                new ArrayList<>(List.of(resources)),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)),
                "resource");
    }

    private DelegationScope scope(
            boolean model, ParameterRule arguments, Set<DataOperation> operations) {
        return new DelegationScope(
                "summarize-customer",
                model,
                arguments == null ? Map.of() : Map.of("lookup", arguments),
                operations.isEmpty() ? Map.of() : Map.of(MEMORY, operations));
    }

    private String tool(HostSession session, String resource, AtomicInteger effects) {
        return session.operation(
                true,
                "lookup",
                Map.of("resource", resource),
                args -> {
                    effects.incrementAndGet();
                    return "ok";
                },
                value -> value,
                (original, value) -> (String) value);
    }

    @Test
    void descendantCannotWidenAncestorParameterRulesEvenWithPermissivePolicy() {
        try (var control = runtime();
                var root =
                        control.openTaskSession(
                                IDENTITY,
                                LIMITS,
                                null,
                                scope(true, arguments("customer-a"), Set.of()));
                var child =
                        control.openDelegatedSession(
                                root,
                                IDENTITY,
                                null,
                                scope(true, arguments("customer-a", "customer-b"), Set.of()));
                var allowed = control.openDelegatedSession(child, IDENTITY);
                var denied = control.openDelegatedSession(child, IDENTITY)) {
            var effects = new AtomicInteger();
            assertEquals("ok", tool(allowed, "customer-a", effects));
            assertEquals(
                    "delegation_arguments_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> tool(denied, "customer-b", effects))
                            .code());
            assertEquals(1, effects.get());
            assertEquals(1, root.taskSnapshot().toolCalls());
            root.checkActive();
        }
    }

    @Test
    void parameterRewriteCannotEscapeDelegationAndNeverExecutes() {
        try (var control =
                        runtime(
                                request ->
                                        request.point() == InterventionPoint.PRE_TOOL_CALL
                                                ? PolicyDecision.transform(
                                                        "rewrite", Map.of("resource", "customer-b"))
                                                : PolicyDecision.allow(),
                                EnforcementMode.ENFORCE);
                var root =
                        control.openTaskSession(
                                IDENTITY,
                                LIMITS,
                                null,
                                scope(true, arguments("customer-a"), Set.of()));
                var child = control.openDelegatedSession(root, IDENTITY)) {
            var effects = new AtomicInteger();
            assertEquals(
                    "delegation_arguments_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> tool(child, "customer-a", effects))
                            .code());
            assertEquals(0, effects.get());
            assertEquals(0, root.taskSnapshot().toolCalls());
        }
    }

    @Test
    void capabilitiesCannotBeAddedOrReassignedToDifferentTask() {
        var initial = scope(false, null, Set.of(DataOperation.MEMORY_READ));
        try (var control = runtime();
                var root = control.openTaskSession(IDENTITY, LIMITS, null, initial)) {
            var another =
                    new DataResource(
                            "memory", DataResource.Kind.MEMORY, "tenant", "user", "conversation-b");
            for (var expanded :
                    List.of(
                            scope(true, null, Set.of(DataOperation.MEMORY_READ)),
                            scope(
                                    false,
                                    arguments("customer-a"),
                                    Set.of(DataOperation.MEMORY_READ)),
                            scope(
                                    false,
                                    null,
                                    Set.of(DataOperation.MEMORY_READ, DataOperation.MEMORY_CLEAR)),
                            new DelegationScope("other-task", false, Map.of(), Map.of()),
                            new DelegationScope(
                                    initial.taskName(),
                                    false,
                                    Map.of(),
                                    Map.of(another, Set.of(DataOperation.MEMORY_READ))))) {
                assertEquals(
                        "delegation_scope_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                control.openDelegatedSession(
                                                        root, IDENTITY, null, expanded))
                                .code());
            }
            assertEquals(1, root.taskSnapshot().activeSessions());
        }
    }

    @Test
    void dataAuthorizationBindsExactInstanceAndOperationForSyncAndAsync() throws Exception {
        try (var control = runtime();
                var root =
                        control.openTaskSession(
                                IDENTITY,
                                LIMITS,
                                null,
                                scope(false, null, Set.of(DataOperation.MEMORY_READ)));
                var allowed = control.openDelegatedSession(root, IDENTITY);
                var wrongInstance = control.openDelegatedSession(root, IDENTITY);
                var wrongOperation = control.openDelegatedSession(root, IDENTITY)) {
            var effects = new AtomicInteger();
            var value =
                    allowed.dataOperationAsyncPrepared(
                                    MEMORY,
                                    DataOperation.MEMORY_READ,
                                    List.of(),
                                    Function.identity(),
                                    input -> {
                                        effects.incrementAndGet();
                                        return CompletableFuture.completedFuture("memory");
                                    },
                                    result -> result,
                                    (original, effective) -> (String) effective)
                            .get(3, TimeUnit.SECONDS);
            assertEquals("memory", value);
            var another =
                    new DataResource(
                            "memory", DataResource.Kind.MEMORY, "tenant", "user", "conversation-b");
            assertEquals(
                    "delegation_resource_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            wrongInstance.dataOperationPrepared(
                                                    another,
                                                    DataOperation.MEMORY_READ,
                                                    List.of(),
                                                    Function.identity(),
                                                    input -> {
                                                        effects.incrementAndGet();
                                                        return "bad";
                                                    },
                                                    result -> result,
                                                    (original, effective) -> (String) effective))
                            .code());
            var failure =
                    wrongOperation.dataOperationAsyncPrepared(
                            MEMORY,
                            DataOperation.MEMORY_CLEAR,
                            List.of(),
                            Function.identity(),
                            input -> {
                                effects.incrementAndGet();
                                return CompletableFuture.completedFuture("bad");
                            },
                            result -> result,
                            (original, effective) -> (String) effective);
            var error =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> failure.get(3, TimeUnit.SECONDS));
            assertEquals(
                    "delegation_resource_denied", ((GovernanceException) error.getCause()).code());
            assertEquals(1, effects.get());
            assertEquals(1, root.taskSnapshot().dataCalls());
        }
    }

    @Test
    void contextualScopeBindingsRemainBoundToIssuerAfterAgentIdentityChanges() {
        var initial =
                scope(
                        true,
                        ParameterRule.object(
                                Map.of("resource", ParameterRule.string("agentId")), "resource"),
                        Set.of());
        var childIdentity = new TrustedContext("child-agent", "tenant", "user", Set.of("read"));
        try (var control = runtime();
                var root = control.openTaskSession(IDENTITY, LIMITS, null, initial);
                var allowed = control.openDelegatedSession(root, childIdentity);
                var denied = control.openDelegatedSession(root, childIdentity)) {
            var effects = new AtomicInteger();
            assertEquals("ok", tool(allowed, IDENTITY.agentId(), effects));
            assertEquals(
                    "delegation_arguments_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> tool(denied, childIdentity.agentId(), effects))
                            .code());
            assertEquals(1, effects.get());
        }
    }

    @Test
    void scopedAuthorityCannotBeDisabledByEvaluationOnlyMode() {
        try (var control =
                        runtime(request -> PolicyDecision.allow(), EnforcementMode.EVALUATE_ONLY);
                var root =
                        control.openTaskSession(
                                IDENTITY, LIMITS, null, scope(false, null, Set.of()));
                var child = control.openDelegatedSession(root, IDENTITY);
                var modelChild = control.openDelegatedSession(root, IDENTITY)) {
            var effects = new AtomicInteger();
            assertEquals(
                    "delegation_tool_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> tool(child, "customer-a", effects))
                            .code());
            assertEquals(
                    "delegation_model_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            modelChild.operation(
                                                    false,
                                                    null,
                                                    "input",
                                                    input -> {
                                                        effects.incrementAndGet();
                                                        return "bad";
                                                    },
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
            assertEquals(0, effects.get());
        }
    }
}
