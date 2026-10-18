package io.agt.langchain4j;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.*;
import io.agt.core.*;
import io.agt.delegation.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 独立接收进程：真实官方客户端连接本机模拟 Provider，不在输出中包含请求或凭据。 */
public final class RemoteUsageWorker {
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
                        URI.create((String) config.get("authority")),
                        () -> (String) config.get("credential"));
        ModelUsageMeter meter =
                (request, output) ->
                        new ModelUsageMeter.Bound(
                                request.toBuilder()
                                        .parameters(
                                                OpenAiChatRequestParameters.builder()
                                                        .overrideWith(request.parameters())
                                                        .maxCompletionTokens(output)
                                                        .build())
                                        .build(),
                                100);
        try (var verifier =
                        new DelegationVerifier(
                                "urn:agt:usage-test",
                                "model-worker",
                                Map.of("key-1", key),
                                client,
                                Clock.systemUTC());
                var control =
                        AgentControl.builder(ModelUsageRemoteHttpTest.policy())
                                .audit(record -> {})
                                .build();
                var permit =
                        verifier.accept(
                                new DelegationToken((String) config.get("token")),
                                new AuthenticatedAgent("reader"));
                var session =
                        control.openAuthorizedSession(
                                permit, ModelUsageRemoteHttpTest.receiverLimits(), null);
                var binding = session.bind()) {
            var provider =
                    OpenAiChatModel.builder()
                            .baseUrl((String) config.get("provider"))
                            .apiKey("local-remote-usage-test")
                            .modelName("model")
                            .maxCompletionTokens(999)
                            .maxRetries(0)
                            .timeout(Duration.ofSeconds(10))
                            .build();
            var guarded =
                    new LangChain4jGovernance(
                                    control,
                                    new AiServiceOptions(
                                            StreamDelivery.REALTIME,
                                            StreamingLimits.DEFAULT,
                                            32,
                                            meter))
                            .guardModel(provider);
            System.out.println("READY");
            System.out.flush();
            String command;
            while ((command = input.readLine()) != null && !command.equals("QUIT")) {
                try {
                    var request =
                            ChatRequest.builder().messages(UserMessage.from("request")).build();
                    var response =
                            command.equals("ASYNC")
                                    ? guarded.chatAsync(request).get(10, TimeUnit.SECONDS)
                                    : guarded.chat(request);
                    if (!"ok".equals(response.aiMessage().text()))
                        throw new AssertionError("Invalid response");
                    System.out.println("OK");
                } catch (Exception error) {
                    Throwable cause =
                            error instanceof ExecutionException ? error.getCause() : error;
                    if (!(cause instanceof GovernanceException denied))
                        throw new AssertionError("Unexpected model failure");
                    System.out.println("DENIED:" + denied.code());
                    System.out.flush();
                    break;
                }
                System.out.flush();
            }
        }
    }
}
