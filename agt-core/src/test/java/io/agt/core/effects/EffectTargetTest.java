package io.agt.core.effects;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EffectTargetTest {
    @Test
    void humanApprovalTargetCannotLoseBusinessIdentityDuringParameterRewrite() {
        var rule = new EffectRule("orders", "v1", EffectLedger.Phase.FORWARD, "catalog-1");
        var target = new EffectTarget(rule, new EffectIntent("host-order-7", "open", null));
        var request =
                new PolicyRequest(
                        InterventionPoint.PRE_TOOL_CALL,
                        new TrustedContext("agent", "tenant", "user", Set.of()),
                        "session",
                        "action",
                        "pay",
                        Map.of("item", "before"),
                        target);
        assertEquals(target, request.withTarget(Map.of("item", "after")).effect());
        assertNotEquals(
                target.identity(),
                new EffectTarget(rule, new EffectIntent("host-order-8", "open", null)).identity());
        assertNotEquals(
                target.identity(),
                new EffectTarget(rule, new EffectIntent("host-order-7", "closed", null))
                        .identity());
        assertFalse(target.toString().contains("host-order-7"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PolicyRequest(
                                InterventionPoint.POST_TOOL_CALL,
                                request.context(),
                                "session",
                                "action",
                                "pay",
                                "result",
                                target));
    }
}
