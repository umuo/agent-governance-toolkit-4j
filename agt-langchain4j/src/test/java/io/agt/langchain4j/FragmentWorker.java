package io.agt.langchain4j;

import static io.agt.langchain4j.FragmentProvenanceTest.*;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.agt.core.*;
import io.agt.core.data.DocumentSegment;
import io.agt.delegation.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立接收 JVM；授权与端点凭据只从 stdin 获取，正文不经过授权 RPC。 */
public final class FragmentWorker {
    public static void main(String[] ignored) throws Exception {
        var config =
                JsonValues.parseObject(
                        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                                .readLine());
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
        try (var verifier =
                        new DelegationVerifier(
                                "urn:agt:fragment-test",
                                "worker",
                                Map.of("key-1", key),
                                client,
                                Clock.systemUTC());
                var control = AgentControl.builder(policy()).audit(record -> {}).build();
                var session =
                        control.openAuthorizedSession(
                                verifier.accept(
                                        new DelegationToken((String) config.get("token")),
                                        new AuthenticatedAgent("reader")),
                                new TaskLimits(CALLS, 10, 1, 1),
                                null);
                var binding = session.bind()) {
            var governance = new LangChain4jGovernance(control);
            ContentRetriever nativeRetriever =
                    query -> List.of(content((String) config.get("text"), "forged-public-id"));
            String documentId = (String) config.get("documentId");
            var retriever =
                    documentId.isEmpty()
                            ? governance.guardRetriever(SOURCE, nativeRetriever)
                            : governance.guardRetriever(
                                    SOURCE,
                                    nativeRetriever,
                                    item -> new DocumentSegment(documentId, 1, "chunk"));
            var results =
                    retriever
                            .retrieveAsync(Query.from("synthetic-query"))
                            // 多次独立有界 RPC 与文件库提交可累计超过 5 秒；不改变生产单次 RPC/租约期限。
                            .get(10, java.util.concurrent.TimeUnit.SECONDS);
            var snapshot = session.dataFlowSnapshot();
            var calls = new AtomicInteger();
            try {
                governance.guardModel(model(calls)).chat("synthetic-model-input");
            } catch (GovernanceException denied) {
                if (!denied.code().equals("data_flow_model_denied")) throw denied;
            }
            System.out.println(snapshot.label() + ":" + results.size() + ":" + calls.get());
        }
    }
}
