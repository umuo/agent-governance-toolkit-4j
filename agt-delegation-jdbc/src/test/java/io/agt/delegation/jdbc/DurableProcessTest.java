package io.agt.delegation.jdbc;

import static io.agt.delegation.jdbc.DurableTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.delegation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.tools.Server;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DurableProcessTest {
    @TempDir Path directory;

    @Test
    void separateJvmsShareToolAndTokenLimitsAndColdReaderKeepsTotals() throws Exception {
        try (var rig = new Rig(200);
                var a = rig.worker();
                var b = rig.worker()) {
            assertEquals("OK:1", a.call("MODEL"));
            assertEquals("OK:1", b.call("MODEL"));
            assertEquals("OK:2", a.call("TOOL"));
            assertEquals("OK:2", b.call("TOOL"));
            assertEquals("OK:3", a.call("TOOL"));
            assertEquals("DENIED:task_tool_budget_exceeded:2", b.call("TOOL"));
            assertEquals("DENIED:task_token_budget_exceeded:3", a.call("MODEL"));
            assertEquals(100, rig.inspect().usage().settledTokens());
            assertEquals(
                    0,
                    new java.math.BigDecimal("0.00012")
                            .compareTo(rig.inspect().usage().settledCost()));
            a.close();
            b.close();
            rig.root.close();
            try (var reader = new Worker(rig.config(true))) {
                assertEquals("STATE:3:100:0:0", reader.read());
                assertTrue(reader.process.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, reader.process.exitValue());
            }
        }
    }

    @Test
    void killedDispatchedJvmKeepsHoldAndStopsSiblingAcrossFreshRegistryInstances()
            throws Exception {
        try (var rig = new Rig(1000);
                var a = rig.worker();
                var b = rig.worker()) {
            assertEquals("DISPATCHED:1", a.call("HANG"));
            a.process.destroyForcibly();
            assertTrue(a.process.waitFor(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            RegistryInspection state;
            do {
                state = rig.inspect();
                if (state.usage().uncertainCalls() == 1) break;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            assertEquals(150, state.usage().heldTokens());
            assertEquals(1, state.usage().uncertainCalls());
            assertEquals("model_usage_unconfirmed", state.usage().fault());
            String denied = b.call("MODEL");
            assertTrue(denied.startsWith("DENIED:"), denied);
            assertTrue(denied.endsWith(":0"), "Sibling must not enter native model call");
            try (var reader = new Worker(rig.config(true))) {
                assertEquals("STATE:0:0:150:1", reader.read());
                assertTrue(reader.process.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, reader.process.exitValue());
            }
        }
    }

    final class Rig implements AutoCloseable {
        final Server server;
        final org.h2.jdbcx.JdbcDataSource data;
        final AgentControl control;
        final HostSession source;
        final DelegationAuthority authority;
        final DelegationAuthority.Root root;
        final String password = UUID.randomUUID().toString();

        Rig(long tokens) throws Exception {
            server =
                    Server.createTcpServer(
                                    "-tcpPort",
                                    "0",
                                    "-tcpDaemon",
                                    "-baseDir",
                                    directory.toString(),
                                    "-ifNotExists")
                            .start();
            data =
                    dataSource(
                            "jdbc:h2:tcp://127.0.0.1:"
                                    + server.getPort()
                                    + "/authority;WRITE_DELAY=0;LOCK_TIMEOUT=3000");
            data.setUser("sa");
            data.setPassword(password);
            var registry = registry();
            registry.initializeSchema();
            control = AgentControl.builder(policy()).audit(record -> {}).build();
            source =
                    control.openTaskSession(
                            SOURCE,
                            new TaskLimits(
                                    new BudgetLimits(10, 3, 60000), 20, 10, 3, null, usage(tokens)),
                            null,
                            scope());
            authority =
                    new DelegationAuthority(
                            ISSUER,
                            "key-1",
                            (RSAPrivateKey) KEYS.getPrivate(),
                            registry,
                            Clock.systemUTC());
            root = authority.register(source);
        }

        JdbcGrantRegistry registry() {
            return new JdbcGrantRegistry(
                    data,
                    "primary",
                    Clock.systemUTC(),
                    null,
                    2,
                    JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
        }

        RegistryInspection inspect() {
            return registry().inspect(root.lease().grantId(), AUDITOR);
        }

        Map<String, Object> config(boolean inspect) {
            var values = new LinkedHashMap<String, Object>();
            values.put("url", data.getURL());
            values.put("user", "sa");
            values.put("password", password);
            values.put("root", root.lease().grantId());
            values.put("inspect", inspect);
            values.put(
                    "publicKey", Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
            if (!inspect)
                values.put(
                        "token",
                        authority
                                .issue(
                                        root.lease(),
                                        "reader",
                                        AUDIENCE,
                                        SOURCE.permissions(),
                                        scope(),
                                        Duration.ofSeconds(30))
                                .value());
            return values;
        }

        Worker worker() throws Exception {
            var worker = new Worker(config(false));
            try {
                assertEquals("READY", worker.read());
                return worker;
            } catch (Throwable failure) {
                worker.close();
                throw failure;
            }
        }

        public void close() {
            root.close();
            authority.close();
            source.close();
            control.close();
            server.stop();
        }
    }

    static final class Worker implements AutoCloseable {
        final Process process;
        final BufferedWriter input;
        final BufferedReader output;
        final ExecutorService reader = Executors.newSingleThreadExecutor();

        Worker(Map<String, Object> config) throws Exception {
            process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty(
                                            "surefire.test.class.path",
                                            System.getProperty("java.class.path")),
                                    DurableWorker.class.getName())
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

        String call(String command) throws Exception {
            input.write(command);
            input.newLine();
            input.flush();
            return read();
        }

        public void close() {
            if (process.isAlive()) {
                try {
                    input.write("EXIT");
                    input.newLine();
                    input.flush();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (Exception ignored) {
                    process.destroyForcibly();
                }
            }
            reader.shutdownNow();
        }
    }
}
