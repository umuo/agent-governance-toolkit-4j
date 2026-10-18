package io.agt.core.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.GovernanceException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExternalExecutionStateTest {
    static final class Time extends Clock {
        final AtomicLong value = new AtomicLong(1000000);

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(value.get());
        }

        public long millis() {
            return value.get();
        }
    }

    @Test
    void timeoutSurvivesRestoreAndLateReceiptDoesNotReviveRoot() {
        var time = new Time();
        var state = new ExternalExecutionState("task", time);
        state.register("work", "session", "REMOTE_JOB");
        state.start("work", "session");
        state.cancel("work", "session");
        var restored = ExternalExecutionState.restored(state.checkpoint(), time);
        assertEquals(1, restored.summary().pendingCancellation());
        time.value.addAndGet(2001);
        assertEquals(1, restored.summary().unknown());
        assertEquals(1, restored.summary().active());
        var again = ExternalExecutionState.restored(restored.checkpoint(), time);
        again.finish("work", "session", TerminationEvidence.CONFIRMED);
        assertTrue(again.summary().allTerminationConfirmed());
        assertEquals(0, again.summary().active());
        assertEquals("external_execution_unconfirmed", again.summary().fault());
        assertThrows(GovernanceException.class, again::checkActive);
    }

    @Test
    void ownershipAndReceiptStagesCannotBeConfused() {
        var state = new ExternalExecutionState("task", new Time());
        state.register("work", "session", "REMOTE_JOB");
        assertThrows(GovernanceException.class, () -> state.start("work", "other"));
        assertThrows(
                GovernanceException.class,
                () -> state.finish("work", "session", TerminationEvidence.CONFIRMED));
        state.cancel("work", "session");
        assertEquals(TerminationEvidence.NOT_STARTED, state.snapshot().entries().get(0).evidence());
        assertThrows(GovernanceException.class, () -> state.start("work", "session"));
        assertThrows(
                GovernanceException.class, () -> state.register("work", "session", "OTHER_KIND"));
    }

    @Test
    void boundedPagesAreCompleteAndConcurrentMutationCannotProduceMixedSnapshot() {
        var state = new ExternalExecutionState("task", new Time());
        for (int i = 0; i < 257; i++) {
            state.register("work-" + i, "session", "REMOTE_JOB");
            state.finish("work-" + i, "session", TerminationEvidence.NOT_STARTED);
        }
        var summary = state.summary();
        assertEquals(257, summary.records());
        assertEquals(128, state.page(summary.revision(), 0).rows().size());
        assertEquals(128, state.page(summary.revision(), 128).rows().size());
        assertEquals(1, state.page(summary.revision(), 256).rows().size());
        state.register("new", "session", "REMOTE_JOB");
        assertEquals(
                "external_execution_snapshot_changed",
                assertThrows(GovernanceException.class, () -> state.page(summary.revision(), 128))
                        .code());
    }

    @Test
    void invalidCheckpointAndFabricatedSettledCountsCannotClearRunningWork() {
        var state = new ExternalExecutionState("task", new Time());
        state.register("work", "session", "REMOTE_JOB");
        state.start("work", "session");
        var actual = state.snapshot();
        var fake =
                new ExternalExecutionRegistry.Snapshot(
                        "task", actual.entries(), 0, 0, 0, false, null);
        assertThrows(GovernanceException.class, () -> ExternalExecutionState.settled(fake));
        var row = state.checkpoint().rows().get(0);
        var invalid =
                new ExternalExecutionState.Checkpoint(
                        "task", 2, null, List.of(new ExternalExecutionState.Row(row.entry(), -1)));
        assertThrows(
                GovernanceException.class,
                () -> ExternalExecutionState.restored(invalid, new Time()));
    }
}
