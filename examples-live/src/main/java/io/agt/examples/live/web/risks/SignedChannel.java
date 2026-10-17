package io.agt.examples.live.web.risks;

import io.agt.core.GovernanceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 单轮的发送方与接收方共享随机密钥；签名校验与 nonce 接收状态属于宿主，不接受模型提交密钥。 */
public final class SignedChannel {
    public record Envelope(
            String sender, String audience, String nonce, String task, byte[] signature) {
        public Envelope {
            signature = signature.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }

    private final byte[] key = new byte[32];
    private final Set<String> received = new HashSet<>();

    public SignedChannel() {
        new SecureRandom().nextBytes(key);
    }

    public Envelope signed(String task) {
        return new Envelope(
                "planner-a",
                "worker-a",
                "nonce-one",
                task,
                sign("planner-a", "worker-a", "nonce-one", task));
    }

    public synchronized void verifyForReceiver(Envelope value, String receiver) {
        if (!value.audience().equals(receiver))
            throw new GovernanceException("agent_audience_denied");
        verify(value);
    }

    public synchronized void verify(Envelope value) {
        if (!value.sender().equals("planner-a")
                || !value.audience().equals("worker-a")
                || !MessageDigest.isEqual(
                        value.signature(),
                        sign(value.sender(), value.audience(), value.nonce(), value.task())))
            throw new GovernanceException("agent_signature_denied");
        if (!received.add(value.nonce())) throw new GovernanceException("agent_replay_denied");
    }

    private byte[] sign(String sender, String audience, String nonce, String task) {
        try {
            // 固定样本字段不含 NUL，采用分隔编码；不是任意 JSON 的跨语言签名协议。
            for (String field : new String[] {sender, audience, nonce, task})
                if (field.contains("\0")) throw new IllegalArgumentException("Invalid field");
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(
                    String.join("\0", sender, audience, nonce, task)
                            .getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("HMAC unavailable", error);
        }
    }
}
