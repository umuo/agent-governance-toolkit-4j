package io.agt.delegation;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import io.agt.core.flow.*;
import io.agt.provenance.JdbcLabelStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RemoteFlowProcessTest {
    @TempDir Path directory;

    @BeforeAll
    static void keys() throws Exception {
        DelegationTest.keyPair();
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
                                    RemoteFlowWorker.class.getName())
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
            try {
                input.write(JsonValues.write(config) + "\n");
                input.flush();
                assertEquals("READY", read(12));
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        String read(int timeout) throws Exception {
            return reader.submit(output::readLine).get(timeout, TimeUnit.SECONDS);
        }

        String run(String command) throws Exception {
            input.write(command + "\n");
            input.flush();
            return read(6);
        }

        void quit() throws Exception {
            if (process.isAlive()) {
                input.write("QUIT\n");
                input.flush();
            }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        }

        public void close() {
            process.destroyForcibly();
            reader.shutdownNow();
            try {
                input.close();
                output.close();
            } catch (IOException ignored) {
            }
        }
    }

    final class AuthorityHost implements AutoCloseable {
        final JdbcLabelStore labels;
        final InMemoryGrantRegistry registry = new InMemoryGrantRegistry(Clock.systemUTC());
        final AgentControl control;
        final HostSession source;
        final DelegationAuthority authority;
        final DelegationAuthority.Root root;
        final TestGrantEndpoint endpoint;

        AuthorityHost(boolean initialize, boolean memoryPublic) throws Exception {
            var db = new JdbcDataSource();
            db.setURL(
                    "jdbc:h2:file:"
                            + directory.resolve("labels")
                            + ";WRITE_DELAY=0;LOCK_TIMEOUT=2000");
            labels = new JdbcLabelStore(db, 2);
            if (initialize) labels.initializeSchema();
            control =
                    AgentControl.builder(RemoteFlowTest.policy())
                            .labelStore(labels)
                            .audit(record -> {})
                            .build();
            var original = RemoteFlowTest.flow(false);
            var flow =
                    new DataFlowPolicy(
                            Map.of(
                                    RemoteFlowTest.SECRET,
                                    DataLabel.CONFIDENTIAL,
                                    RemoteFlowTest.MEMORY,
                                    memoryPublic ? DataLabel.PUBLIC : DataLabel.CONFIDENTIAL),
                            original.sourceTools(),
                            original.sinkTools(),
                            original.neutralTools(),
                            original.inputLabel(),
                            original.modelClearance(),
                            original.outputClearance());
            source =
                    control.openTaskSession(
                            RemoteFlowTest.ID,
                            new TaskLimits(RemoteFlowTest.CALLS, 20, 4, 3, flow),
                            null,
                            RemoteFlowTest.scope());
            authority = DelegationTest.authority(registry, Clock.systemUTC());
            root = authority.register(source);
            endpoint = new TestGrantEndpoint(registry);
        }

        Worker worker() throws Exception {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            DelegationTest.AUDIENCE,
                            RemoteFlowTest.ID.permissions(),
                            RemoteFlowTest.scope(),
                            Duration.ofSeconds(45));
            return new Worker(
                    Map.of(
                            "token",
                            token.value(),
                            "publicKey",
                            Base64.getEncoder()
                                    .encodeToString(DelegationTest.keys.getPublic().getEncoded()),
                            "endpoint",
                            endpoint.uri().toString(),
                            "credential",
                            endpoint.credential,
                            "memoryFile",
                            directory.resolve("memory.txt").toString(),
                            "taskId",
                            source.taskSnapshot().taskId()));
        }

        public void close() {
            root.close();
            authority.close();
            source.close();
            control.close();
            endpoint.close();
        }
    }

    @Test
    void twoJvmReceiversShareTaintAndFreshAuthorityReopensPersistentMemoryLabels()
            throws Exception {
        String producer;
        try (var host = new AuthorityHost(true, false);
                var reader = host.worker();
                var sender = host.worker()) {
            producer = host.source.taskSnapshot().taskId();
            assertEquals("OK:PUBLIC:1", sender.run("PUBLIC"));
            assertEquals("OK:CONFIDENTIAL:0", reader.run("SECRET"));
            assertEquals("DENIED:data_flow_egress_denied:1", sender.run("PUBLIC"));
            assertEquals("OK:CONFIDENTIAL:0", reader.run("STORE"));
            assertEquals(
                    "fixture-sensitive-summary", Files.readString(directory.resolve("memory.txt")));
            assertEquals(
                    new StoredLabel(DataLabel.CONFIDENTIAL, 1),
                    host.labels.read(RemoteFlowTest.MEMORY).orElseThrow());
            assertEquals(
                    producer,
                    host.labels
                            .history(RemoteFlowTest.MEMORY, 1)
                            .orElseThrow()
                            .provenance()
                            .taskId());
            assertEquals(
                    LabelProvenance.Kind.WRITE,
                    host.labels
                            .history(RemoteFlowTest.MEMORY, 1)
                            .orElseThrow()
                            .provenance()
                            .kind());
            assertEquals(
                    LabelProvenance.Kind.CLASSIFICATION,
                    host.labels
                            .history(RemoteFlowTest.SECRET, 1)
                            .orElseThrow()
                            .provenance()
                            .kind());
            reader.quit();
            sender.quit();
        }
        // 新建注册表、运行时、授权服务和 JDBC 数据源，只保留实际数据文件及标签数据库。
        try (var reopened = new AuthorityHost(false, true);
                var worker = reopened.worker()) {
            assertNotEquals(producer, reopened.source.taskSnapshot().taskId());
            assertEquals(DataLabel.PUBLIC, reopened.root.flowSnapshot().label());
            assertEquals("OK:CONFIDENTIAL:0", worker.run("MEMORY"));
            assertEquals("DENIED:data_flow_egress_denied:0", worker.run("PUBLIC"));
            assertTrue(
                    reopened.root.flowSnapshot().sources().stream()
                            .anyMatch(
                                    value -> value.startsWith("stored:") && value.endsWith(":1")));
            assertEquals(
                    producer,
                    reopened.labels
                            .history(RemoteFlowTest.MEMORY, 1)
                            .orElseThrow()
                            .provenance()
                            .taskId());
            worker.quit();
        }
    }
}
