package io.agt.delegation;

import static io.agt.delegation.DelegationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.delegation.*;
import io.agt.core.effects.*;
import io.agt.core.policy.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EffectDelegationTest {
    @BeforeAll
    static void setup() throws Exception {
        keyPair();
    }

    static StructuredPolicy effectPolicy(String variation) {
        EffectRule rule =
                switch (variation) {
                    case "omit" -> null;
                    case "namespace" ->
                            new EffectRule("other", "v1", EffectLedger.Phase.FORWARD, "catalog-1");
                    case "schema" ->
                            new EffectRule("orders", "v2", EffectLedger.Phase.FORWARD, "catalog-1");
                    case "phase" ->
                            new EffectRule(
                                    "orders", "v1", EffectLedger.Phase.COMPENSATION, "catalog-1");
                    case "catalog" ->
                            new EffectRule("orders", "v1", EffectLedger.Phase.FORWARD, "catalog-2");
                    default ->
                            new EffectRule("orders", "v1", EffectLedger.Phase.FORWARD, "catalog-1");
                };
        return StructuredPolicy.builder("grant-effects-v1")
                .budget(CALLS)
                .tool(
                        "lookup",
                        new ToolRule(Set.of("read"), args("customer-a", "customer-b"), false, rule))
                .tool("slow", new ToolRule(Set.of("read"), ParameterRule.object(Map.of()), false))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"omit", "namespace", "schema", "phase", "catalog"})
    void signedReceiverCannotDropOrReplaceRequiredEffectBinding(String variation) {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = AgentControl.builder(effectPolicy("original")).audit(r -> {}).build();
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var authority = authority(registry, clock);
                var root = authority.register(source);
                var verifier = verifier(registry, clock);
                var receiver =
                        AgentControl.builder(effectPolicy(variation)).audit(r -> {}).build()) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            AUDIENCE,
                            Set.of("read"),
                            scope(),
                            Duration.ofSeconds(30));
            try (var permit = verifier.accept(token, new AuthenticatedAgent("reader"))) {
                assertEquals(
                        "delegation_runtime_policy_denied",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                receiver.openAuthorizedSession(
                                                        permit, receiverLimits(), null))
                                .code());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyToolPolicyWithoutEffectsKeepsOriginalDigest() {
        var document =
                StructuredPolicy.builder("legacy-tools")
                        .points(Set.of(InterventionPoint.PRE_TOOL_CALL))
                        .tool(
                                "read",
                                new ToolRule(Set.of("read"), ParameterRule.object(Map.of()), false))
                        .build()
                        .document();
        var oldShape =
                new com.fasterxml.jackson.databind.ObjectMapper().convertValue(document, Map.class);
        oldShape.remove("injectionChecks");
        ((Map<String, Object>) ((Map<String, Object>) oldShape.get("tools")).get("read"))
                .remove("effect");
        // 旧协议既没有 effect，也没有后续加入的可选版本绑定字段。
        ((Map<String, Object>) ((Map<String, Object>) oldShape.get("tools")).get("read"))
                .remove("binding");
        assertEquals(JsonValues.digest(oldShape), GrantCodec.policyDigest(document));
    }
}
