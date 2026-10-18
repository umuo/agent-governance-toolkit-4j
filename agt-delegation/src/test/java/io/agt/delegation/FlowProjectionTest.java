package io.agt.delegation;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.data.*;
import io.agt.core.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowProjectionTest {
    @Test
    void typedVersionProjectionSurvivesStrictWireNumbersButRejectsAdditionalBodyFields() {
        var resource =
                new DataResource(
                        "docs", DataResource.Kind.RETRIEVAL, "tenant", "user", "partition");
        var policy =
                new DataFlowPolicy(
                        Map.of(resource, DataLabel.PUBLIC),
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        DataLabel.PUBLIC,
                        DataLabel.PUBLIC,
                        DataLabel.PUBLIC);
        var fragment =
                DataFragment.document("synthetic", new DocumentSegment("document", 1, "chunk"));
        var full =
                DataFragment.target(
                        resource,
                        DataOperation.RETRIEVE,
                        "private-synthetic-body",
                        List.of(fragment));
        var projection =
                FlowProjection.target(policy, InterventionPoint.POST_DATA_ACCESS, null, full);
        var decoded = JsonValues.parse(JsonValues.write(projection));
        assertDoesNotThrow(
                () ->
                        FlowProjection.require(
                                policy, InterventionPoint.POST_DATA_ACCESS, null, decoded));
        assertFalse(JsonValues.write(projection).contains("private-synthetic-body"));
        var extra = new LinkedHashMap<Object, Object>((Map<?, ?>) decoded);
        extra.put("body", "unexpected");
        assertThrows(
                GovernanceException.class,
                () ->
                        FlowProjection.require(
                                policy, InterventionPoint.POST_DATA_ACCESS, null, extra));
    }
}
