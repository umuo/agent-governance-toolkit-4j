package io.agt.sandbox;

import static io.agt.sandbox.SandboxCommandBackendTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.execution.*;
import io.agt.core.policy.StructuredPolicy;
import io.github.sandboxdemo.api.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SandboxCancellationReceiptTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerCancellationDoesNotConfirmSdkWorkerOrUnknownProcessCleanup(boolean completeCleanup)
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var known = SandboxCleanupState.CONFIRMED;
        var cleanup =
                new SandboxCleanupResult(
                        completeCleanup ? known : SandboxCleanupState.UNKNOWN,
                        known,
                        known,
                        known,
                        true);
        var sdk =
                client(
                        request -> {
                            entered.countDown();
                            // 模拟取消不立即生效的外部 SDK；释放前后台仍确实存活。
                            while (release.getCount() != 0) {
                                try {
                                    release.await(20, TimeUnit.MILLISECONDS);
                                } catch (InterruptedException ignored) {
                                    /* 由测试显式释放。 */
                                }
                            }
                            return result("late-result", "");
                        },
                        request -> {},
                        cleanup);
        var fixture = new SandboxCommandBackendTest();
        fixture.directory = directory;
        var caller = Executors.newSingleThreadExecutor();
        try (var backend = SandboxCommandBackend.strict(sdk, fixture.policy(), 1);
                var control =
                        AgentControl.builder(StructuredPolicy.builder("receipts").build())
                                .audit(record -> {})
                                .build();
                var session = control.openDetachedSession(identity(true))) {
            var command = fixture.command(backend, new CommandLimits(10000, 1024, 1));
            var result = caller.submit(() -> command.execute(Map.of("mode", "old"), session));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            session.revoke();
            assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            var pending = session.externalExecutionSnapshot();
            assertEquals(1, pending.pendingCancellation());
            assertFalse(pending.allTerminationConfirmed());
            assertEquals(1, pending.active());
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (session.externalExecutionSnapshot().pendingCancellation() != 0
                    && System.nanoTime() < deadline) Thread.sleep(5);
            var finalState = session.externalExecutionSnapshot();
            assertEquals(0, finalState.pendingCancellation());
            assertEquals(completeCleanup, finalState.allTerminationConfirmed());
            assertEquals(completeCleanup ? 0 : 1, finalState.unknown());
            assertEquals(completeCleanup ? 0 : 1, finalState.active());
            assertEquals(
                    completeCleanup
                            ? ExternalExecutionRegistry.State.CANCEL_CONFIRMED
                            : ExternalExecutionRegistry.State.UNKNOWN,
                    finalState.entries().get(0).state());
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }
}
