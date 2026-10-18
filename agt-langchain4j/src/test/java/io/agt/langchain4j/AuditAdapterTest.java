package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.agt.audit.jdbc.*;
import io.agt.core.*;
import io.agt.core.audit.*;
import io.agt.core.policy.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.KeyGenerator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuditAdapterTest {
    static final TrustedContext ID = new TrustedContext("agent", "tenant", "user", Set.of("read"));
    static final TrustedContext REVIEWER =
            new TrustedContext(
                    "reviewer",
                    "tenant",
                    "user",
                    Set.of("governance:audit:read", "governance:audit:body"));

    JdbcAuditVault vault() throws Exception {
        var primary = new JdbcDataSource();
        primary.setURL("jdbc:h2:mem:adapter-main-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var anchor = new JdbcDataSource();
        anchor.setURL("jdbc:h2:mem:adapter-witness-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var witness = new JdbcAuditWitness(anchor, 3);
        witness.initializeSchema();
        var aes = KeyGenerator.getInstance("AES");
        aes.init(256);
        var hmac = KeyGenerator.getInstance("HmacSHA256");
        hmac.init(256);
        var version = new AuditKeys.Version("key-1", aes.generateKey(), hmac.generateKey());
        var keys =
                new AuditKeys() {
                    public Version active() {
                        return version;
                    }

                    public Version find(String id) {
                        return version.id().equals(id) ? version : null;
                    }
                };
        var body =
                new AuditBodyPolicy(
                        Set.of(
                                InterventionPoint.PRE_TOOL_CALL,
                                InterventionPoint.POST_TOOL_CALL,
                                InterventionPoint.PRE_MODEL_CALL,
                                InterventionPoint.POST_MODEL_CALL),
                        Set.of("payload", "messages", "text", "response"),
                        true,
                        50000,
                        16000);
        var vault =
                new JdbcAuditVault(
                        primary,
                        "native-audit",
                        UUID.randomUUID().toString(),
                        new AuditVaultPolicy(body, 86400000L, 2592000000L, 1000),
                        keys,
                        witness,
                        Clock.systemUTC(),
                        3);
        vault.initializeSchema();
        return vault;
    }

    static StructuredPolicy policy() {
        return StructuredPolicy.builder("native-audit-v1")
                .tool(
                        "read",
                        new ToolRule(
                                Set.of("read"),
                                ParameterRule.object(
                                        Map.of("payload", ParameterRule.string(null)), "payload"),
                                false))
                .build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeToolSyncAndAsyncCaptureUsesActualSessionAndDoesNotRewriteBusinessInput(boolean async)
            throws Exception {
        var vault = vault();
        var actual = new AtomicReference<String>();
        String sessionId;
        try (var control = AgentControl.builder(policy()).audit(vault).build();
                var session = control.openSession(ID)) {
            sessionId = session.sessionId();
            var tool =
                    new LangChain4jGovernance(control)
                            .guardTool(
                                    "read",
                                    new dev.langchain4j.service.tool.ToolExecutor() {
                                        public String execute(
                                                ToolExecutionRequest request, Object memory) {
                                            actual.set(request.arguments());
                                            return "password=synthetic-result-secret";
                                        }

                                        public CompletableFuture<
                                                        dev.langchain4j.service.tool
                                                                .ToolExecutionResult>
                                                executeAsync(
                                                        ToolExecutionRequest request,
                                                        dev.langchain4j.invocation.InvocationContext
                                                                context) {
                                            return CompletableFuture.completedFuture(
                                                    dev.langchain4j.service.tool.ToolExecutionResult
                                                            .builder()
                                                            .resultText(execute(request, null))
                                                            .build());
                                        }
                                    });
            var request =
                    ToolExecutionRequest.builder()
                            .id("model-call")
                            .name("read")
                            .arguments("{\"payload\":\"Bearer synthetic-input-secret\"}")
                            .build();
            String result =
                    async
                            ? tool.executeAsync(request, null).get(4, TimeUnit.SECONDS).resultText()
                            : tool.execute(request, null);
            assertEquals("password=synthetic-result-secret", result);
        }
        assertTrue(actual.get().contains("synthetic-input-secret"));
        var events = vault.readMetadata(REVIEWER, 0, 100);
        int captures = 0;
        for (var row : events)
            if (row.bodyAvailable()) {
                assertTrue(JsonValues.write(row.metadata()).contains(sessionId));
                String body = JsonValues.write(vault.readBody(REVIEWER, row.sequence()));
                assertFalse(body.contains("synthetic-input-secret"));
                assertFalse(body.contains("synthetic-result-secret"));
                captures++;
            }
        assertEquals(2, captures);
    }

    @Test
    void nativeModelRequestAndResponseReachEncryptedGovernedCapture() throws Exception {
        var vault = vault();
        var model =
                new NativeIntegrationTest.NativeModel(
                        request -> LangChain4jGovernanceTest.text("provider-result"));
        try (var control = AgentControl.builder(policy()).audit(vault).build();
                var session = control.openSession(ID)) {
            assertEquals(
                    "provider-result",
                    new LangChain4jGovernance(control)
                            .guardModel(model)
                            .chat("Bearer synthetic-model-secret"));
        }
        var captures =
                vault.readMetadata(REVIEWER, 0, 100).stream()
                        .filter(JdbcAuditVault.RecordView::bodyAvailable)
                        .toList();
        assertEquals(2, captures.size());
        String bodies =
                JsonValues.write(
                        captures.stream()
                                .map(row -> vault.readBody(REVIEWER, row.sequence()))
                                .toList());
        assertFalse(bodies.contains("synthetic-model-secret"));
        assertTrue(bodies.contains("provider-result"));
    }
}
