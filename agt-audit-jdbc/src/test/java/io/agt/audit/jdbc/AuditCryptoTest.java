package io.agt.audit.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuditCryptoTest {
    @Test
    void extractedStoragePrimitiveReadsLegacyCiphertextAndKeepsLegacyWireFormat() throws Exception {
        var key = new JdbcAuditVaultTest.KeyRing().active();
        var aad = List.of("legacy-catalog", "stream", 7, "BODY");
        byte[] nonce = new byte[12];
        Arrays.fill(nonce, (byte) 7);
        var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                key.encryption(),
                new javax.crypto.spec.GCMParameterSpec(128, nonce));
        cipher.updateAAD(AuditCrypto.json(aad));
        byte[] legacy = cipher.doFinal(AuditCrypto.json(Map.of("text", "legacy-body")));
        byte[] stored =
                java.nio.ByteBuffer.allocate(12 + legacy.length).put(nonce).put(legacy).array();
        assertEquals(Map.of("text", "legacy-body"), AuditCrypto.decrypt(key, aad, stored));
        byte[] fresh = AuditCrypto.encrypt(key, aad, AuditCrypto.json(Map.of("text", "new-body")));
        cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                key.encryption(),
                new javax.crypto.spec.GCMParameterSpec(128, Arrays.copyOf(fresh, 12)));
        cipher.updateAAD(AuditCrypto.json(aad));
        assertEquals(
                Map.of("text", "new-body"),
                JsonValues.parse(
                        new String(
                                cipher.doFinal(fresh, 12, fresh.length - 12),
                                java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void jcaAeadBindsAssociatedDataAndProducesDistinctNonces() throws Exception {
        var key = new JdbcAuditVaultTest.KeyRing().active();
        var aad = List.of("catalog", "stream", 1, "BODY");
        byte[] first = AuditCrypto.encrypt(key, aad, AuditCrypto.json(Map.of("text", "value")));
        byte[] second = AuditCrypto.encrypt(key, aad, AuditCrypto.json(Map.of("text", "value")));
        assertFalse(Arrays.equals(first, second));
        assertEquals(Map.of("text", "value"), AuditCrypto.decrypt(key, aad, first));
        assertThrows(
                GovernanceException.class,
                () -> AuditCrypto.decrypt(key, List.of("catalog", "stream", 2, "BODY"), first));
        first[first.length - 1] ^= 1;
        assertThrows(GovernanceException.class, () -> AuditCrypto.decrypt(key, aad, first));
        assertFalse(
                key.toString()
                        .contains(
                                Base64.getEncoder().encodeToString(key.encryption().getEncoded())));
    }
}
