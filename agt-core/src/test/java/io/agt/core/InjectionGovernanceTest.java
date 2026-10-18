package io.agt.core;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.audit.AuditRecord;
import io.agt.core.injection.*;
import io.agt.core.policy.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InjectionGovernanceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedInputIsNeverPartiallyScannedOrSilentlyMarkedClear(boolean warn) {
        var checks =
                new InjectionCheck(
                        "tiny",
                        "classifier",
                        "reviewed-v1",
                        Set.of(InterventionPoint.PRE_MODEL_CALL),
                        1000,
                        3,
                        warn ? InjectionCheck.Failure.WARN : InjectionCheck.Failure.DENY);
        var scanned = new AtomicInteger();
        var executed = new AtomicInteger();
        var audit = new CopyOnWriteArrayList<AuditRecord>();
        try (var control =
                        control(
                                checks,
                                input -> {
                                    scanned.incrementAndGet();
                                    return DetectionResult.clear();
                                },
                                audit);
                var session = control.openSession(ID)) {
            if (warn) model(session, executed, "larger-than-cap");
            else
                assertEquals(
                        "injection_input_limit",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> model(session, executed, "larger-than-cap"))
                                .code());
            assertEquals(0, scanned.get());
            assertEquals(warn ? 1 : 0, executed.get());
            assertTrue(
                    audit.stream().anyMatch(r -> r.warnings().contains("injection_input_limit")));
            assertFalse(audit.stream().anyMatch(r -> r.warnings().contains("injection_clear")));
        }
    }

    @Test
    void unknownIsNotClearAndObserveReportsSuspectWithoutGrantingNewAuthority() {
        var audit = new CopyOnWriteArrayList<AuditRecord>();
        try (var control =
                        control(
                                check(InjectionCheck.Failure.DENY, 1000),
                                input -> DetectionResult.unknown(),
                                audit);
                var session = control.openSession(ID)) {
            assertEquals(
                    "injection_detector_unknown",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> model(session, new AtomicInteger(), "request"))
                            .code());
        }
        var effects = new AtomicInteger();
        try (var control =
                        AgentControl.builder(policy(check(InjectionCheck.Failure.DENY, 1000)))
                                .mode(EnforcementMode.EVALUATE_ONLY)
                                .audit(audit::add)
                                .detector(
                                        "classifier",
                                        "reviewed-v1",
                                        input -> DetectionResult.suspect())
                                .build();
                var session = control.openSession(ID)) {
            model(session, effects, "request");
            assertEquals(1, effects.get());
            assertTrue(audit.stream().anyMatch(r -> r.warnings().contains("injection_detected")));
            assertThrows(GovernanceException.class, control::exportAuthorizationPolicy);
        }
    }

    @Test
    void runtimeUsesOneImmutableDeclarationSnapshotEvenForCustomEvaluators() {
        var check =
                new InjectionCheck(
                        "output",
                        "classifier",
                        "reviewed-v1",
                        Set.of(InterventionPoint.POST_MODEL_CALL),
                        1000,
                        10000,
                        InjectionCheck.Failure.DENY);
        var declaration = new AtomicReference<List<InjectionCheck>>(List.of(check));
        var delegate = StructuredPolicy.builder("custom-policy").build();
        var reads = new AtomicInteger();
        PolicyEvaluator custom =
                new PolicyEvaluator() {
                    public String version() {
                        return delegate.version();
                    }

                    public Set<InterventionPoint> interventionPoints() {
                        return delegate.interventionPoints();
                    }

                    public PolicyDecision evaluate(PolicyRequest request) {
                        return delegate.evaluate(request);
                    }

                    public List<InjectionCheck> injectionChecks() {
                        reads.incrementAndGet();
                        return declaration.get();
                    }
                };
        try (var control =
                        AgentControl.builder(custom)
                                .audit(r -> {})
                                .detector(
                                        "classifier",
                                        "reviewed-v1",
                                        input -> DetectionResult.suspect())
                                .build();
                var session = control.openSession(ID)) {
            declaration.set(List.of());
            assertTrue(control.requiresCompleteOutputCheck());
            assertEquals(1, reads.get());
            assertEquals(
                    "injection_detected",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> model(session, new AtomicInteger(), "request"))
                            .code());
        }
    }

    static final TrustedContext ID = new TrustedContext("agent", "tenant", "user", Set.of());

    static InjectionCheck check(InjectionCheck.Failure failure, long timeout) {
        return new InjectionCheck(
                "prompt-check",
                "classifier",
                "reviewed-v1",
                Set.of(InterventionPoint.PRE_MODEL_CALL),
                timeout,
                10000,
                failure);
    }

    static StructuredPolicy policy(InjectionCheck check) {
        return StructuredPolicy.builder("injection-v1").injection(check).build();
    }

    static String model(HostSession session, AtomicInteger calls, String input) {
        return session.operation(
                false,
                null,
                input,
                v -> {
                    calls.incrementAndGet();
                    return "answer";
                },
                v -> v,
                (before, after) -> (String) after);
    }

    static AgentControl control(
            InjectionCheck check, InjectionDetector detector, List<AuditRecord> audit) {
        return AgentControl.builder(policy(check))
                .audit(audit::add)
                .detector("classifier", "reviewed-v1", detector)
                .build();
    }

    @Test
    void missingOrWrongVersionBindingFailsBeforeRuntimeCanServeRequests() {
        var policy = policy(check(InjectionCheck.Failure.DENY, 1000));
        assertEquals(
                "injection_detector_binding_denied",
                assertThrows(
                                GovernanceException.class,
                                () -> AgentControl.builder(policy).audit(r -> {}).build())
                        .code());
        assertThrows(
                GovernanceException.class,
                () ->
                        AgentControl.builder(policy)
                                .audit(r -> {})
                                .detector("classifier", "older-v0", i -> DetectionResult.clear())
                                .build());
    }

    @Test
    void suspectNeverReachesNativeAndAuditContainsNoBody() {
        var audit = new CopyOnWriteArrayList<AuditRecord>();
        var calls = new AtomicInteger();
        try (var control =
                        control(
                                check(InjectionCheck.Failure.DENY, 1000),
                                input -> DetectionResult.suspect(),
                                audit);
                var session = control.openSession(ID)) {
            assertEquals(
                    "injection_detected",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> model(session, calls, "private injected payload"))
                            .code());
            assertEquals(0, calls.get());
            var denied =
                    audit.stream()
                            .filter(r -> r.reasonCode().equals("injection_detected"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("DENY", denied.verdict());
            assertTrue(denied.warnings().contains("injection_trust:MIXED"));
            assertFalse(audit.toString().contains("private injected payload"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableDetectorUsesExplicitFaultPolicyWithoutReportingClear(boolean warn) {
        var audit = new CopyOnWriteArrayList<AuditRecord>();
        var calls = new AtomicInteger();
        var failure = warn ? InjectionCheck.Failure.WARN : InjectionCheck.Failure.DENY;
        try (var control =
                        control(
                                check(failure, 1000),
                                input -> {
                                    throw new IllegalStateException("private detector diagnostic");
                                },
                                audit);
                var session = control.openSession(ID)) {
            if (warn) assertEquals("answer", model(session, calls, "request"));
            else
                assertEquals(
                        "injection_detector_error",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> model(session, calls, "request"))
                                .code());
            assertEquals(warn ? 1 : 0, calls.get());
            assertTrue(
                    audit.stream()
                            .anyMatch(r -> r.warnings().contains("injection_detector_error")));
            assertFalse(audit.stream().anyMatch(r -> r.warnings().contains("injection_clear")));
            assertFalse(audit.toString().contains("private detector diagnostic"));
        }
    }

    @Test
    void rewrittenRequestMustBeCheckedAgainBeforeDispatch() {
        var policy =
                StructuredPolicy.builder("rewrite-scan")
                        .injection(check(InjectionCheck.Failure.DENY, 1000))
                        .content(
                                new ContentRule(
                                        "rewrite",
                                        Set.of(InterventionPoint.PRE_MODEL_CALL),
                                        List.of(),
                                        Map.of("safe", "injected")))
                        .build();
        var stages = new CopyOnWriteArrayList<DetectionInput.Stage>();
        var calls = new AtomicInteger();
        try (var control =
                        AgentControl.builder(policy)
                                .audit(r -> {})
                                .detector(
                                        "classifier",
                                        "reviewed-v1",
                                        input -> {
                                            stages.add(input.stage());
                                            return input.content().equals("injected")
                                                    ? DetectionResult.suspect()
                                                    : DetectionResult.clear();
                                        })
                                .build();
                var session = control.openSession(ID)) {
            assertEquals(
                    "injection_detected",
                    assertThrows(GovernanceException.class, () -> model(session, calls, "safe"))
                            .code());
            assertEquals(
                    List.of(DetectionInput.Stage.ORIGINAL, DetectionInput.Stage.EFFECTIVE), stages);
            assertEquals(0, calls.get());
        }
    }

    @Test
    void deterministicDenialPrecedesAnyExternalDetection() {
        var detections = new AtomicInteger();
        var effects = new AtomicInteger();
        var policy =
                StructuredPolicy.builder("hard-denial")
                        .injection(check(InjectionCheck.Failure.WARN, 1000))
                        .content(
                                new ContentRule(
                                        "blocked",
                                        Set.of(InterventionPoint.PRE_MODEL_CALL),
                                        List.of("forbidden"),
                                        Map.of()))
                        .build();
        try (var control =
                        AgentControl.builder(policy)
                                .audit(r -> {})
                                .detector(
                                        "classifier",
                                        "reviewed-v1",
                                        input -> {
                                            detections.incrementAndGet();
                                            return DetectionResult.clear();
                                        })
                                .build();
                var session = control.openSession(ID)) {
            assertThrows(GovernanceException.class, () -> model(session, effects, "forbidden"));
            assertEquals(0, detections.get());
            assertEquals(0, effects.get());
        }
    }

    @Test
    void lateClearCannotUndoTimeout() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var control =
                        control(
                                check(InjectionCheck.Failure.DENY, 200),
                                input -> {
                                    entered.countDown();
                                    while (release.getCount() > 0)
                                        try {
                                            release.await();
                                        } catch (InterruptedException ignored) {
                                        }
                                    return DetectionResult.clear();
                                },
                                new CopyOnWriteArrayList<>());
                var session = control.openSession(ID)) {
            assertEquals(
                    "injection_detector_timeout",
                    assertThrows(GovernanceException.class, () -> model(session, calls, "request"))
                            .code());
            assertEquals(0, entered.getCount());
            release.countDown();
            assertEquals(0, calls.get());
            assertTrue(session.budgetSnapshot().failed());
            assertThrows(GovernanceException.class, () -> model(session, calls, "retry"));
            assertEquals(0, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void sharedPoolRemainsBoundedWhenPluginsIgnoreInterrupts() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var started = new AtomicInteger();
        try (var control =
                control(
                        check(InjectionCheck.Failure.WARN, 150),
                        input -> {
                            started.incrementAndGet();
                            entered.countDown();
                            while (release.getCount() > 0)
                                try {
                                    release.await();
                                } catch (InterruptedException ignored) {
                                }
                            return DetectionResult.clear();
                        },
                        new CopyOnWriteArrayList<>())) {
            for (int i = 0; i < 8; i++)
                try (var session = control.openSession(ID)) {
                    model(session, new AtomicInteger(), "request");
                }
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(2, started.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void sessionRevocationInterruptsDetectionWaitWithoutDispatch() throws Exception {
        var entered = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var callers = Executors.newSingleThreadExecutor();
        var calls = new AtomicInteger();
        try (var control =
                        control(
                                check(InjectionCheck.Failure.DENY, 10000),
                                input -> {
                                    entered.countDown();
                                    try {
                                        new CountDownLatch(1).await();
                                    } finally {
                                        stopped.countDown();
                                    }
                                    return DetectionResult.clear();
                                },
                                new CopyOnWriteArrayList<>());
                var root =
                        control.openTaskSession(
                                ID, new TaskLimits(new BudgetLimits(5, 5, 30000), 5, 2, 1))) {
            var future =
                    callers.submit(
                            () -> {
                                try (var binding = root.bind()) {
                                    return model(root, calls, "request");
                                }
                            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            root.revoke();
            assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void yamlDefinesVersionedChecks() {
        var parsed =
                StructuredPolicy.fromYaml(
                        """
                apiVersion: agt.java/v1
                version: injection-yaml
                interventionPoints: [agent_startup, pre_model_call, agent_shutdown]
                budget: {maxModelCalls: 3, maxToolCalls: 3, maxDurationMillis: 30000}
                injectionChecks:
                  - id: scan
                    detector: classifier
                    revision: reviewed-v1
                    points: [pre_model_call]
                    timeoutMillis: 1000
                    maxCharacters: 1000
                    failure: DENY
                """);
        assertEquals(1, parsed.injectionChecks().size());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new InjectionCheck(
                                "scan",
                                "classifier",
                                "v1",
                                Set.of(InterventionPoint.AGENT_SHUTDOWN),
                                1000,
                                1000,
                                InjectionCheck.Failure.WARN));
    }
}
