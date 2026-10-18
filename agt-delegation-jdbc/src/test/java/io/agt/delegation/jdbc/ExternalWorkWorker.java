package io.agt.delegation.jdbc;

import io.agt.core.*;
import io.agt.core.execution.*;
import io.agt.delegation.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** 独立 SDK 节点；私有凭据经 stdin，stdout 仅传固定状态。 */
public final class ExternalWorkWorker {
    public static void main(String[] ignored) throws Throwable {
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
        Path directory = Path.of((String) config.get("directory"));
        Path marker = directory.resolve("job.pid");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        String digest =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(java)));
        var command =
                new FixedCommand(
                        "runJob",
                        java,
                        digest,
                        directory,
                        List.of(
                                CommandArgument.literal("-cp"),
                                        CommandArgument.literal(
                                                Path.of(
                                                                ExternalNativeFixture.class
                                                                        .getProtectionDomain()
                                                                        .getCodeSource()
                                                                        .getLocation()
                                                                        .toURI())
                                                        .toString()),
                                CommandArgument.literal(ExternalNativeFixture.class.getName()),
                                        CommandArgument.literal(marker.toString())),
                        new CommandLimits(30000, 1024, 1));
        var nativeWorker = Executors.newSingleThreadExecutor();
        try (var verifier =
                        new DelegationVerifier(
                                "urn:agt:external-test",
                                "worker",
                                Map.of("key-1", key),
                                client,
                                Clock.systemUTC());
                var control =
                        AgentControl.builder(ExternalWorkProcessTest.policy())
                                .audit(record -> {})
                                .build();
                var session =
                        control.openAuthorizedSession(
                                verifier.accept(
                                        new DelegationToken((String) config.get("token")),
                                        new AuthenticatedAgent("reader")),
                                new TaskLimits(ExternalWorkProcessTest.CALLS, 2, 1, 1),
                                null)) {
            reply("READY");
            String operation;
            while ((operation = input.readLine()) != null && !operation.equals("EXIT")) {
                try {
                    switch (operation) {
                        case "START" -> {
                            var call =
                                    session.operationAsyncPrepared(
                                            true,
                                            "runJob",
                                            Map.of(),
                                            value -> value,
                                            value ->
                                                    CompletableFuture.supplyAsync(
                                                            () ->
                                                                    command.execute(
                                                                            Map.of(), session),
                                                            nativeWorker),
                                            value -> value,
                                            (original, value) -> (String) value);
                            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                            while (!Files.exists(marker)
                                    && !call.isDone()
                                    && System.nanoTime() < until) Thread.sleep(5);
                            if (call.isDone()) call.join();
                            if (!Files.exists(marker))
                                throw new AssertionError("Native job did not start");
                            reply("STARTED");
                        }
                        case "REVOKE" -> {
                            session.revoke();
                            reply("STOP_REQUESTED");
                        }
                        case "PING" -> {
                            session.checkActive();
                            reply("PONG");
                        }
                        default -> throw new AssertionError("Unknown command");
                    }
                } catch (Throwable stopped) {
                    while ((stopped instanceof CompletionException
                                    || stopped instanceof ExecutionException)
                            && stopped.getCause() != null) stopped = stopped.getCause();
                    if (stopped instanceof GovernanceException denied)
                        reply("DENIED:" + denied.code());
                    else throw stopped;
                }
            }
        } finally {
            nativeWorker.shutdownNow();
            nativeWorker.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static void reply(String value) {
        System.out.println(value);
        System.out.flush();
    }
}
