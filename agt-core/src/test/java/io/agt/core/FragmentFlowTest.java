package io.agt.core;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.data.*;
import io.agt.core.flow.*;
import io.agt.core.policy.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FragmentFlowTest {
    static final DataResource SOURCE =
            new DataResource("docs", DataResource.Kind.RETRIEVAL, "tenant", "user", "partition");
    static final TrustedContext ID = new TrustedContext("agent", "tenant", "user", Set.of("read"));

    DataFlowPolicy flow() {
        return new DataFlowPolicy(
                Map.of(SOURCE, DataLabel.PUBLIC),
                Map.of(),
                Map.of(),
                Set.of(),
                DataLabel.PUBLIC,
                DataLabel.PUBLIC,
                DataLabel.PUBLIC);
    }

    @Test
    void checkpointRetainsUnstoredFragmentsAndRejectsUnknownOrReboundSources() {
        var state = new DataFlowState(flow(), "task", null);
        var fragment = DataFragment.document("synthetic");
        var target =
                DataFragment.target(SOURCE, DataOperation.RETRIEVE, "synthetic", List.of(fragment));
        state.beforeData("read", target);
        state.validate(
                new PolicyRequest(
                        InterventionPoint.POST_DATA_ACCESS, ID, "session", "read", null, target));
        var checkpoint = state.checkpoint();
        assertEquals(state.snapshot(), DataFlowState.restored(flow(), checkpoint, null).snapshot());
        assertEquals(1, checkpoint.fragments().size());
        var missing =
                new DataFlowState.Checkpoint(
                        checkpoint.taskId(),
                        checkpoint.snapshot(),
                        checkpoint.observed(),
                        null,
                        Map.of());
        assertThrows(
                GovernanceException.class, () -> DataFlowState.restored(flow(), missing, null));
        var other =
                new DataResource(
                        "docs", DataResource.Kind.RETRIEVAL, "tenant", "user", "other-partition");
        var observation = new DataFlowState.FragmentObservation(other, fragment, null);
        var rebound =
                new DataFlowState.Checkpoint(
                        checkpoint.taskId(),
                        checkpoint.snapshot(),
                        checkpoint.observed(),
                        null,
                        Map.of(fragment.key(SOURCE), observation));
        assertThrows(
                GovernanceException.class, () -> DataFlowState.restored(flow(), rebound, null));
    }

    @Test
    void fragmentProjectionDirectionFailsBeforeNativeReadAndCannotAddCallsToBudget() {
        var policy =
                StructuredPolicy.builder("fragments")
                        .resource(
                                "docs",
                                new DataRule(
                                        DataResource.Kind.RETRIEVAL,
                                        DataRule.Scope.PRINCIPAL,
                                        Map.of(DataOperation.RETRIEVE, Set.of("read"))))
                        .build();
        try (var control = AgentControl.builder(policy).audit(record -> {}).build();
                var task =
                        control.openTaskSession(
                                ID,
                                new TaskLimits(new BudgetLimits(2, 2, 60000), 2, 2, 1, flow()))) {
            var nativeCalls = new AtomicInteger();
            var wrong =
                    new FragmentProjection<Object, String>() {
                        public List<DataFragment> written(Object prepared) {
                            return List.of(DataFragment.document("synthetic"));
                        }
                    };
            assertEquals(
                    "data_fragment_direction_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            task.dataOperationPrepared(
                                                    SOURCE,
                                                    DataOperation.RETRIEVE,
                                                    "query",
                                                    value -> value,
                                                    value -> {
                                                        nativeCalls.incrementAndGet();
                                                        return "synthetic";
                                                    },
                                                    value -> value,
                                                    (original, value) -> (String) value,
                                                    wrong))
                            .code());
            assertEquals(0, nativeCalls.get());
            assertEquals(0, task.taskSnapshot().dataCalls());
        }
    }

    @Test
    void itemReorderingDoesNotChangeContentIdentityAndMetadataCannotRewriteReferences() {
        var fragment = DataFragment.document("synthetic");
        var target =
                DataFragment.target(
                        SOURCE, DataOperation.RETRIEVE, List.of("synthetic"), List.of(fragment));
        assertEquals(
                List.of("synthetic"),
                DataFragment.value(SOURCE, DataOperation.RETRIEVE, target, List.of(fragment)));
        var changed =
                DataFragment.target(
                        SOURCE,
                        DataOperation.RETRIEVE,
                        List.of("synthetic"),
                        List.of(DataFragment.document("other")));
        assertEquals(
                "data_fragment_transform_denied",
                assertThrows(
                                GovernanceException.class,
                                () ->
                                        DataFragment.value(
                                                SOURCE,
                                                DataOperation.RETRIEVE,
                                                changed,
                                                List.of(fragment)))
                        .code());
        assertThrows(
                GovernanceException.class,
                () -> DataFragment.message(Map.of("text", "synthetic")).key(SOURCE));
    }
}
