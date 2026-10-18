package io.agt.delegation;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.flow.*;
import io.agt.core.usage.UsageBudget;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;

class GrantStatusServiceTest {
    @Test
    void flowSnapshotAcceptsAllConfiguredPrerequisiteToolsBeyondOneRegistryCategory() {
        var neutral = new HashSet<String>();
        for (int i = 0; i < 128; i++) neutral.add("step-" + i);
        var flow =
                new DataFlowState(
                        new DataFlowPolicy(
                                Map.of(),
                                Map.of("source-step", DataLabel.PUBLIC),
                                Map.of(
                                        "sink-a",
                                        new DataFlowPolicy.SinkRule(
                                                DataLabel.PUBLIC, null, Map.of(), neutral),
                                        "sink-b",
                                        new DataFlowPolicy.SinkRule(
                                                DataLabel.PUBLIC,
                                                null,
                                                Map.of(),
                                                Set.of("source-step"))),
                                neutral,
                                DataLabel.PUBLIC,
                                DataLabel.PUBLIC,
                                DataLabel.PUBLIC));
        neutral.forEach(flow::completedTool);
        flow.completedTool("source-step");
        assertEquals(129, flow.snapshot().completedTools().size());
        assertEquals(
                flow.snapshot(),
                GrantCodec.decodeFlowSnapshot(GrantCodec.encodeFlowSnapshot(flow.snapshot())));
    }

    @Test
    void snapshotCannotCoerceNullOrStringCountersIntoZeroOrInventACurrency() {
        var snapshot =
                new UsageBudget.Snapshot(
                        10,
                        new BigDecimal("0.01"),
                        20,
                        new BigDecimal("0.02"),
                        1,
                        0,
                        100,
                        BigDecimal.ONE,
                        "USD",
                        null);
        String encoded = GrantCodec.encodeUsageSnapshot(snapshot);
        assertEquals(snapshot, GrantCodec.decodeUsageSnapshot(encoded));
        for (var field :
                List.of(
                        "settledTokens",
                        "heldTokens",
                        "inFlightCalls",
                        "uncertainCalls",
                        "maxTokens",
                        "settledCost",
                        "heldCost",
                        "maxCost",
                        "currency")) {
            for (Object invalid : Arrays.asList(null, "0", true, List.of())) {
                var value = new LinkedHashMap<>(JsonValues.parseObject(encoded));
                value.put(field, invalid);
                // 文本货币可以是标识符，其他类型不能被隐式转为文本。
                if (field.equals("currency") && invalid instanceof String) continue;
                assertEquals(
                        "delegation_document_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                GrantCodec.decodeUsageSnapshot(
                                                        JsonValues.write(value)))
                                .code());
            }
        }
    }

    @Test
    void receiverProtocolCannotExposeAuthorityRootOperationsOrIgnoreExtraFields() {
        var service = new GrantStatusService(new InMemoryGrantRegistry(Clock.systemUTC()));
        var caller = new AuthenticatedAgent("reader");
        for (String op : List.of("registerRoot", "revoke", "heartbeatRoot", "issue", "parent"))
            assertEquals(400, service.handle(caller, JsonValues.write(Map.of("op", op))).status());
        assertEquals(
                400, service.handle(caller, "{\"op\":\"check\",\"op\":\"complete\"}").status());
        assertEquals(
                400,
                service.handle(
                                caller,
                                JsonValues.write(
                                        Map.of(
                                                "op",
                                                "check",
                                                "lease",
                                                Map.of(
                                                        "grantId",
                                                        "grant",
                                                        "secret",
                                                        "A".repeat(43)),
                                                "sessionId",
                                                "session",
                                                "ignorePolicy",
                                                true)))
                        .status());
    }
}
