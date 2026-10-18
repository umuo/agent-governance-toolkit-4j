package io.agt.delegation.jdbc;

import static io.agt.delegation.jdbc.DurableTestSupport.*;

import io.agt.core.*;
import io.agt.delegation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立 JVM；测试凭据仅从 stdin 读取，stdout 只传状态和原生调用计数。 */
public final class DurableWorker {
    public static void main(String[] ignored) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var config = JsonValues.parseObject(input.readLine());
        var data = dataSource((String) config.get("url"));
        data.setUser((String) config.get("user"));
        data.setPassword((String) config.get("password"));
        var clock = Clock.systemUTC();
        var registry =
                new JdbcGrantRegistry(
                        data, "primary", clock, null, 2, JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
        if (Boolean.TRUE.equals(config.get("inspect"))) {
            var state = registry.inspect((String) config.get("root"), AUDITOR);
            reply(
                    "STATE:"
                            + state.toolCalls()
                            + ":"
                            + state.usage().settledTokens()
                            + ":"
                            + state.usage().heldTokens()
                            + ":"
                            + state.usage().uncertainCalls());
            return;
        }
        var publicKey =
                (RSAPublicKey)
                        KeyFactory.getInstance("RSA")
                                .generatePublic(
                                        new X509EncodedKeySpec(
                                                Base64.getDecoder()
                                                        .decode((String) config.get("publicKey"))));
        try (var verifier =
                        new DelegationVerifier(
                                ISSUER, AUDIENCE, Map.of("key-1", publicKey), registry, clock);
                var control = AgentControl.builder(policy()).audit(record -> {}).build();
                var session =
                        control.openAuthorizedSession(
                                verifier.accept(
                                        new DelegationToken((String) config.get("token")),
                                        new AuthenticatedAgent("reader")),
                                new TaskLimits(CALLS, 20, 1, 1),
                                null)) {
            var effects = new AtomicInteger();
            reply("READY");
            String command;
            while ((command = input.readLine()) != null && !command.equals("EXIT")) {
                try {
                    switch (command) {
                        case "TOOL" ->
                                session.operationPrepared(
                                        true,
                                        "lookup",
                                        Map.of(),
                                        value -> value,
                                        value -> {
                                            effects.incrementAndGet();
                                            return "ok";
                                        },
                                        value -> value,
                                        (original, value) -> (String) value);
                        case "MODEL" -> model(session, effects);
                        case "HANG" -> {
                            session.operationAsyncPrepared(
                                    false,
                                    null,
                                    Map.of("model", "fixture"),
                                    value ->
                                            new Input(
                                                    session.reserveModelUsage("fixture", 100, 50)),
                                    value -> {
                                        effects.incrementAndGet();
                                        reply("DISPATCHED:" + effects.get());
                                        return new CompletableFuture<String>();
                                    },
                                    value -> value,
                                    (original, value) -> (String) value);
                            new CountDownLatch(1).await();
                        }
                        default -> throw new AssertionError("Unknown worker command");
                    }
                    reply("OK:" + effects.get());
                } catch (GovernanceException denied) {
                    reply("DENIED:" + denied.code() + ":" + effects.get());
                }
            }
        }
    }

    private static void reply(String status) {
        System.out.println(status);
        System.out.flush();
    }
}
