package io.agt.core;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.data.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DocumentFragmentTest {
    static final DataResource RESOURCE =
            new DataResource("docs", DataResource.Kind.RETRIEVAL, "tenant", "user", "partition");

    @Test
    void versionedIdentityRoundTripsWithoutChangingLegacyNamespace() {
        var legacy = DataFragment.document("body");
        var one = DataFragment.document("body", new DocumentSegment("doc", 1, "chunk"));
        var two = DataFragment.document("body", new DocumentSegment("doc", 2, "chunk"));
        assertEquals(legacy.digest(), one.digest());
        assertNotEquals(legacy.key(RESOURCE), one.key(RESOURCE));
        assertNotEquals(one.key(RESOURCE), two.key(RESOURCE));
        assertEquals(
                JsonValues.digest(
                        List.of("AGT-FRAGMENT-V1", RESOURCE.descriptor(), legacy.descriptor())),
                legacy.key(RESOURCE));
        var target = DataFragment.target(RESOURCE, DataOperation.RETRIEVE, "body", List.of(one));
        var parsed = JsonValues.parse(JsonValues.write(target));
        assertEquals(List.of(one), DataFragment.fromTarget(parsed, RESOURCE));
        assertThrows(
                GovernanceException.class,
                () -> DataFragment.value(RESOURCE, DataOperation.RETRIEVE, parsed, List.of(two)));
    }

    @Test
    void protocolRejectsUntrustedShapeVersionCoercionAndMemoryBinding() {
        var fragment = DataFragment.document("body", new DocumentSegment("doc", 1, "chunk"));
        for (var invalid : List.of("1", true, 1.5, -1, 0)) {
            var fields = new LinkedHashMap<>(fragment.descriptor());
            fields.put(
                    "document",
                    Map.of("documentId", "doc", "version", invalid, "segmentId", "chunk"));
            assertEquals(
                    "data_fragment_identity_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            DataFragment.fromTarget(
                                                    Map.of("fragments", List.of(fields)), RESOURCE))
                            .code());
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DataFragment(
                                DataFragment.Kind.MEMORY_MESSAGE,
                                fragment.digest(),
                                fragment.document()));
        var fields = new LinkedHashMap<>(fragment.descriptor());
        fields.put("label", "PUBLIC");
        assertThrows(
                GovernanceException.class,
                () -> DataFragment.fromTarget(Map.of("fragments", List.of(fields)), RESOURCE));
    }
}
