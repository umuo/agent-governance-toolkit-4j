package io.agt.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.invocation.InvocationContext;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.execution.CommandExecution;
import io.agt.core.execution.CommandLimits;
import io.agt.langchain4j.LangChain4jGovernance;
import io.github.sandboxdemo.api.SandboxErrorCode;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxIsolationLevel;
import io.github.sandboxdemo.api.SandboxPhase;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxResourceLimits;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * alpha.2 正式 API 的准入、稳定错误与协作取消契约。
 *
 * <p>使用无资源的合成 runner；原生隔离由 SandboxNativeTest 独立证明。
 */
class SandboxAlpha2Test {
    @TempDir Path directory;

    private SandboxCommandBackendTest fixture() {
        var fixture = new SandboxCommandBackendTest();
        fixture.directory = directory;
        return fixture;
    }

    @Test
    void actualPolicyPreflightRefusesBeforeBudgetOrTargetExecution() throws Exception {
        var fixture = fixture();
        var calls = new AtomicInteger();
        var probes = new AtomicInteger();
        var client =
                SandboxCommandBackendTest.client(
                        request -> {
                            calls.incrementAndGet();
                            return SandboxCommandBackendTest.result("unexpected", "");
                        },
                        request -> {
                            probes.incrementAndGet();
                            throw new SandboxException(
                                    SandboxErrorCode.BACKEND_UNAVAILABLE,
                                    SandboxPhase.PROBE,
                                    "private-probe-marker");
                        });
        try (var backend = SandboxCommandBackend.strict(client, fixture.policy())) {
            var command = fixture.command(backend, CommandLimits.DEFAULT);
            try (var control =
                            AgentControl.builder(SandboxCommandBackendTest.rules(command, false))
                                    .audit(r -> {})
                                    .build();
                    var session = control.openSession(SandboxCommandBackendTest.identity(true))) {
                var error =
                        assertThrows(
                                GovernanceException.class,
                                () ->
                                        new LangChain4jGovernance(control)
                                                .guardCommand(command)
                                                .execute(
                                                        SandboxCommandBackendTest.request(), null));
                assertEquals("sandbox_unavailable", error.code());
                assertFalse(error.getMessage().contains("private-probe-marker"));
                assertEquals(0, calls.get());
                assertEquals(1, probes.get());
                assertEquals(0, session.budgetSnapshot().toolCalls());
            }
        }
    }

    @Test
    void requestReconstructionPreservesAllResourceAndIsolationRequirements() throws Exception {
        var limits =
                SandboxResourceLimits.builder()
                        .memoryBytes(1024)
                        .processes(2)
                        .cpuTime(Duration.ofMillis(100))
                        .diskBytes(2048)
                        .build();
        var policy =
                SandboxPolicy.builder(directory)
                        .resourceLimits(limits)
                        .requiredIsolation(SandboxIsolationLevel.VIRTUAL_MACHINE)
                        .maxCombinedOutputBytes(17)
                        .build();
        var request =
                SandboxRequests.create(
                        policy,
                        new CommandExecution(
                                directory.resolve("tool"),
                                directory,
                                List.of("fixed"),
                                new CommandLimits(1000, 128, 1)),
                        null);
        assertEquals(limits, request.policy().resourceLimits());
        assertEquals(SandboxIsolationLevel.VIRTUAL_MACHINE, request.policy().requiredIsolation());
        assertEquals(17, request.policy().maxCombinedOutputBytes());
        assertTrue(request.executionEnvironment().isStrict());
    }

