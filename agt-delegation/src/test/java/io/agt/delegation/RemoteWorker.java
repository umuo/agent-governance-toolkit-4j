package io.agt.delegation;

import io.agt.core.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立 JVM 测试入口；秘密仅经标准输入传入，输出只含状态与计数。 */
public final class RemoteWorker {
    public static void main(String[] ignored) throws Exception {
        var config =
                JsonValues.parseObject(
                        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                                .readLine());
        var publicKey =
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
        try (var verifier =
                        new DelegationVerifier(
                                DelegationTest.ISSUER,
                                DelegationTest.AUDIENCE,
                                Map.of("key-1", publicKey),
                                client,
                                Clock.systemUTC());
                var receiver = DelegationTest.control();
                var session =
                        receiver.openAuthorizedSession(
                                verifier.accept(
                                        new DelegationToken((String) config.get("token")),
                                        new AuthenticatedAgent("reader")),
                                DelegationTest.receiverLimits(),
                                null)) {
            if (!session.taskSnapshot().taskId().equals(config.get("taskId")))
                throw new AssertionError("Task binding changed");
            var effects = new AtomicInteger();
            DelegationTest.lookup(session, "customer-a", effects);
            var entered = new CountDownLatch(1);
            var call =
                    session.operationAsyncPrepared(
                            true,
                            "slow",
                            Map.of(),
                            value -> value,
                            value -> {
                                entered.countDown();
                                return new CompletableFuture<String>();
                            },
                            value -> value,
                            (original, value) -> (String) value);
            if (!entered.await(3, TimeUnit.SECONDS))
                throw new AssertionError("Worker did not enter");
            System.out.println("READY");
            System.out.flush();
            String code;
            try {
                call.get(6, TimeUnit.SECONDS);
                throw new AssertionError("Late result delivered");
            } catch (ExecutionException stopped) {
                if (!(stopped.getCause() instanceof GovernanceException denied))
                    throw new AssertionError("Unexpected termination");
                code = denied.code();
            }
            try {
                DelegationTest.lookup(session, "customer-a", effects);
                throw new AssertionError("Stopped task executed again");
            } catch (GovernanceException expected) {
                /* fail closed */
            }
            System.out.println("STOPPED:" + code + ":" + effects.get());
        }
    }
}
