package io.agt.langchain4j;

import static io.agt.langchain4j.FragmentProvenanceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.agt.core.*;
import io.agt.core.data.*;
import io.agt.core.delegation.DelegationScope;
import io.agt.core.flow.*;
import io.agt.delegation.*;
import io.agt.delegation.jdbc.JdbcGrantRegistry;
import io.agt.provenance.JdbcLabelStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FragmentRemoteProcessTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoSdkJvmsUseActionBoundMetadataOnlyRpcAndDurableFragmentState(boolean versioned)
            throws Exception {
        var ds =
                data(
                        "jdbc:h2:file:"
                                + directory.resolve("authority")
                                + ";WRITE_DELAY=0;LOCK_TIMEOUT=3000");
        var labels = new JdbcLabelStore(ds, 2);
        labels.initializeSchema();
        var contentSensitive = DataFragment.document("synthetic-sensitive-segment");
        var sensitive =
                versioned
                        ? DataFragment.document(
                                "synthetic-sensitive-segment",
                                new DocumentSegment("manual-sensitive", 1, "chunk"))
                        : contentSensitive;
        labels.recordFragment(
                SOURCE,
                contentSensitive,
                DataLabel.CONFIDENTIAL,
                new LabelProvenance(
                        "ingestor",
                        "classify",
                        List.of("host-policy"),
                        LabelProvenance.Kind.CLASSIFICATION));
        if (versioned) {
            labels.publishDocument(
                    SOURCE,
                    "manual-public",
                    0,
                    Map.of("chunk", DataFragment.document("public-segment").digest()),
                    DataLabel.PUBLIC,
                    new LabelProvenance(
                            "ingestor",
                            "publish-public",
                            List.of("host-policy"),
                            LabelProvenance.Kind.CLASSIFICATION));
            labels.publishDocument(
                    SOURCE,
                    "manual-sensitive",
                    0,
                    Map.of("chunk", contentSensitive.digest()),
                    DataLabel.PUBLIC,
                    new LabelProvenance(
                            "ingestor",
                            "publish-sensitive",
                            List.of("host-policy"),
                            LabelProvenance.Kind.CLASSIFICATION));
        }
        var registry =
                new JdbcGrantRegistry(
                        ds,
                        "primary",
                        Clock.systemUTC(),
                        labels,
                        2,
                        JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
        registry.initializeSchema();
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        var requests = new CopyOnWriteArrayList<Map<String, Object>>();
        var executor = Executors.newCachedThreadPool();
        var endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.setExecutor(executor);
        String credential = UUID.randomUUID().toString();
        var service = new GrantStatusService(registry);
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
                        requests.add(JsonValues.parseObject(json));
                        var response = service.handle(new AuthenticatedAgent("reader"), json);
                        byte[] body =
                                JsonValues.write(response.body()).getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(response.status(), body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        endpoint.start();
        var scope =
                new DelegationScope(
                        "fragment-task",
                        true,
                        Map.of(),
                        Map.of(SOURCE, Set.of(DataOperation.RETRIEVE)));
        try (var control =
                        AgentControl.builder(policy())
                                .labelStore(labels)
                                .audit(record -> {})
                                .build();
                var source =
                        control.openTaskSession(
                                ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC), null, scope);
                var authority =
                        new DelegationAuthority(
                                "urn:agt:fragment-test",
                                "key-1",
                                (RSAPrivateKey) keys.getPrivate(),
                                registry,
                                Clock.systemUTC());
                var root = authority.register(source)) {
            URI uri = URI.create("http://127.0.0.1:" + endpoint.getAddress().getPort() + "/grants");
            assertEquals(
                    "PUBLIC:1:1",
                    worker(
                            authority,
                            root,
                            scope,
                            uri,
                            credential,
                            keys,
                            "public-segment",
                            versioned));
            var publicFragment =
                    versioned
                            ? DataFragment.document(
                                    "public-segment",
                                    new DocumentSegment("manual-public", 1, "chunk"))
                            : DataFragment.document("public-segment");
            var history = labels.fragmentHistory(SOURCE, publicFragment, 1).orElseThrow();
            assertEquals(LabelProvenance.Kind.CLASSIFICATION, history.provenance().kind());
            assertEquals(source.taskSnapshot().taskId(), history.provenance().taskId());
            assertNotEquals("flow-check", history.provenance().actionId());
            assertTrue(
                    requests.stream()
                            .anyMatch(
                                    request ->
                                            request.get("op").equals("flow_validate_action")
                                                    && request.get("actionId")
                                                            .equals(
                                                                    history.provenance()
                                                                            .actionId())));
            assertEquals(
                    "CONFIDENTIAL:1:0",
                    worker(
                            authority,
                            root,
                            scope,
                            uri,
                            credential,
                            keys,
                            "synthetic-sensitive-segment",
                            versioned));
            var reopened =
                    new JdbcGrantRegistry(
                            ds,
                            "primary",
                            Clock.systemUTC(),
                            new JdbcLabelStore(ds, 2),
                            2,
                            JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
            var state =
                    reopened.inspect(
                            root.lease().grantId(),
                            new TrustedContext(
                                    "auditor", "tenant", "user", Set.of("governance:inspect")));
            assertEquals(DataLabel.CONFIDENTIAL, state.flow().label());
            assertTrue(state.flow().sources().contains("fragment:" + sensitive.key(SOURCE)));
            assertEquals(DataLabel.CONFIDENTIAL, source.dataFlowSnapshot().label());
            if (versioned)
                labels.publishDocument(
                        SOURCE,
                        "manual-public",
                        1,
                        Map.of("chunk", DataFragment.document("updated-public-text").digest()),
                        DataLabel.PUBLIC,
                        new LabelProvenance(
                                "ingestor",
                                "update-public",
                                List.of("host-policy"),
                                LabelProvenance.Kind.CLASSIFICATION));
            var failure =
                    assertThrows(
                            GovernanceException.class,
                            () ->
                                    source.disclose(
                                            "result",
                                            value -> value,
                                            (original, value) -> (String) value));
            assertEquals(
                    versioned ? "document_version_stale" : "data_flow_output_denied",
                    failure.code());
            for (var request : requests) {
                String json = JsonValues.write(request);
                assertFalse(json.contains("public-segment"));
                assertFalse(json.contains("synthetic-sensitive-segment"));
                assertFalse(json.contains("synthetic-query"));
                assertFalse(json.contains("synthetic-model-input"));
            }
        } finally {
            endpoint.stop(0);
            executor.shutdownNow();
        }
    }

    private String worker(
            DelegationAuthority authority,
            DelegationAuthority.Root root,
            DelegationScope scope,
            URI uri,
            String credential,
            KeyPair keys,
            String text,
            boolean versioned)
            throws Exception {
        var token =
                authority.issue(
                        root.lease(),
                        "reader",
                        "worker",
                        ID.permissions(),
                        scope,
                        Duration.ofSeconds(30));
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                FragmentWorker.class.getName())
                        .redirectError(ProcessBuilder.Redirect.PIPE)
                        .start();
        var reader = Executors.newSingleThreadExecutor();
        try {
            try (var input =
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                input.write(
                        JsonValues.write(
                                        Map.of(
                                                "token",
                                                token.value(),
                                                "publicKey",
                                                Base64.getEncoder()
                                                        .encodeToString(
                                                                keys.getPublic().getEncoded()),
                                                "endpoint",
                                                uri.toString(),
                                                "credential",
                                                credential,
                                                "text",
                                                text,
                                                "documentId",
                                                versioned
                                                        ? (text.equals("public-segment")
                                                                ? "manual-public"
                                                                : "manual-sensitive")
                                                        : ""))
                                + "\n");
            }
            var output =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.submit(output::readLine).get(20, TimeUnit.SECONDS);
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(
                    0,
                    process.exitValue(),
                    "Worker assertions failed: "
                            + new String(
                                    process.getErrorStream().readNBytes(8192),
                                    StandardCharsets.UTF_8));
            return status;
        } finally {
            process.destroyForcibly();
            reader.shutdownNow();
        }
    }
}
