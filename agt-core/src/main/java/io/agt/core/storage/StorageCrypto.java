package io.agt.core.storage;

import io.agt.core.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** 受信持久适配器复用的 JCA 算法封装；密钥与 AAD 由适配器绑定，错误不携带正文。 */
public final class StorageCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    private StorageCrypto() {}

    public static byte[] json(Object value) {
        return JsonValues.write(value).getBytes(StandardCharsets.UTF_8);
    }

    public static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (GeneralSecurityException failure) {
            throw failed();
        }
    }

    public static String mac(SecretKey key, Object value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(json(value)));
        } catch (GeneralSecurityException failure) {
            throw failed();
        }
    }

    public static boolean equal(String first, String second) {
        return first != null
                && second != null
                && MessageDigest.isEqual(
                        first.getBytes(StandardCharsets.US_ASCII),
                        second.getBytes(StandardCharsets.US_ASCII));
    }

    public static byte[] encrypt(SecretKey key, Object aad, byte[] plain) {
        if (plain == null || plain.length > 1048548) throw failed();
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(json(aad));
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce)
                    .put(encrypted)
                    .array();
        } catch (GeneralSecurityException failure) {
            throw failed();
        }
    }

    public static byte[] decrypt(SecretKey key, Object aad, byte[] encrypted) {
        if (encrypted == null || encrypted.length < 28 || encrypted.length > 1048576)
            throw failed();
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
            cipher.updateAAD(json(aad));
            return cipher.doFinal(encrypted, 12, encrypted.length - 12);
        } catch (GeneralSecurityException failure) {
            throw failed();
        }
    }

    public static Object parse(byte[] plain) {
        try {
            return JsonValues.parse(
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(plain))
                            .toString());
        } catch (CharacterCodingException failure) {
            throw failed();
        }
    }

    private static GovernanceException failed() {
        return new GovernanceException("storage_integrity_failed");
    }
}
