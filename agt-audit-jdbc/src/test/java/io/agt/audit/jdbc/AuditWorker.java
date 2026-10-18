package io.agt.audit.jdbc;

import io.agt.core.*;
import io.agt.core.audit.*;
import io.agt.core.policy.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.h2.jdbcx.JdbcDataSource;

/** 独立 SDK 工作进程；仅测试合成密钥/凭据经 stdin 传递，不进入 argv 或日志。 */
public final class AuditWorker {
    static AuditVaultPolicy vaultPolicy() {
        return new AuditVaultPolicy(
                new AuditBodyPolicy(
                        Set.of(InterventionPoint.PRE_TOOL_CALL, InterventionPoint.POST_TOOL_CALL),
                        Set.of("payload"),
                        true,
                        10000,
                        4096),
                86400000L,
                2592000000L,
                1000);
    }

    static AuditKeys keys(Map<String, Object> config) {
        var version =
                new AuditKeys.Version(
                        "key-1",
                        new SecretKeySpec(
                                Base64.getDecoder().decode((String) config.get("encryptionKey")),
                                "AES"),
                        new SecretKeySpec(
                                Base64.getDecoder()
                                        .decode((String) config.get("authenticationKey")),
                                "HmacSHA256"));
        return new AuditKeys() {
            public Version active() {
                return version;
            }

            public Version find(String id) {
                return version.id().equals(id) ? version : null;
            }
        };
    }

    static JdbcDataSource data(String url, String credential) {
        var data = new JdbcDataSource();
        data.setURL(url);
        data.setUser("sa");
        data.setPassword(credential);
        return data;
    }

    public static void main(String[] ignored) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var config = JsonValues.parseObject(input.readLine());
        var primary =
                data((String) config.get("primary"), (String) config.get("primaryCredential"));
        var anchor = data((String) config.get("witness"), (String) config.get("witnessCredential"));
        var persistent = new JdbcAuditWitness(anchor, 3);
        AuditWitness witness =
                new AuditWitness() {
                    public AuditCheckpoint read(String catalog, String stream) {
                        return persistent.read(catalog, stream);
                    }

                    public boolean advance(AuditCheckpoint before, AuditCheckpoint next) {
                        if ("pause".equals(config.get("mode")) && next.sequence() == 2) {
                            System.out.println("COMMITTED_PENDING");
                            System.out.flush();
                            try {
                                input.readLine();
                            } catch (IOException failure) {
                                throw new GovernanceException("audit_witness_unavailable");
                            }
                            throw new GovernanceException("audit_witness_unavailable");
                        }
                        return persistent.advance(before, next);
                    }
                };
        var vault =
                new JdbcAuditVault(
                        primary,
                        "governance",
                        (String) config.get("catalog"),
                        vaultPolicy(),
                        keys(config),
                        witness,
                        Clock.systemUTC(),
                        3);
        var policy =
                StructuredPolicy.builder("process-audit-v1")
                        .tool(
                                "business",
                                new ToolRule(
                                        Set.of("execute"),
                                        ParameterRule.object(
                                                Map.of("payload", ParameterRule.string(null)),
                                                "payload"),
                                        false))
                        .build();
        try (var control = AgentControl.builder(policy).audit(vault).build();
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "worker", "tenant", "user", Set.of("execute")))) {
            session.operation(
                    true,
                    "business",
                    Map.of("payload", "worker-private-body"),
                    value -> {
                        try {
                            var request =
                                    HttpRequest.newBuilder(
                                                    URI.create((String) config.get("endpoint")))
                                            .header(
                                                    "Authorization",
                                                    "Bearer " + config.get("httpCredential"))
                                            .timeout(Duration.ofSeconds(5))
                                            .POST(
                                                    HttpRequest.BodyPublishers.ofString(
                                                            JsonValues.write(value)))
                                            .build();
                            var response =
                                    HttpClient.newHttpClient()
                                            .send(request, HttpResponse.BodyHandlers.ofString());
                            if (response.statusCode() != 200)
                                throw new GovernanceException("test_backend_failed");
                            return response.body();
                        } catch (Exception error) {
                            throw new GovernanceException("test_backend_failed");
                        }
                    },
                    value -> value,
                    (a, b) -> (String) b);
        }
        System.out.println("OK");
    }
}