    @Test
    void unsupportedHardLimitCannotDisappearDuringAdmission() throws Exception {
        var fixture = fixture();
        var calls = new AtomicInteger();
        var client =
                SandboxCommandBackendTest.client(
                        r -> {
                            calls.incrementAndGet();
                            return SandboxCommandBackendTest.result("unexpected", "");
                        });
        var policy =
                SandboxPolicy.builder(directory)
                        .resourceLimits(SandboxResourceLimits.builder().memoryBytes(1024).build())
                        .build();
        try (var backend = SandboxCommandBackend.strict(client, policy)) {
            var command = fixture.command(backend, CommandLimits.DEFAULT);
            try (var control =
                            AgentControl.builder(SandboxCommandBackendTest.rules(command, false))
                                    .audit(r -> {})
                                    .build();
                    var session = control.openSession(SandboxCommandBackendTest.identity(true))) {
                assertEquals(
                        "sandbox_policy_unsupported",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                new LangChain4jGovernance(control)
                                                        .guardCommand(command)
                                                        .execute(
                                                                SandboxCommandBackendTest.request(),
                                                                null))
                                .code());
                assertEquals(0, calls.get());
                assertEquals(0, session.budgetSnapshot().toolCalls());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "UNKNOWN,sandbox_execution_failed",
        "BACKEND_UNAVAILABLE,sandbox_unavailable",
        "POLICY_UNSUPPORTED,sandbox_policy_unsupported",
        "POLICY_INVALID,sandbox_request_invalid",
        "START_FAILED,sandbox_start_failed",
        "OUTPUT_CAPTURE_FAILED,sandbox_output_failed",
        "OUTPUT_LIMIT,sandbox_output_limit",
        "TIMEOUT,sandbox_timeout",
        "CANCELLED,sandbox_cancelled",
        "CLEANUP_FAILED,sandbox_cleanup_unconfirmed"
    })
    void errorsUseStableCategoriesWithoutDebugText(SandboxErrorCode code, String expected)
            throws Exception {
        var fixture = fixture();
        var client =
                SandboxCommandBackendTest.client(
                        r -> {
                            throw new SandboxException(
                                    code, SandboxPhase.EXECUTION, "private-debug-marker");
                        });
        try (var backend = SandboxCommandBackend.strict(client, fixture.policy())) {
            var command = fixture.command(backend, CommandLimits.DEFAULT);
            try (var control =
                            AgentControl.builder(SandboxCommandBackendTest.rules(command, false))
                                    .audit(r -> {})
                                    .build();
                    var session = control.openSession(SandboxCommandBackendTest.identity(true))) {
                var error =
                        assertThrows(
                                GovernanceException.class,
                                () ->
                                        new LangChain4jGovernance(control)
                                                .guardCommand(command)
                                                .execute(
                                                        SandboxCommandBackendTest.request(), null));
                assertEquals(expected, error.code());
                assertFalse(error.getMessage().contains("private-debug-marker"));
                assertEquals(1, session.budgetSnapshot().toolCalls());
            }
        }
    }

