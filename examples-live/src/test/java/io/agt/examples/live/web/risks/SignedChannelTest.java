package io.agt.examples.live.web.risks;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.GovernanceException;
import org.junit.jupiter.api.Test;

class SignedChannelTest {
    @Test
    void validSignatureMustAlsoMatchActualReceiverWithoutConsumingNonceOnMismatch() {
        var channel = new SignedChannel();
        var message = channel.signed("report");
        assertEquals(
                "agent_audience_denied",
                assertThrows(
                                GovernanceException.class,
                                () -> channel.verifyForReceiver(message, "worker-b"))
                        .code());
        assertDoesNotThrow(() -> channel.verifyForReceiver(message, "worker-a"));
        assertEquals(
                "agent_replay_denied",
                assertThrows(
                                GovernanceException.class,
                                () -> channel.verifyForReceiver(message, "worker-a"))
                        .code());
    }

    @Test
    void signatureBindsPayloadSenderAudienceAndNonceAndCannotBeReplayed() {
        var channel = new SignedChannel();
        var signed = channel.signed("report");
        for (var changed :
                new SignedChannel.Envelope[] {
                    new SignedChannel.Envelope(
                            "other",
                            signed.audience(),
                            signed.nonce(),
                            signed.task(),
                            signed.signature()),
                    new SignedChannel.Envelope(
                            signed.sender(),
                            "other",
                            signed.nonce(),
                            signed.task(),
                            signed.signature()),
                    new SignedChannel.Envelope(
                            signed.sender(),
                            signed.audience(),
                            "nonce-two",
                            signed.task(),
                            signed.signature()),
                    new SignedChannel.Envelope(
                            signed.sender(),
                            signed.audience(),
                            signed.nonce(),
                            "export",
                            signed.signature())
                }) {
            assertEquals(
                    "agent_signature_denied",
                    assertThrows(GovernanceException.class, () -> channel.verify(changed)).code());
        }
        // 非法消息不消耗合法消息的 nonce；签名必须先验证。
        assertDoesNotThrow(() -> channel.verify(signed));
        assertEquals(
                "agent_replay_denied",
                assertThrows(GovernanceException.class, () -> channel.verify(signed)).code());
        assertEquals(
                "agent_signature_denied",
                assertThrows(GovernanceException.class, () -> new SignedChannel().verify(signed))
                        .code());
    }
}
