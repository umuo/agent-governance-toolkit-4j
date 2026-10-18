package io.agt.approval.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.approval.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApprovalProcessTest {
    @TempDir Path temporary;

    @Test
    void twoIndependentWorkersCanDispatchOnlyOneBusinessRequest() throws Exception {
        try (var fixture = new ApprovalFixture();
                var backend = new ApprovalRuntimeTest.Backend()) {
            var server =
                    Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
            try {
                var token =
                        ApprovalFixture.sign(
                                fixture.claims("requester", "tenant-a", "human", "requester"));
                var actor = fixture.verifier.verify(token);
                var request = longer(fixture.request(actor), fixture.clock.millis() + 9000);
                var claim = fixture.store.submit(actor, request);
                var reviewer = fixture.reviewer();
                fixture.store.decide(
                        reviewer, fixture.store.review(reviewer, request.requestId()), true);
                var config = config(fixture, backend, server, claim, token.value(), "normal");
                try (var first = worker(config, "first");
                        var second = worker(config, "second")) {
                    var outcomes = List.of(first.line(), second.line());
                    assertEquals(1, outcomes.stream().filter("ALLOW"::equals).count());
                    assertEquals(1, outcomes.stream().filter("REFUSED"::equals).count());
                    first.finished();
                    second.finished();
                    assertEquals(1, backend.calls.get());
                }
            } finally {
                server.stop();
            }
        }
    }

    @Test
    void killedAfterConsumptionCommitBeforeWitnessNeverDispatchesOnRecovery() throws Exception {
        try (var fixture = new ApprovalFixture();
                var backend = new ApprovalRuntimeTest.Backend()) {
            var server =
                    Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
            try {
                var token =
                        ApprovalFixture.sign(
                                fixture.claims("requester", "tenant-a", "human", "requester"));
                var actor = fixture.verifier.verify(token);
                var request = longer(fixture.request(actor), fixture.clock.millis() + 9000);
                var claim = fixture.store.submit(actor, request);
                var reviewer = fixture.reviewer();
                fixture.store.decide(
                        reviewer, fixture.store.review(reviewer, request.requestId()), true);
                try (var crashing =
                        worker(
                                config(
                                        fixture,
                                        backend,
                                        server,
                                        claim,
                                        token.value(),
                                        "crash-before-witness"),
                                "crashing")) {
                    assertEquals("COMMIT_PENDING", crashing.line());
                    crashing.process.destroyForcibly();
                    assertTrue(crashing.process.waitFor(3, TimeUnit.SECONDS));
                }
                try (var recovery =
                        worker(
                                config(fixture, backend, server, claim, token.value(), "normal"),
                                "recovery")) {
                    assertEquals("REFUSED", recovery.line());
                    recovery.finished();
                }
                assertEquals(0, backend.calls.get());
                assertEquals(
                        JdbcApprovalStore.Status.CONSUMED,
                        fixture.store.receipt(actor, request.requestId()).status());
            } finally {
                server.stop();
            }
        }
    }

    private ApprovalRequest longer(ApprovalRequest request, long expiry) {
        return new ApprovalRequest(
                request.requestId(),
                request.actionIdentity(),
                request.policyVersion(),
                request.action(),
                expiry);
    }

    private Map<String, Object> config(
            ApprovalFixture fixture,
            ApprovalRuntimeTest.Backend backend,
            Server server,
            ApprovalClaim claim,
            String token,
            String mode) {
        var config = new LinkedHashMap<String, Object>();
        config.put("primary", tcp(fixture.primary.getURL(), server.getPort()));
        config.put("witness", tcp(fixture.witnessData.getURL(), server.getPort()));
        config.put(
                "aes",
                Base64.getEncoder()
                        .encodeToString(fixture.keys.active().encryption().getEncoded()));
        config.put(
                "mac",
                Base64.getEncoder()
                        .encodeToString(fixture.keys.active().authentication().getEncoded()));
        config.put(
                "publicKey",
                Base64.getEncoder().encodeToString(ApprovalFixture.RSA.getPublic().getEncoded()));
        config.put("token", token);
        config.put("requestId", claim.requestId());
        config.put("actionIdentity", claim.actionIdentity());
        config.put("claim", claim.secret());
        config.put("mode", mode);
        config.put(
                "backend", "http://127.0.0.1:" + backend.server.getAddress().getPort() + "/tool");
        return config;
    }

    private String tcp(String url, int port) {
        return url.replace("jdbc:h2:mem:", "jdbc:h2:tcp://127.0.0.1:" + port + "/mem:");
    }

    private Worker worker(Map<String, Object> config, String name) throws Exception {
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("surefire.test.class.path"),
                                ApprovalWorker.class.getName())
                        .redirectError(temporary.resolve(name + ".log").toFile())
                        .start();
        var input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        input.write(JsonValues.write(config) + "\n");
        input.flush();
        return new Worker(process, input);
    }

    private static final class Worker implements AutoCloseable {
        final Process process;
        final Writer input;
        final BufferedReader output;
        final ExecutorService reader = Executors.newSingleThreadExecutor();

        Worker(Process process, Writer input) {
            this.process = process;
            this.input = input;
            output =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8));
        }

        String line() throws Exception {
            return reader.submit(output::readLine).get(8, TimeUnit.SECONDS);
        }

        void finished() throws Exception {
            assertTrue(process.waitFor(3, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        }

        public void close() throws Exception {
            process.destroyForcibly();
            process.waitFor(3, TimeUnit.SECONDS);
            input.close();
            output.close();
            reader.shutdownNow();
        }
    }
}
