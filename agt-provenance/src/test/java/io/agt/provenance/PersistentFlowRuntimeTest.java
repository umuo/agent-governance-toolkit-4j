package io.agt.provenance;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.data.*;
import io.agt.core.flow.*;
import io.agt.core.policy.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PersistentFlowRuntimeTest {
    @Test
    void knownSensitiveClassificationPersistsEvenWithoutADataWrite() {
        var labels = store(true);
        String classifier;
        try (var control = control(labels);
                var root =
                        control.openTaskSession(
                                ID,
                                limits(
                                        DataLabel.CONFIDENTIAL,
                                        DataLabel.PUBLIC,
                                        DataLabel.SECRET))) {
            classifier = root.taskSnapshot().taskId();
            assertEquals("existing-sensitive-data", read(root, () -> "existing-sensitive-data"));
            var event = labels.history(MEMORY, 1).orElseThrow();
            assertEquals(LabelProvenance.Kind.CLASSIFICATION, event.provenance().kind());
            assertEquals(classifier, event.provenance().taskId());
            assertNotEquals("flow-check", event.provenance().actionId());
            assertEquals(List.of("host-policy"), event.provenance().sources());
        }
        try (var control = control(store(false));
                var root =
                        control.openTaskSession(
                                ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC, DataLabel.PUBLIC))) {
            assertEquals(
                    "data_flow_output_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            root.run(
                                                    "request",
                                                    value ->
                                                            read(
                                                                    root,
                                                                    () ->
                                                                            "existing-sensitive-data"),
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
            assertEquals(classifier, labels.history(MEMORY, 1).orElseThrow().provenance().taskId());
            assertTrue(labels.history(MEMORY, 2).isEmpty());
        }
    }

    @Test
    void lowInputWriteStillPreservesTheDestinationsDeclaredBaseline() {
        var labels = store(true);
        try (var control = control(labels);
                var root =
                        control.openTaskSession(
                                ID,
                                limits(
                                        DataLabel.CONFIDENTIAL,
                                        DataLabel.PUBLIC,
                                        DataLabel.PUBLIC))) {
            root.dataOperationPrepared(
                    MEMORY,
                    DataOperation.MEMORY_ADD,
                    "public-addition",
                    value -> value,
                    value -> "written",
                    value -> value,
                    (original, value) -> (String) value);
            assertEquals(DataLabel.PUBLIC, root.dataFlowSnapshot().label());
            assertEquals(DataLabel.CONFIDENTIAL, labels.read(MEMORY).orElseThrow().label());
            assertEquals(
                    LabelProvenance.Kind.WRITE,
                    labels.history(MEMORY, 1).orElseThrow().provenance().kind());
        }
    }

    @Test
    void directoryResetDuringNativeReadDoesNotEraseAnAlreadyObservedVersion() {
        var labels = store(true);
        labels.record(
                MEMORY,
                DataLabel.CONFIDENTIAL,
                new LabelProvenance("producer", "write", List.of("host-input")));
        try (var control = control(labels);
                var root =
                        control.openTaskSession(
                                ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC, DataLabel.SECRET))) {
            assertEquals(
                    "label_store_integrity_failed",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            read(
                                                    root,
                                                    () -> {
                                                        var db = new JdbcDataSource();
                                                        db.setURL(
                                                                "jdbc:h2:file:"
                                                                        + directory.resolve(
                                                                                "labels"));
                                                        try (var connection = db.getConnection();
                                                                var statement =
                                                                        connection
                                                                                .createStatement()) {
                                                            statement.executeUpdate(
                                                                    "DELETE FROM agt_label_origin");
                                                            statement.executeUpdate(
                                                                    "DELETE FROM agt_resource_label");
                                                        } catch (java.sql.SQLException failed) {
                                                            throw new IllegalStateException(
                                                                    "fixture-reset-failed");
                                                        }
                                                        return "fixture-read";
                                                    }))
                            .code());
        }
    }

    @TempDir Path directory;
    static final TrustedContext ID =
            new TrustedContext("agent", "tenant", "user", Set.of("data", "send"));
    static final DataResource MEMORY =
            new DataResource("memory", DataResource.Kind.MEMORY, "tenant", "user", "stable-memory");
    static final BudgetLimits CALLS = new BudgetLimits(10, 10, 60000);

    JdbcLabelStore store(boolean initialize) {
        var source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:file:" + directory.resolve("labels") + ";WRITE_DELAY=0;LOCK_TIMEOUT=2000");
        var store = new JdbcLabelStore(source, 2);
        if (initialize) store.initializeSchema();
        return store;
    }

    AgentControl control(LabelStore store) {
        var policy =
                StructuredPolicy.builder("persisted-flow")
                        .budget(CALLS)
                        .resource(
                                "memory",
                                new DataRule(
                                        DataResource.Kind.MEMORY,
                                        DataRule.Scope.PRINCIPAL,
                                        Map.of(
                                                DataOperation.MEMORY_READ,
                                                Set.of("data"),
                                                DataOperation.MEMORY_ADD,
                                                Set.of("data"))))
                        .tool(
                                "send",
                                new ToolRule(Set.of("send"), ParameterRule.object(Map.of()), false))
                        .build();
        return AgentControl.builder(policy).labelStore(store).audit(record -> {}).build();
    }

    TaskLimits limits(DataLabel resource, DataLabel input, DataLabel output) {
        var flow =
                new DataFlowPolicy(
                        Map.of(MEMORY, resource),
                        Map.of(),
                        Map.of(
                                "send",
                                new DataFlowPolicy.SinkRule(
                                        DataLabel.PUBLIC, null, Map.of(), Set.of())),
                        Set.of(),
                        input,
                        DataLabel.PUBLIC,
                        output);
        return new TaskLimits(CALLS, 10, 8, 2, flow);
    }

    String read(HostSession session, java.util.function.Supplier<String> nativeRead) {
        return session.dataOperationPrepared(
                MEMORY,
                DataOperation.MEMORY_READ,
                Map.of(),
                value -> value,
                value -> nativeRead.get(),
                value -> value,
                (original, value) -> (String) value);
    }

    @Test
    void reconstructedTaskCannotTreatPersistedSensitiveMemoryAsPublic() {
        var writerStore = store(true);
        var memory = new AtomicReference<String>();
        try (var control = control(writerStore);
                var writer =
                        control.openTaskSession(
                                ID,
                                limits(
                                        DataLabel.CONFIDENTIAL,
                                        DataLabel.CONFIDENTIAL,
                                        DataLabel.CONFIDENTIAL))) {
            writer.dataOperationPrepared(
                    MEMORY,
                    DataOperation.MEMORY_ADD,
                    "fixture-confidential",
                    value -> value,
                    value -> {
                        memory.set((String) value);
                        return "written";
                    },
                    value -> value,
                    (original, value) -> (String) value);
        }
        var reopened = store(false);
        try (var control = control(reopened);
                var root =
                        control.openTaskSession(
                                ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC, DataLabel.PUBLIC));
                var reader = control.openDelegatedSession(root, ID);
                var model = control.openDelegatedSession(root, ID);
                var sender = control.openDelegatedSession(root, ID);
                var output = control.openDelegatedSession(root, ID)) {
            assertEquals("fixture-confidential", read(reader, memory::get));
            assertEquals(DataLabel.CONFIDENTIAL, root.dataFlowSnapshot().label());
            assertTrue(
                    root.dataFlowSnapshot().sources().stream()
                            .anyMatch(
                                    source ->
                                            source.startsWith("stored:") && source.endsWith(":1")));
            var effects = new AtomicInteger();
            assertEquals(
                    "data_flow_model_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            model.operation(
                                                    false,
                                                    null,
                                                    "request",
                                                    value -> {
                                                        effects.incrementAndGet();
                                                        return "response";
                                                    },
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
            assertEquals(
                    "data_flow_egress_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            sender.operation(
                                                    true,
                                                    "send",
                                                    Map.of(),
                                                    value -> {
                                                        effects.incrementAndGet();
                                                        return "sent";
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
                                                    "fixture-confidential",
                                                    value -> value,
                                                    (original, value) -> (String) value))
                            .code());
            assertEquals(0, effects.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void postReadRecheckCatchesSensitiveWriteDuringNativeReadBeforeOutput(boolean async)
            throws Exception {
        var store = store(true);
        try (var control = control(store);
                var root =
                        control.openTaskSession(
                                ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC, DataLabel.PUBLIC))) {
            java.util.function.Supplier<String> read =
                    () -> {
                        store.record(
                                MEMORY,
                                DataLabel.CONFIDENTIAL,
                                new LabelProvenance(
                                        "other-task", "concurrent-write", List.of("host-input")));
                        return "fixture-sensitive-race";
                    };
            if (async) {
                var result =
                        root.runAsync(
                                "request",
                                value ->
                                        root.dataOperationAsyncPrepared(
                                                MEMORY,
                                                DataOperation.MEMORY_READ,
                                                Map.of(),
                                                target -> target,
                                                target ->
                                                        CompletableFuture.completedFuture(
                                                                read.get()),
                                                target -> target,
                                                (original, target) -> (String) target),
                                value -> value,
                                (original, value) -> (String) value);
                assertEquals(
                        "data_flow_output_denied",
                        ((GovernanceException)
                                        assertThrows(
                                                        ExecutionException.class,
                                                        () -> result.get(5, TimeUnit.SECONDS))
                                                .getCause())
                                .code());
            } else {
                assertEquals(
                        "data_flow_output_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                root.run(
                                                        "request",
                                                        value -> read(root, read),
                                                        value -> value,
                                                        (original, value) -> (String) value))
                                .code());
            }
            assertEquals(DataLabel.CONFIDENTIAL, root.dataFlowSnapshot().label());
        }
    }

    @Test
    void uncertainNativeWriteKeepsConservativeLabelButPrepareFailureDoesNotCreateOne() {
        var store = store(true);
        var effects = new AtomicInteger();
        try (var control = control(store);
                var root =
                        control.openTaskSession(
                                ID,
                                limits(
                                        DataLabel.CONFIDENTIAL,
                                        DataLabel.CONFIDENTIAL,
                                        DataLabel.CONFIDENTIAL))) {
            assertThrows(
                    GovernanceException.class,
                    () ->
                            root.dataOperationPrepared(
                                    MEMORY,
                                    DataOperation.MEMORY_ADD,
                                    "fixture",
                                    value -> {
                                        throw new GovernanceException("prepare_failed");
                                    },
                                    value -> {
                                        effects.incrementAndGet();
                                        return "written";
                                    },
                                    value -> value,
                                    (original, value) -> (String) value));
            assertTrue(store.read(MEMORY).isEmpty());
            assertEquals(0, effects.get());
        }
        try (var control = control(store);
                var root =
                        control.openTaskSession(
                                ID,
                                limits(
                                        DataLabel.CONFIDENTIAL,
                                        DataLabel.CONFIDENTIAL,
                                        DataLabel.CONFIDENTIAL))) {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            root.dataOperationPrepared(
                                    MEMORY,
                                    DataOperation.MEMORY_ADD,
                                    "fixture",
                                    value -> value,
                                    value -> {
                                        effects.incrementAndGet();
                                        throw new IllegalStateException("fixture-write-failed");
                                    },
                                    value -> value,
                                    (original, value) -> value));
            assertEquals(DataLabel.CONFIDENTIAL, store.read(MEMORY).orElseThrow().label());
            assertEquals(1, effects.get());
        }
    }

    @Test
    void unavailableCatalogStopsReadAndWriteBeforeNativeExecution() {
        var unavailable = store(false);
        var effects = new AtomicInteger();
        for (boolean write : List.of(false, true)) {
            try (var control = control(unavailable);
                    var root =
                            control.openTaskSession(
                                    ID,
                                    limits(
                                            DataLabel.CONFIDENTIAL,
                                            DataLabel.CONFIDENTIAL,
                                            DataLabel.CONFIDENTIAL))) {
                assertEquals(
                        "label_store_unavailable",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                root.dataOperationPrepared(
                                                        MEMORY,
                                                        write
                                                                ? DataOperation.MEMORY_ADD
                                                                : DataOperation.MEMORY_READ,
                                                        Map.of(),
                                                        value -> value,
                                                        value -> {
                                                            effects.incrementAndGet();
                                                            return "native";
                                                        },
                                                        value -> value,
                                                        (original, value) -> (String) value))
                                .code());
            }
        }
        assertEquals(0, effects.get());
    }

    @Test
    void configuredCatalogCannotBeSilentlySkippedWithoutAnExplicitFlowPolicy() {
        var effects = new AtomicInteger();
        try (var control = control(store(true));
                var root = control.openTaskSession(ID, new TaskLimits(CALLS, 10, 8, 2))) {
            assertEquals(
                    "data_flow_policy_required",
                    assertThrows(GovernanceException.class, root::exportDelegationState).code());
            assertEquals(
                    "data_flow_policy_required",
                    assertThrows(GovernanceException.class, root::exportDelegationQuota).code());
            assertEquals(
                    "data_flow_policy_required",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            read(
                                                    root,
                                                    () -> {
                                                        effects.incrementAndGet();
                                                        return "unguarded";
                                                    }))
                            .code());
            assertEquals(0, effects.get());
            assertEquals(0, root.taskSnapshot().dataCalls());
        }
    }
}
