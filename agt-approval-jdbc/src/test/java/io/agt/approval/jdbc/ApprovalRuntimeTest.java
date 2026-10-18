package io.agt.approval.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.agt.core.*;
import io.agt.core.approval.*;
import io.agt.core.delegation.DelegationScope;
import io.agt.identity.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ApprovalRuntimeTest {
    private HostSession session(ApprovalFixture f, AuthenticatedActor actor, AgentControl control) {
        var scope =
                new DelegationScope(
                        "task",
                        false,
                        Map.of("read", f.runtime.document().tools().get("read").parameters()),
                        Map.of());
        return control.openAuthorizedSession(
                actor.permit("agent", f.runtime, scope), new TaskLimits(f.budget, 10, 1, 0), null);
    }

    private String tool(HostSession session, java.util.function.Function<Object, String> backend) {
        return session.operation(
                true,
                "read",
                Map.of("value", "effective-private-parameters"),
                backend,
                value -> value,
                (original, value) -> (String) value);
    }

    private ApprovalReview waitForReview(
            ApprovalFixture f,
            AuthenticatedActor reviewer,
            AtomicReference<ApprovalRequest> request)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var pending = request.get();
            if (pending != null) {
                try {
                    return f.store.review(reviewer, pending.requestId());
                } catch (GovernanceException missing) {
                    if (!missing.code().equals("approval_request_missing")) throw missing;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Approval was not persisted");
    }

    @Test
    void authenticatedReviewGatesActualHttpToolAndBindsEffectiveParameters() throws Exception {
        try (var f = new ApprovalFixture();
                var backend = new Backend()) {
            var actor = f.requester();
            var reviewer = f.reviewer();
            var request = new AtomicReference<ApprovalRequest>();
            try (var resolver = new JdbcApprovalResolver(f.store, actor);
                    var control =
                            AgentControl.builder(f.runtime)
                                    .audit(r -> {})
                                    .approvalTimeout(Duration.ofSeconds(4))
                                    .approvalResolver(
                                            r -> {
                                                request.set(r);
                                                return resolver.resolve(r);
                                            })
                                    .build();
                    var session = session(f, actor, control)) {
                var caller = Executors.newSingleThreadExecutor();
                try {
                    var result = caller.submit(() -> tool(session, backend::invoke));
                    var view = waitForReview(f, reviewer, request);
                    assertEquals(0, backend.calls.get());
                    assertFalse(result.isDone());
                    assertEquals(
                            Map.of("value", "effective-private-parameters"),
                            view.request().action().target());
                    f.store.decide(reviewer, view, true);
                    assertEquals("backend-ok", result.get(5, TimeUnit.SECONDS));
                    assertEquals(1, backend.calls.get());
                    assertEquals(
                            Map.of("value", "effective-private-parameters"),
                            JsonValues.parseObject(backend.body.get()));
                    assertEquals(
                            JdbcApprovalStore.Status.CONSUMED,
                            f.store.receipt(actor, view.request().requestId()).status());
                } finally {
                    caller.shutdownNow();
                }
            }
        }
    }

    @Test
    void deniedHumanDecisionNeverReachesRealBackend() throws Exception {
        try (var f = new ApprovalFixture();
                var backend = new Backend()) {
            var actor = f.requester();
            var reviewer = f.reviewer();
            var request = new AtomicReference<ApprovalRequest>();
            try (var resolver = new JdbcApprovalResolver(f.store, actor);
                    var control =
                            AgentControl.builder(f.runtime)
                                    .audit(r -> {})
                                    .approvalTimeout(Duration.ofSeconds(4))
                                    .approvalResolver(
                                            r -> {
                                                request.set(r);
                                                return resolver.resolve(r);
                                            })
                                    .build();
                    var session = session(f, actor, control)) {
                var caller = Executors.newSingleThreadExecutor();
                try {
                    var result = caller.submit(() -> tool(session, backend::invoke));
                    var view = waitForReview(f, reviewer, request);
                    f.store.decide(reviewer, view, false);
                    assertInstanceOf(
                            GovernanceException.class,
                            assertThrows(
                                            ExecutionException.class,
                                            () -> result.get(5, TimeUnit.SECONDS))
                                    .getCause());
                    assertEquals(0, backend.calls.get());
                } finally {
                    caller.shutdownNow();
                }
            }
        }
    }

    @Test
    void timeoutAndLateApprovalCannotStartBusinessCall() throws Exception {
        try (var f = new ApprovalFixture();
                var backend = new Backend()) {
            var actor = f.requester();
            var request = new AtomicReference<ApprovalRequest>();
            try (var resolver = new JdbcApprovalResolver(f.store, actor);
                    var control =
                            AgentControl.builder(f.runtime)
                                    .audit(r -> {})
                                    .approvalTimeout(Duration.ofMillis(150))
                                    .approvalResolver(
                                            r -> {
                                                request.set(r);
                                                return resolver.resolve(r);
                                            })
                                    .build();
                    var session = session(f, actor, control)) {
                assertThrows(GovernanceException.class, () -> tool(session, backend::invoke));
                assertNotNull(request.get());
                Thread.sleep(250);
                assertThrows(
                        GovernanceException.class,
                        () -> f.store.review(f.reviewer(), request.get().requestId()));
                assertEquals(0, backend.calls.get());
            }
        }
    }

    @Test
    void resolverCapacityCancellationAndCloseAreBounded() throws Exception {
        try (var f = new ApprovalFixture()) {
            var requester = f.requester();
            try (var resolver = new JdbcApprovalResolver(f.store, requester)) {
                var first = resolver.resolve(f.request(requester)).toCompletableFuture();
                var second = resolver.resolve(f.request(requester)).toCompletableFuture();
                var overflow = resolver.resolve(f.request(requester)).toCompletableFuture();
                var failure =
                        assertThrows(
                                ExecutionException.class, () -> overflow.get(1, TimeUnit.SECONDS));
                assertEquals(
                        "approval_resolver_capacity",
                        ((GovernanceException) failure.getCause()).code());
                assertTrue(first.cancel(true));
                assertTrue(second.cancel(true));
                var reused = resolver.resolve(f.request(requester)).toCompletableFuture();
                assertTrue(reused.cancel(true));
                var pending = resolver.resolve(f.request(requester)).toCompletableFuture();
                resolver.close();
                assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void reviewedVersionSurvivesIdentityAndEncryptedApprovalBeforeRealHttpExecution()
            throws Exception {
        try (var fixture = new ApprovalFixture();
                var backend = new Backend()) {
            byte[] artifact = "reviewed-artifact".getBytes(StandardCharsets.UTF_8);
            var specification =
                    Map.<String, Object>of("name", "read", "description", "reviewed read");
            var descriptor =
                    new io.agt.core.tools.ToolDescriptor(
                            "read",
                            "publisher",
                            "v1",
                            "sha256:" + io.agt.core.storage.StorageCrypto.hash(artifact),
                            specification,
                            Set.of("read"));
            try (var catalog =
                    new io.agt.core.tools.ReviewedToolCatalog(List.of(descriptor), 1024)) {
                var lease = catalog.register("read", artifact, specification);
                var parameters = fixture.runtime.document().tools().get("read").parameters();
                var policy =
                        io.agt.core.policy.StructuredPolicy.builder("versioned-v1")
                                .budget(fixture.budget)
                                .tool(
                                        "read",
                                        new io.agt.core.policy.ToolRule(
                                                Set.of("read"),
                                                parameters,
                                                true,
                                                null,
                                                lease.identity()))
                                .build();
                var approval =
                        new ApprovalPolicy(
                                fixture.identity.identity(),
                                policy.document(),
                                fixture.policy.requesterPermissions(),
                                fixture.policy.reviewerPermissions(),
                                100,
                                65536,
                                10000,
                                4,
                                2,
                                50);
                var store =
                        new JdbcApprovalStore(
                                fixture.primary,
                                "versioned-store",
                                "versioned-catalog",
                                approval,
                                fixture.keys,
                                fixture.witness,
                                fixture.clock,
                                2);
                store.initializeSchema();
                var requester = fixture.requester();
                var reviewer = fixture.reviewer();
                var request = new AtomicReference<ApprovalRequest>();
                var scope =
                        new DelegationScope("task", false, Map.of("read", parameters), Map.of());
                try (var resolver = new JdbcApprovalResolver(store, requester);
                        var control =
                                AgentControl.builder(policy)
                                        .reviewedTools(List.of(lease))
                                        .audit(r -> {})
                                        .approvalTimeout(Duration.ofSeconds(4))
                                        .approvalResolver(
                                                r -> {
                                                    request.set(r);
                                                    return resolver.resolve(r);
                                                })
                                        .build();
                        var permit = requester.permit("agent", policy, scope);
                        var session =
                                control.openAuthorizedSession(
                                        permit, new TaskLimits(fixture.budget, 10, 1, 0), null)) {
                    var executor = Executors.newSingleThreadExecutor();
                    try {
                        var result =
                                executor.submit(
                                        () ->
                                                session.reviewedToolOperation(
                                                        lease,
                                                        Map.of("value", "reviewed-effective"),
                                                        x -> x,
                                                        backend::invoke,
                                                        value -> value,
                                                        (original, value) -> (String) value));
                        ApprovalReview view = null;
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                        while (view == null && System.nanoTime() < deadline) {
                            if (request.get() != null) {
                                try {
                                    view = store.review(reviewer, request.get().requestId());
                                } catch (GovernanceException missing) {
                                    if (!missing.code().equals("approval_request_missing"))
                                        throw missing;
                                }
                            }
                            if (view == null) Thread.sleep(10);
                        }
                        assertNotNull(view);
                        assertEquals(lease.identity(), view.request().action().toolBinding());
                        assertEquals(0, backend.calls.get());
                        store.decide(reviewer, view, true);
                        assertEquals("backend-ok", result.get(5, TimeUnit.SECONDS));
                        assertEquals(1, backend.calls.get());
                    } finally {
                        executor.shutdownNow();
                    }
                }
            }
        }
    }

    static final class Backend implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final HttpClient client = HttpClient.newHttpClient();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> body = new AtomicReference<>();

        Backend() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext(
                    "/tool",
                    exchange -> {
                        calls.incrementAndGet();
                        body.set(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        byte[] response = "backend-ok".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                        exchange.close();
                    });
            server.start();
        }

        String invoke(Object parameters) {
            try {
                return client.send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + server.getAddress().getPort()
                                                                + "/tool"))
                                        .timeout(Duration.ofSeconds(2))
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        JsonValues.write(parameters)))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .body();
            } catch (Exception failure) {
                throw new IllegalStateException("Synthetic backend failed");
            }
        }

        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
