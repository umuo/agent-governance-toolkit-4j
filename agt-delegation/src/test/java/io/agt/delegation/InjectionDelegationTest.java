package io.agt.delegation;

import static io.agt.delegation.DelegationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.delegation.*;
import io.agt.core.injection.*;
import io.agt.core.policy.StructuredPolicy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InjectionDelegationTest {
    @BeforeAll
    static void setup() throws Exception {
        keyPair();
    }

    static StructuredPolicy checked(boolean warn) {
        var base = policy().document();
        return new StructuredPolicy(
                new StructuredPolicy.Document(
                        base.apiVersion(),
                        base.version(),
                        base.interventionPoints(),
                        base.budget(),
                        base.tools(),
                        base.contentRules(),
                        base.resources(),
                        base.dataLimits(),
                        List.of(
                                new InjectionCheck(
                                        "scan",
                                        "classifier",
                                        "pinned-v1",
                                        Set.of(InterventionPoint.PRE_MODEL_CALL),
                                        1000,
                                        10000,
                                        warn
                                                ? InjectionCheck.Failure.WARN
                                                : InjectionCheck.Failure.DENY))));
    }

    static AgentControl runtime(StructuredPolicy policy, InjectionDetector detector) {
        return AgentControl.builder(policy)
                .audit(r -> {})
                .detector("classifier", "pinned-v1", detector)
                .build();
    }

    static DelegationScope modelScope() {
        return new DelegationScope("model-check", true, Map.of(), Map.of());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void receiverCannotRemoveDetectorOrWeakenItsFailurePolicy(boolean weaken) {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = runtime(checked(false), input -> DetectionResult.clear());
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, modelScope());
                var authority = authority(registry, clock);
                var root = authority.register(source);
                var verifier = verifier(registry, clock);
                var receiver =
                        weaken
                                ? runtime(checked(true), input -> DetectionResult.clear())
                                : control()) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            AUDIENCE,
                            Set.of("read"),
                            modelScope(),
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
    void acceptedRemoteRuntimeActuallyDetectsBeforeNativeModelDispatch() {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        var effects = new AtomicInteger();
        try (var sender = runtime(checked(false), input -> DetectionResult.clear());
                var source = sender.openTaskSession(SOURCE, sourceLimits(), null, modelScope());
                var authority = authority(registry, clock);
                var root = authority.register(source);
                var verifier = verifier(registry, clock);
                var receiver = runtime(checked(false), input -> DetectionResult.suspect())) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            AUDIENCE,
                            Set.of("read"),
                            modelScope(),
                            Duration.ofSeconds(30));
            try (var permit = verifier.accept(token, new AuthenticatedAgent("reader"));
                    var session = receiver.openAuthorizedSession(permit, receiverLimits(), null)) {
                assertEquals(
                        "injection_detected",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                session.operation(
                                                        false,
                                                        null,
                                                        "synthetic attack",
                                                        value -> {
                                                            effects.incrementAndGet();
                                                            return "bad";
                                                        },
                                                        v -> v,
                                                        (o, e) -> (String) e))
                                .code());
                assertEquals(0, effects.get());
                assertEquals(0, source.taskSnapshot().modelCalls());
            }
        }
    }

    @Test
    void policiesWithoutDetectionRetainTheirLegacyFingerprint() {
        var simple =
                StructuredPolicy.builder("legacy")
                        .points(Set.of(InterventionPoint.PRE_MODEL_CALL))
                        .build()
                        .document();
        var expected =
                JsonValues.digest(
                        Map.of(
                                "apiVersion",
                                "agt.java/v1",
                                "version",
                                "legacy",
                                "interventionPoints",
                                List.of("pre_model_call"),
                                "budget",
                                Map.of(
                                        "maxModelCalls",
                                        simple.budget().maxModelCalls(),
                                        "maxToolCalls",
                                        simple.budget().maxToolCalls(),
                                        "maxDurationMillis",
                                        simple.budget().maxDurationMillis()),
                                "tools",
                                Map.of(),
                                "contentRules",
                                List.of(),
                                "resources",
                                Map.of(),
                                "dataLimits",
                                Map.of(
                                        "maxCalls",
                                        simple.dataLimits().maxCalls(),
                                        "maxItems",
                                        simple.dataLimits().maxItems())));
        assertEquals(expected, GrantCodec.policyDigest(simple));
    }
}
