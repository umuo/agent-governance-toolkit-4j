package io.agt.effects.jdbc;

import io.agt.core.*;
import io.agt.core.effects.*;
import io.agt.core.policy.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;

/** 独立 SDK JVM；仅使用测试 stdin 配置，不在参数或输出中包含凭据。 */
public final class EffectWorker {
    static final TrustedContext ID = new TrustedContext("worker", "tenant", "user", Set.of("pay"));
    static final EffectIntent INTENT = new EffectIntent("order-7", "open", null);

    static StructuredPolicy policy(String catalogId) {
        return StructuredPolicy.builder("effect-process-v1")
                .tool(
                        "pay",
                        new ToolRule(
                                Set.of("pay"),
                                ParameterRule.object(
                                        Map.of("item", ParameterRule.string(null)), "item"),
                                false,
                                new EffectRule(
                                        "orders", "v1", EffectLedger.Phase.FORWARD, catalogId)))
                .build();
    }

    static final class HttpBackend implements EffectBackend {
        final HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        final String endpoint, credential;

        HttpBackend(String endpoint, String credential) {
            this.endpoint = endpoint;
            this.credential = credential;
        }

        Map<String, Object> request(String path, Call call, EffectLedger.Proof proof)
                throws Exception {
            var body = new LinkedHashMap<String, Object>();
            body.put("key", call.idempotencyKey());
            body.put("digest", call.requestDigest());
            body.put("state", call.expectedState());
            body.put("phase", call.phase().name());
            body.put("attempt", call.attempt());
            body.put("parameters", call.parameters());
            if (proof != null) body.put("receipt", proof.receipt());
            var request =
                    HttpRequest.newBuilder(URI.create(endpoint + path))
                            .header("Authorization", "Bearer " + credential)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(12))
                            .POST(HttpRequest.BodyPublishers.ofString(JsonValues.write(body)))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Test backend failed");
            return JsonValues.parseObject(response.body());
        }

        public String currentState(Call call) throws Exception {
            return (String) request("/state", call, null).get("state");
        }

        public Reply execute(Call call) throws Exception {
            var reply = request("/execute", call, null);
            return new Reply(
                    EffectLedger.Status.valueOf((String) reply.get("status")),
                    (String) reply.get("receipt"),
                    reply.get("value"));
        }

        public Object recover(Call call, EffectLedger.Proof proof) throws Exception {
            return request("/recover", call, proof).get("value");
        }
    }

    public static void main(String[] ignored) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var config = JsonValues.parseObject(input.readLine());
        var data = new JdbcDataSource();
        data.setURL((String) config.get("jdbc"));
        data.setUser("sa");
        data.setPassword((String) config.get("databaseCredential"));
        var ledger = new JdbcEffectLedger(data, "orders", Clock.systemUTC(), 3);
        try (var control =
                        AgentControl.builder(policy((String) config.get("catalog")))
                                .audit(r -> {})
                                .build();
                var session = control.openSession(ID)) {
            Object result =
                    session.effectOperation(
                            "pay",
                            Map.of("item", "book"),
                            ledger,
                            INTENT,
                            new HttpBackend(
                                    (String) config.get("endpoint"),
                                    (String) config.get("credential")));
            System.out.println("RESULT:" + ("paid".equals(result) ? "paid" : "unexpected"));
        } catch (GovernanceException denied) {
            System.out.println("DENIED:" + denied.code());
        }
    }
}