    @Test
    void cancellationReachesSdkExecutionControl() throws Exception {
        var fixture = fixture();
        var started = new java.util.concurrent.CountDownLatch(1);
        var observed = new java.util.concurrent.CountDownLatch(1);
        var capabilities = SandboxCommandBackendTest.client(r -> null).capabilities();
        var client =
                io.github.sandboxdemo.sdk.SandboxClient.builder()
                        .backend(
                                new io.github.sandboxdemo.api.SandboxRunner() {
                                    @Override
                                    public String backendName() {
                                        return "fake";
                                    }

                                    @Override
                                    public boolean supportsOutputPolicy(SandboxPolicy policy) {
                                        return true;
                                    }

                                    @Override
                                    public boolean supportsEnvironment(
                                            io.github.sandboxdemo.api.SandboxRequest request) {
                                        return true;
                                    }

                                    @Override
                                    public void validate(
                                            io.github.sandboxdemo.api.SandboxRequest request) {}

                                    @Override
                                    public io.github.sandboxdemo.api.SandboxBackendInfo
                                            backendInfo() {
                                        return io.github.sandboxdemo.api.SandboxBackendInfo.current(
                                                capabilities.platform(), "fake", null);
                                    }

                                    @Override
                                    public io.github.sandboxdemo.api.SandboxResult execute(
                                            io.github.sandboxdemo.api.SandboxRequest request) {
                                        throw new AssertionError("必须通过协作控制入口执行");
                                    }

                                    @Override
                                    public io.github.sandboxdemo.api.SandboxResult execute(
                                            io.github.sandboxdemo.api.SandboxRequest request,
                                            io.github.sandboxdemo.api.SandboxExecutionControl
                                                    signal)
                                            throws SandboxException, InterruptedException {
                                        started.countDown();
                                        try {
                                            while (!signal.cancellationRequested()) {
                                                signal.checkActive();
                                                Thread.sleep(5);
                                            }
                                            observed.countDown();
                                            throw new SandboxException(
                                                    SandboxErrorCode.CANCELLED,
                                                    SandboxPhase.EXECUTION,
                                                    "cancelled");
                                        } finally {
                                            signal.reportCleanup(
                                                    new io.github.sandboxdemo.api
                                                            .SandboxCleanupResult(
                                                            io.github.sandboxdemo.api
                                                                    .SandboxCleanupState
                                                                    .NOT_REQUIRED,
                                                            io.github.sandboxdemo.api
                                                                    .SandboxCleanupState
                                                                    .NOT_REQUIRED,
                                                            io.github.sandboxdemo.api
                                                                    .SandboxCleanupState
                                                                    .NOT_REQUIRED,
                                                            io.github.sandboxdemo.api
                                                                    .SandboxCleanupState
                                                                    .NOT_REQUIRED,
                                                            true));
                                        }
                                    }
                                },
                                capabilities,
                                () ->
                                        new io.github.sandboxdemo.api.SandboxRuntimeStatus(
                                                capabilities, true, "fake"))
                        .build();
        try (var backend = SandboxCommandBackend.strict(client, fixture.policy())) {
            var command = fixture.command(backend, new CommandLimits(2000, 128, 1));
            try (var control =
                            AgentControl.builder(SandboxCommandBackendTest.rules(command, false))
                                    .audit(r -> {})
                                    .build();
                    var session = control.openSession(SandboxCommandBackendTest.identity(true))) {
                var future =
                        new LangChain4jGovernance(control)
                                .guardCommand(command)
                                .executeAsync(
                                        SandboxCommandBackendTest.request(),
                                        InvocationContext.builder().build());
                assertTrue(started.await(1, TimeUnit.SECONDS));
                future.cancel(true);
                assertTrue(observed.await(1, TimeUnit.SECONDS), "SDK 控制对象未收到取消");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"FAILED", "UNKNOWN"})
    void unconfirmedStreamCleanupWithholdsResultsAndQuarantines(
            io.github.sandboxdemo.api.SandboxCleanupState state) throws Exception {
        var fixture = fixture();
        var cleanup =
                new io.github.sandboxdemo.api.SandboxCleanupResult(
                        io.github.sandboxdemo.api.SandboxCleanupState.NOT_REQUIRED,
                        state,
                        io.github.sandboxdemo.api.SandboxCleanupState.NOT_REQUIRED,
                        io.github.sandboxdemo.api.SandboxCleanupState.NOT_REQUIRED,
                        true);
        var client =
                SandboxCommandBackendTest.client(
                        r -> SandboxCommandBackendTest.result("private-result", ""),
                        r -> {},
                        cleanup);
        try (var backend = SandboxCommandBackend.strict(client, fixture.policy())) {
            var command = fixture.command(backend, CommandLimits.DEFAULT);
            try (var control =
                            AgentControl.builder(SandboxCommandBackendTest.rules(command, false))
                                    .audit(r -> {})
                                    .build();
                    var session = control.openSession(SandboxCommandBackendTest.identity(true))) {
                assertEquals(
                        "sandbox_cleanup_unconfirmed",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                new LangChain4jGovernance(control)
                                                        .guardCommand(command)
                                                        .execute(
                                                                SandboxCommandBackendTest.request(),
                                                                null))
                                .code());
                assertEquals(1, session.budgetSnapshot().toolCalls());
                assertEquals(
                        "sandbox_cleanup_unconfirmed",
                        assertThrows(
                                        GovernanceException.class,
                                        () ->
                                                backend.prepare(
                                                        new CommandExecution(
                                                                directory.resolve("tool"),
                                                                directory.toRealPath(),
                                                                List.of(),
                                                                CommandLimits.DEFAULT)))
                                .code());
            }
        }
    }
}
