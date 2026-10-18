package io.agt.delegation;

import static io.agt.delegation.DelegationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class QuiescentHandoffTest {
    @BeforeAll
    static void prepareKey() throws Exception {
        keyPair();
    }

    @Test
    void synchronousToolCannotBeTransferredWhileItsPostCheckIsPending() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        var workers = Executors.newSingleThreadExecutor();
        var release = new CountDownLatch(1);
        try (var control = control();
                var source = control.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var authority = authority(registry, clock)) {
            var entered = new CountDownLatch(1);
            var call =
                    workers.submit(
                            () ->
                                    source.operation(
                                            true,
                                            "lookup",
                                            Map.of("resource", "customer-a"),
                                            value -> "ok",
                                            value -> {
                                                entered.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new AssertionError("Release missing");
                                                } catch (InterruptedException interrupted) {
                                                    throw new IllegalStateException(interrupted);
                                                }
                                                return value;
                                            },
                                            (original, value) -> (String) value));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(
                    "delegation_operations_inflight_denied",
                    assertThrows(GovernanceException.class, () -> authority.register(source))
                            .code());
            release.countDown();
            assertEquals("ok", call.get(3, TimeUnit.SECONDS));
            try (var root = authority.register(source)) {
                assertEquals(2, registry.parent(root.lease()).quota().toolCalls());
                source.checkActive();
            }
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void asynchronousUnfinishedToolDeniesHandoffWithoutDestroyingOriginalTask() throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var control = control();
                var source = control.openTaskSession(SOURCE, sourceLimits(), null, scope());
                var authority = authority(registry, clock)) {
            var nativeCall = new CompletableFuture<String>();
            var entered = new CountDownLatch(1);
            var call =
                    source.operationAsyncPrepared(
                            true,
                            "lookup",
                            Map.of("resource", "customer-a"),
                            value -> value,
                            value -> {
                                entered.countDown();
                                return nativeCall;
                            },
                            value -> value,
                            (original, value) -> (String) value);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(
                    "delegation_operations_inflight_denied",
                    assertThrows(GovernanceException.class, () -> authority.register(source))
                            .code());
            nativeCall.complete("ok");
            assertEquals("ok", call.get(3, TimeUnit.SECONDS));
            try (var root = authority.register(source)) {
                assertEquals(2, registry.parent(root.lease()).quota().toolCalls());
                source.checkActive();
            }
        }
    }
}
