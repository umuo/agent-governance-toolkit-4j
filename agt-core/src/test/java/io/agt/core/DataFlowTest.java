package io.agt.core;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.data.*;
import io.agt.core.flow.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DataFlowTest {
    private static final TrustedContext IDENTITY =
            new TrustedContext("agent", "tenant", "user", Set.of("read", "send"));
    private static final DataResource SECRET_MEMORY =
            new DataResource(
                    "memory", DataResource.Kind.MEMORY, "tenant", "user", "secret-partition");
    private static final DataResource PUBLIC_MEMORY =
            new DataResource(
                    "memory", DataResource.Kind.MEMORY, "tenant", "user", "public-partition");

    private AgentControl runtime() {
        return AgentControl.builder(
                        new PolicyEvaluator() {
                            public String version() {
                                return "flow-test";
                            }

                            public Set<InterventionPoint> interventionPoints() {
                                return EnumSet.allOf(InterventionPoint.class);
                            }

                            public PolicyDecision evaluate(PolicyRequest request) {
                                return PolicyDecision.allow();
                            }
                        })
                .audit(record -> {})
                .build();
    }

    private DataFlowPolicy policy(DataLabel model, DataLabel output) {
        return new DataFlowPolicy(
                Map.of(SECRET_MEMORY, DataLabel.CONFIDENTIAL, PUBLIC_MEMORY, DataLabel.PUBLIC),
                Map.of("fetch", DataLabel.CONFIDENTIAL),
                Map.of(
                        "send",
                        new DataFlowPolicy.SinkRule(
                                DataLabel.SECRET,
                                "destination",
                                Map.of("internal", DataLabel.SECRET, "external", DataLabel.PUBLIC),
                                Set.of("review"))),
                Set.of("review"),
                DataLabel.PUBLIC,
                model,
                output);
    }

    private HostSession root(AgentControl control, DataFlowPolicy policy) {
        return control.openTaskSession(
                IDENTITY, new TaskLimits(new BudgetLimits(20, 30, 60000), 20, 20, 4, policy));
    }

    private String tool(
            HostSession session,
            String name,
            Map<String, Object> arguments,
            AtomicInteger effects) {
        return session.operation(
                true,
                name,
                arguments,
                input -> {
                    effects.incrementAndGet();
                    return "ok";
                },
                value -> value,
                (original, value) -> (String) value);
    }

    @Test
    void unclassifiedToolAndUnknownDestinationCannotBypassFlowRegistry() {
        try (var control = runtime();
                var root = root(control, policy(DataLabel.SECRET, DataLabel.SECRET));
                var unknown = control.openDelegatedSession(root, IDENTITY);
                var destination = control.openDelegatedSession(root, IDENTITY)) {
            var effects = new AtomicInteger();
            assertEquals(
                    "data_flow_tool_unclassified_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> tool(unknown, "newOutboundAlias", Map.of(), effects))
                            .code());
            assertEquals(
                    "data_flow_destination_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            tool(
                                                    destination,
                                                    "send",
                                                    Map.of("destination", "arbitrary-url"),
                                                    effects))
                            .code());
            assertEquals(0, effects.get());
            assertEquals(0, root.taskSnapshot().toolCalls());
        }
    }

    @Test
    void secretReadInOneChildBlocksExternalSendInAnotherButAllowsApprovedInternalSink() {
        try (var control = runtime();
                var root = root(control, policy(DataLabel.SECRET, DataLabel.SECRET));
                var reader = control.openDelegatedSession(root, IDENTITY);
                var sender = control.openDelegatedSession(root, IDENTITY);
                var internal = control.openDelegatedSession(root, IDENTITY)) {
            var effects = new AtomicInteger();
            tool(reader, "fetch", Map.of(), effects);
            tool(reader, "review", Map.of(), effects);
            reader.close();
            assertEquals(DataLabel.CONFIDENTIAL, root.dataFlowSnapshot().label());
            assertEquals(
                    "data_flow_egress_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            tool(
                                                    sender,
                                                    "send",
                                                    Map.of("destination", "external"),
                                                    effects))
                            .code());
            assertEquals("ok", tool(internal, "send", Map.of("destination", "internal"), effects));
            assertEquals(3, effects.get());
            assertEquals(3, root.taskSnapshot().toolCalls());
        }
    }

    @Test
    void toolSequenceRequiresAuditedSuccessfulPredecessor() throws Exception {
        try (var control = runtime();
                var root = root(control, policy(DataLabel.SECRET, DataLabel.SECRET));
                var early = control.openDelegatedSession(root, IDENTITY);
                var reviewer = control.openDelegatedSession(root, IDENTITY);
                var sender = control.openDelegatedSession(root, IDENTITY)) {
            var effects = new AtomicInteger();
            assertEquals(
                    "data_flow_prerequisite_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            tool(
                                                    early,
                                                    "send",
                                                    Map.of("destination", "external"),
                                                    effects))
                            .code());
            assertEquals(
                    "ok",
                    reviewer.operationAsyncPrepared(
                                    true,
                                    "review",
                                    Map.of(),
                                    Function.identity(),
                                    input -> {
                                        effects.incrementAndGet();
                                        return CompletableFuture.completedFuture("ok");
                                    },
                                    value -> value,
                                    (original, value) -> (String) value)
                            .get(3, TimeUnit.SECONDS));
            assertEquals("ok", tool(sender, "send", Map.of("destination", "external"), effects));
            assertTrue(root.dataFlowSnapshot().completedTools().contains("review"));
            assertEquals(2, effects.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void commitRechecksFlowAfterParallelReadChangesLabelDuringPreparation(boolean tool)
            throws Exception {
        try (var control = runtime();
                var root = root(control, policy(DataLabel.PUBLIC, DataLabel.SECRET));
                var reader = control.openDelegatedSession(root, IDENTITY);
                var sender = control.openDelegatedSession(root, IDENTITY)) {
            var effects = new AtomicInteger();
            tool(reader, "review", Map.of(), effects);
            var prepared = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var externalEffects = new AtomicInteger();
            var request =
                    sender.operationAsyncPrepared(
                            tool,
                            tool ? "send" : null,
                            Map.of("destination", "external"),
                            input -> {
                                prepared.countDown();
                                try {
                                    assertTrue(resume.await(3, TimeUnit.SECONDS));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException(interrupted);
                                }
                                return input;
                            },
                            input -> {
                                externalEffects.incrementAndGet();
                                return CompletableFuture.completedFuture("sent");
                            },
                            value -> value,
                            (original, value) -> (String) value);
            assertTrue(prepared.await(3, TimeUnit.SECONDS));
            try {
                tool(reader, "fetch", Map.of(), effects);
            } finally {
                resume.countDown();
            }
            var error =
                    assertThrows(ExecutionException.class, () -> request.get(3, TimeUnit.SECONDS));
            assertEquals(
                    tool ? "data_flow_egress_denied" : "data_flow_model_denied",
                    ((GovernanceException) error.getCause()).code());
            assertEquals(0, externalEffects.get());
            assertEquals(2, root.taskSnapshot().toolCalls());
        }
    }

    @Test
    void dataInstanceReadsTaintTaskAndWritesToLowerClassifiedStoreAreBlocked() {
        try (var control = runtime();
                var root = root(control, policy(DataLabel.SECRET, DataLabel.SECRET));
                var reader = control.openDelegatedSession(root, IDENTITY);
                var writer = control.openDelegatedSession(root, IDENTITY)) {
            var effects = new AtomicInteger();
            assertEquals(
                    "secret",
                    reader.dataOperationPrepared(
                            SECRET_MEMORY,
                            DataOperation.MEMORY_READ,
                            List.of(),
                            Function.identity(),
                            input -> {
                                effects.incrementAndGet();
                                return "secret";
                            },
                            value -> value,
                            (original, value) -> (String) value));
            assertEquals(
                    "data_flow_write_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            writer.dataOperationPrepared(
                                                    PUBLIC_MEMORY,
                                                    DataOperation.MEMORY_ADD,
                                                    "secret",
                                                    Function.identity(),
                                                    input -> {
                                                        effects.incrementAndGet();
                                                        return "bad";
                                                    },
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
            assertEquals(1, effects.get());
            assertEquals(1, root.taskSnapshot().dataCalls());
            assertTrue(
                    root.dataFlowSnapshot().sources().stream()
                            .anyMatch(source -> source.startsWith("data:sha256:")));
        }
    }

    @Test
    void lowClearanceModelAndFinalOutputCannotReceiveSensitiveContext() {
        try (var control = runtime();
                var root = root(control, policy(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var reader = control.openDelegatedSession(root, IDENTITY);
                var model = control.openDelegatedSession(root, IDENTITY);
                var output = control.openDelegatedSession(root, IDENTITY)) {
            var effects = new AtomicInteger();
            tool(reader, "fetch", Map.of(), effects);
            assertEquals(
                    "data_flow_model_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            model.operation(
                                                    false,
                                                    null,
                                                    "secret",
                                                    input -> {
                                                        effects.incrementAndGet();
                                                        return "bad";
                                                    },
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
            assertEquals(
                    "data_flow_output_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            output.disclose(
                                                    "secret",
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
            assertEquals(1, effects.get());
        }
    }

    @Test
    void failedSourceRemainsConservativelyTaintedButFailedReviewNeverUnlocksSink() {
        try (var control = runtime();
                var root = root(control, policy(DataLabel.SECRET, DataLabel.SECRET));
                var source = control.openDelegatedSession(root, IDENTITY);
                var review = control.openDelegatedSession(root, IDENTITY);
                var sender = control.openDelegatedSession(root, IDENTITY)) {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.operation(
                                    true,
                                    "fetch",
                                    Map.of(),
                                    input -> {
                                        throw new IllegalStateException();
                                    },
                                    value -> value,
                                    (original, value) -> value));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            review.operation(
                                    true,
                                    "review",
                                    Map.of(),
                                    input -> {
                                        throw new IllegalStateException();
                                    },
                                    value -> value,
                                    (original, value) -> value));
            assertEquals(DataLabel.CONFIDENTIAL, root.dataFlowSnapshot().label());
            assertFalse(root.dataFlowSnapshot().completedTools().contains("review"));
            assertEquals(
                    "data_flow_prerequisite_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            tool(
                                                    sender,
                                                    "send",
                                                    Map.of("destination", "internal"),
                                                    new AtomicInteger()))
                            .code());
        }
    }
}
