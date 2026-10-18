package io.agt.delegation.jdbc;

import static io.agt.delegation.jdbc.DurableTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.agt.core.*;
import io.agt.core.delegation.DelegationScope;
import io.agt.core.execution.*;
import io.agt.core.policy.*;
import io.agt.delegation.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.interfaces.RSAPrivateKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ExternalWorkProcessTest {
    static final BudgetLimits CALLS = new BudgetLimits(1, 5, 60000);
    static final TrustedContext ID =
            new TrustedContext("coordinator", "tenant", "user", Set.of("execute"));
    @TempDir Path directory;

    static StructuredPolicy policy() {
        return StructuredPolicy.builder("remote-work-v1")
                .budget(CALLS)
                .tool(
                        "runJob",
                        new ToolRule(Set.of("execute"), ParameterRule.object(Map.of()), false))
                .build();
    }

    static void await(BooleanSupplier test) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!test.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(test.getAsBoolean(), "Expected authoritative external state");
    }

    @Test
    void remoteCancellationConfirmsOnlyAfterActualPidExitAndPreservesImportedHistory()
            throws Exception {
        try (var rig = new Rig();
                var a = rig.worker("a");
                var b = rig.worker("b")) {
            assertEquals("STARTED", a.call("START"));
            var pid = a.pid();
            assertTrue(pid.isAlive());
            assertEquals(1, rig.inspect().external().active());
            assertEquals("STOP_REQUESTED", a.call("REVOKE"));
            await(() -> !pid.isAlive());
            await(() -> rig.inspect().external().allTerminationConfirmed());
            var state = rig.inspect().external();
            assertEquals(2, state.entries().size());
            assertEquals(0, state.active());
            assertTrue(
                    state.entries().stream().anyMatch(e -> e.executionId().equals(rig.historyId)));
            assertTrue(
                    state.entries().stream()
                            .anyMatch(
                                    e ->
                                            e.kind().equals("DIRECT_PROCESS")
                                                    && e.state()
                                                            == ExternalExecutionRegistry.State
                                                                    .CANCEL_CONFIRMED));
            assertEquals("PONG", b.call("PING"));
            assertTrue(rig.source.externalExecutionSnapshot().allTerminationConfirmed());
            rig.assertMetadataOnly();
        }
    }

    @Test
    void killedSdkJvmLeavesLiveChildUnknownAndStopsSiblingBeforeDispatch() throws Exception {
        try (var rig = new Rig();
                var a = rig.worker("a");
                var b = rig.worker("b")) {
            assertEquals("STARTED", a.call("START"));
            var pid = a.pid();
            a.process.destroyForcibly();
            assertTrue(a.process.waitFor(5, TimeUnit.SECONDS));
            assertTrue(pid.isAlive(), "External process must remain alive after SDK node death");
            await(() -> rig.inspect().external().pendingCancellation() == 1);
            assertEquals("PONG", b.call("PING"));
            await(() -> rig.inspect().external().unknown() == 1);
            var unknown = rig.inspect().external();
            assertEquals(1, unknown.unknown());
            assertEquals(1, unknown.active());
            assertFalse(unknown.allTerminationConfirmed());
            assertTrue(pid.isAlive(), "Lease expiration cannot be counted as process termination");
            assertTrue(b.call("START").startsWith("DENIED:"));
            assertFalse(Files.exists(b.marker));
            assertEquals("external_execution_unconfirmed", rig.inspect().external().fault());
            rig.assertMetadataOnly();
        }
    }

    @Test
    void lostReceiptCanBeReconciledAfterTokenExpiryWithoutUnfreezingRoot() throws Exception {
        try (var rig = new Rig(true);
                var a = rig.worker("a")) {
            rig.dropReceipts = true;
            assertEquals("STARTED", a.call("START"));
            var pid = a.pid();
            assertEquals("STOP_REQUESTED", a.call("REVOKE"));
            await(() -> !pid.isAlive());
            String receipt = rig.receipts.poll(8, TimeUnit.SECONDS);
            assertNotNull(receipt);
            rig.clock.time.addAndGet(31000);
            // 到期第一次只请求取消，后续观察越过确认期限才能判为未知。
            rig.inspect();
            rig.clock.time.addAndGet(2100);
            assertEquals(1, rig.inspect().external().unknown());
            var response =
                    new GrantStatusService(rig.registry())
                            .handle(new AuthenticatedAgent("reader"), receipt);
            assertEquals(200, response.status());
            var state = rig.inspect().external();
            assertTrue(state.allTerminationConfirmed());
            assertEquals(0, state.active());
            assertEquals("external_execution_unconfirmed", state.fault());
            assertFalse(rig.inspect().active());
            assertTrue(rig.source.externalExecutionSnapshot().allTerminationConfirmed());
        }
    }

    final class Rig implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final Clock activeClock;
        final org.h2.jdbcx.JdbcDataSource data =
                dataSource(
                        "jdbc:h2:file:"
                                + directory.resolve("authority")
                                + ";WRITE_DELAY=0;LOCK_TIMEOUT=3000");
        final String credential = UUID.randomUUID().toString();
        final ExecutorService transport = Executors.newCachedThreadPool();
        final HttpServer endpoint;
        final AgentControl control;
        final HostSession source;
        final DelegationAuthority authority;
        final DelegationAuthority.Root root;
        final String historyId;
        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> receipts = new LinkedBlockingQueue<>();
        volatile boolean dropReceipts;
        final DelegationScope scope =
                new DelegationScope(
                        "remote-job",
                        false,
                        Map.of("runJob", ParameterRule.object(Map.of())),
                        Map.of());

        Rig() throws Exception {
            this(false);
        }

        Rig(boolean manualClock) throws Exception {
            activeClock = manualClock ? clock : Clock.systemUTC();
            var registry = registry();
            registry.initializeSchema();
            var service = new GrantStatusService(registry);
            endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            endpoint.setExecutor(transport);
            endpoint.createContext(
                    "/grants",
                    exchange -> {
                        try (exchange) {
                            if (!"POST".equals(exchange.getRequestMethod())
                                    || !("Bearer " + credential)
                                            .equals(
                                                    exchange.getRequestHeaders()
                                                            .getFirst("Authorization"))) {
                                exchange.sendResponseHeaders(403, -1);
                                return;
                            }
                            var bytes = exchange.getRequestBody().readNBytes(65537);
                            if (bytes.length > 65536) {
                                exchange.sendResponseHeaders(400, -1);
                                return;
                            }
                            String json = new String(bytes, StandardCharsets.UTF_8);
                            var body = JsonValues.parseObject(json);
                            requests.add(body);
                            GrantStatusService.Response response;
                            if (dropReceipts && "work_finish".equals(body.get("op"))) {
                                receipts.add(json);
                                response =
                                        new GrantStatusService.Response(
                                                503,
                                                Map.of("code", "delegation_registry_unavailable"));
                            } else
                                response = service.handle(new AuthenticatedAgent("reader"), json);
                            var output =
                                    JsonValues.write(response.body())
                                            .getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(response.status(), output.length);
                            exchange.getResponseBody().write(output);
                        }
                    });
            endpoint.start();
            control = AgentControl.builder(policy()).audit(record -> {}).build();
            source = control.openTaskSession(ID, new TaskLimits(CALLS, 2, 10, 3), null, scope);
            var history =
                    source.registerExternalExecution(
                            "LOCAL_CHECK",
                            () -> CompletableFuture.completedFuture(TerminationEvidence.CONFIRMED));
            history.started();
            history.finished(TerminationEvidence.CONFIRMED);
            historyId = history.id();
            authority =
                    new DelegationAuthority(
                            "urn:agt:external-test",
                            "key-1",
                            (RSAPrivateKey) KEYS.getPrivate(),
                            registry,
                            activeClock);
            root = authority.register(source);
        }

        JdbcGrantRegistry registry() {
            return new JdbcGrantRegistry(
                    data, "primary", activeClock, null, 2, JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
        }

        RegistryInspection inspect() {
            return registry().inspect(root.lease().grantId(), AUDITOR);
        }

        Worker worker(String name) throws Exception {
            Path place = Files.createDirectory(directory.resolve(name));
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            "worker",
                            ID.permissions(),
                            scope,
                            Duration.ofSeconds(30));
            var worker =
                    new Worker(
                            place,
                            Map.of(
                                    "token",
                                    token.value(),
                                    "publicKey",
                                    Base64.getEncoder()
                                            .encodeToString(KEYS.getPublic().getEncoded()),
                                    "endpoint",
                                    "http://127.0.0.1:"
                                            + endpoint.getAddress().getPort()
                                            + "/grants",
                                    "credential",
                                    credential,
                                    "directory",
                                    place.toString()));
            try {
                assertEquals("READY", worker.read());
                return worker;
            } catch (Throwable failure) {
                worker.close();
                throw failure;
            }
        }

        void assertMetadataOnly() {
            for (var request : requests) {
                String json = JsonValues.write(request);
                assertFalse(json.contains(directory.toString()));
                assertFalse(json.contains(ExternalNativeFixture.class.getName()));
                assertFalse(json.contains("job.pid"));
            }
        }

        public void close() throws Exception {
            root.close();
            authority.close();
            source.close();
            control.close();
            endpoint.stop(0);
            transport.shutdownNow();
            try (var files = Files.walk(directory)) {
                for (var file :
                        files.filter(p -> p.getFileName().toString().equals("job.pid")).toList()) {
                    long pid = Long.parseLong(Files.readString(file));
                    var process = ProcessHandle.of(pid);
                    if (process.isPresent() && process.get().isAlive()) {
                        process.get().destroyForcibly();
                        try {
                            process.get().onExit().get(5, TimeUnit.SECONDS);
                        } catch (TimeoutException stale) {
                            assertFalse(process.get().isAlive(), "Owned fixture process must stop");
                        }
                    }
                }
            }
        }
    }

    static final class Worker implements AutoCloseable {
        final Process process;
        final Path marker;
        final BufferedWriter input;
        final BufferedReader output;
        final ExecutorService reader = Executors.newSingleThreadExecutor();

        Worker(Path directory, Map<String, Object> config) throws Exception {
            marker = directory.resolve("job.pid");
            process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty(
                                            "surefire.test.class.path",
                                            System.getProperty("java.class.path")),
                                    ExternalWorkWorker.class.getName())
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            input =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    process.getOutputStream(), StandardCharsets.UTF_8));
            output =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8));
            input.write(JsonValues.write(config));
            input.newLine();
            input.flush();
        }

        String read() throws Exception {
            return reader.submit(output::readLine).get(12, TimeUnit.SECONDS);
        }

        String call(String value) throws Exception {
            input.write(value);
            input.newLine();
            input.flush();
            return read();
        }

        ProcessHandle pid() throws Exception {
            return ProcessHandle.of(Long.parseLong(Files.readString(marker))).orElseThrow();
        }

        public void close() {
            if (process.isAlive())
                try {
                    input.write("EXIT");
                    input.newLine();
                    input.flush();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (Exception ignored) {
                    process.destroyForcibly();
                }
            reader.shutdownNow();
        }
    }
}
