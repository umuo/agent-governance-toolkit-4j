package io.agt.delegation;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class RemoteProcessTest {
    @BeforeAll
    static void keys() throws Exception {
        DelegationTest.keyPair();
    }

    @Test
    void parentRevocationStopsASeparateJvmOverHttp() throws Exception {
        exercise(false);
    }

    @Test
    void registryDisconnectStopsASeparateJvmOverHttp() throws Exception {
        exercise(true);
    }

    private void exercise(boolean disconnect) throws Exception {
        var clock = Clock.systemUTC();
        var registry = new InMemoryGrantRegistry(clock);
        try (var sender = DelegationTest.control();
                var source =
                        sender.openTaskSession(
                                DelegationTest.SOURCE,
                                DelegationTest.sourceLimits(),
                                null,
                                DelegationTest.scope());
                var authority = DelegationTest.authority(registry, clock);
                var root = authority.register(source);
                var endpoint = new TestGrantEndpoint(registry)) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            DelegationTest.AUDIENCE,
                            Set.of("read"),
                            DelegationTest.scope(),
                            Duration.ofSeconds(30));
            var process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty(
                                            "surefire.test.class.path",
                                            System.getProperty("java.class.path")),
                                    RemoteWorker.class.getName())
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            var reader = Executors.newSingleThreadExecutor();
            try {
                var config =
                        Map.of(
                                "token",
                                token.value(),
                                "publicKey",
                                Base64.getEncoder()
                                        .encodeToString(
                                                DelegationTest.keys.getPublic().getEncoded()),
                                "endpoint",
                                endpoint.uri().toString(),
                                "credential",
                                endpoint.credential,
                                "taskId",
                                source.taskSnapshot().taskId());
                try (var input =
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                    input.write(JsonValues.write(config) + "\n");
                }
                var output =
                        new BufferedReader(
                                new InputStreamReader(
                                        process.getInputStream(), StandardCharsets.UTF_8));
                assertEquals("READY", reader.submit(output::readLine).get(12, TimeUnit.SECONDS));
                if (disconnect) endpoint.close();
                else source.revoke();
                String stopped = reader.submit(output::readLine).get(5, TimeUnit.SECONDS);
                assertNotNull(stopped);
                assertTrue(stopped.startsWith("STOPPED:"), "Remote task must stop");
                assertTrue(stopped.endsWith(":1"), "No additional side effects after stop");
                if (disconnect) assertTrue(stopped.contains("delegation_registry_unavailable"));
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), "Remote assertions failed");
            } finally {
                process.destroyForcibly();
                reader.shutdownNow();
            }
        }
    }
}
