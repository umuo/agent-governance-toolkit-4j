package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.data.DataOperation;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立 JVM 的受信任宿主测试入口；正文仅在实际数据适配器内处理，输出只含状态/等级/计数。 */
public final class RemoteFlowWorker {
    public static void main(String[] ignored) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var config = JsonValues.parseObject(input.readLine());
        var key =
                (RSAPublicKey)
                        KeyFactory.getInstance("RSA")
                                .generatePublic(
                                        new X509EncodedKeySpec(
                                                Base64.getDecoder()
                                                        .decode((String) config.get("publicKey"))));
        var client =
                HttpGrantStatusClient.loopbackForTesting(
                        URI.create((String) config.get("endpoint")),
                        () -> (String) config.get("credential"));
        Path memory = Path.of((String) config.get("memoryFile"));
        try (var verifier =
                        new DelegationVerifier(
                                DelegationTest.ISSUER,
                                DelegationTest.AUDIENCE,
                                Map.of("key-1", key),
                                client,
                                Clock.systemUTC());
                var control =
                        AgentControl.builder(RemoteFlowTest.policy()).audit(record -> {}).build();
                var permit =
                        verifier.accept(
                                new DelegationToken((String) config.get("token")),
                                new AuthenticatedAgent("reader"));
                var session =
                        control.openAuthorizedSession(
                                permit, RemoteFlowTest.receiverLimits(), null)) {
            if (!session.taskSnapshot().taskId().equals(config.get("taskId")))
                throw new AssertionError("Task changed");
            var effects = new AtomicInteger();
            System.out.println("READY");
            System.out.flush();
            String command;
            while ((command = input.readLine()) != null && !command.equals("QUIT")) {
                try {
                    switch (command) {
                        case "PUBLIC" ->
                                RemoteFlowTest.tool(
                                        session,
                                        "send",
                                        Map.of("destination", "public", "body", "fixture-output"),
                                        effects);
                        case "SECRET" ->
                                RemoteFlowTest.read(
                                        session, "fixture-query", "fixture-confidential");
                        case "STORE" ->
                                session.dataOperationPrepared(
                                        RemoteFlowTest.MEMORY,
                                        DataOperation.MEMORY_ADD,
                                        "fixture-sensitive-summary",
                                        value -> value,
                                        value -> {
                                            try {
                                                Files.writeString(
                                                        memory,
                                                        (String) value,
                                                        StandardCharsets.UTF_8);
                                                return "written";
                                            } catch (IOException failed) {
                                                throw new IllegalStateException(
                                                        "fixture-write-failed");
                                            }
                                        },
                                        value -> value,
                                        (original, value) -> (String) value);
                        case "MEMORY" ->
                                session.dataOperationPrepared(
                                        RemoteFlowTest.MEMORY,
                                        DataOperation.MEMORY_READ,
                                        Map.of(),
                                        value -> value,
                                        value -> {
                                            try {
                                                return Files.readString(
                                                        memory, StandardCharsets.UTF_8);
                                            } catch (IOException failed) {
                                                throw new IllegalStateException(
                                                        "fixture-read-failed");
                                            }
                                        },
                                        value -> value,
                                        (original, value) -> (String) value);
                        default -> throw new AssertionError("Invalid worker command");
                    }
                    System.out.println(
                            "OK:" + session.dataFlowSnapshot().label() + ":" + effects.get());
                } catch (GovernanceException denied) {
                    System.out.println("DENIED:" + denied.code() + ":" + effects.get());
                    System.out.flush();
                    break;
                }
                System.out.flush();
            }
        }
    }
}
