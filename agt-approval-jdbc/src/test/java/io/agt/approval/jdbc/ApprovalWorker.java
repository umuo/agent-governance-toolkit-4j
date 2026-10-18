package io.agt.approval.jdbc;

import io.agt.audit.jdbc.*;
import io.agt.core.*;
import io.agt.core.storage.*;
import io.agt.identity.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.h2.jdbcx.JdbcDataSource;

/** 测试进程；合成凭据仅通过私有 stdin 传入，不写入命令行或标准输出。 */
public final class ApprovalWorker {
    public static void main(String[] ignored) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var config = JsonValues.parseObject(input.readLine());
        try (var fixture = new ApprovalFixture()) {
            var primary = new JdbcDataSource();
            primary.setURL((String) config.get("primary"));
            var witnessed = new JdbcDataSource();
            witnessed.setURL((String) config.get("witness"));
            var base = new JdbcAuditWitness(witnessed, 2);
            var version =
                    new StorageKeys.Version(
                            "storage-key-a",
                            new SecretKeySpec(
                                    Base64.getDecoder().decode((String) config.get("aes")), "AES"),
                            new SecretKeySpec(
                                    Base64.getDecoder().decode((String) config.get("mac")),
                                    "HmacSHA256"));
            StorageKeys keys =
                    new StorageKeys() {
                        public Version active() {
                            return version;
                        }

                        public Version find(String id) {
                            return version.id().equals(id) ? version : null;
                        }
                    };
            var rsa =
                    (RSAPublicKey)
                            KeyFactory.getInstance("RSA")
                                    .generatePublic(
                                            new X509EncodedKeySpec(
                                                    Base64.getDecoder()
                                                            .decode(
                                                                    (String)
                                                                            config.get(
                                                                                    "publicKey"))));
            AuditWitness witness =
                    new AuditWitness() {
                        public AuditCheckpoint read(String catalog, String stream) {
                            return base.read(catalog, stream);
                        }

                        public boolean advance(AuditCheckpoint previous, AuditCheckpoint next) {
                            if ("crash-before-witness".equals(config.get("mode"))
                                    && next.sequence() == 3) {
                                System.out.println("COMMIT_PENDING");
                                System.out.flush();
                                try {
                                    input.readLine();
                                } catch (IOException ended) {
                                    throw new GovernanceException("synthetic_worker_stopped");
                                }
                            }
                            return base.advance(previous, next);
                        }
                    };
            try (var verifier =
                    new JwtIdentityVerifier(
                            fixture.identity,
                            Map.of("key-a", rsa),
                            token -> true,
                            Clock.systemUTC())) {
                var actor = verifier.verify(new AccessToken((String) config.get("token")));
                var store =
                        new JdbcApprovalStore(
                                primary,
                                "approval-demo",
                                "reviewed-catalog",
                                fixture.policy,
                                keys,
                                witness,
                                Clock.systemUTC(),
                                2);
                var claim =
                        new ApprovalClaim(
                                (String) config.get("requestId"),
                                (String) config.get("actionIdentity"),
                                (String) config.get("claim"));
                if (store.consume(actor, claim).orElseThrow().approved()) {
                    var response =
                            HttpClient.newHttpClient()
                                    .send(
                                            HttpRequest.newBuilder(
                                                            URI.create(
                                                                    (String) config.get("backend")))
                                                    .timeout(Duration.ofSeconds(2))
                                                    .POST(
                                                            HttpRequest.BodyPublishers.ofString(
                                                                    "synthetic-worker-write"))
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200)
                        throw new AssertionError("Synthetic backend failed");
                    System.out.println("ALLOW");
                } else System.out.println("DENY");
            } catch (GovernanceException refused) {
                System.out.println("REFUSED");
            }
        }
    }
}
