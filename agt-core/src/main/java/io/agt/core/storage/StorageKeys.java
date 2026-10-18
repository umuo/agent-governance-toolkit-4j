package io.agt.core.storage;

import io.agt.core.Identifiers;
import java.util.Objects;
import javax.crypto.SecretKey;

/** 可信宿主提供的版本化持久密钥；仅持有接口不代表已接入 KMS。 */
public interface StorageKeys {
    record Version(String id, SecretKey encryption, SecretKey authentication) {
        public Version {
            Identifiers.require(id);
            validate(encryption, authentication);
        }

        public String toString() {
            return "StorageKeyVersion[" + id + ",redacted]";
        }
    }

    Version active();

    Version find(String id);

    static void validate(SecretKey encryption, SecretKey authentication) {
        Objects.requireNonNull(encryption);
        Objects.requireNonNull(authentication);
        byte[] aes = encryption.getEncoded(), mac = authentication.getEncoded();
        if (!"AES".equalsIgnoreCase(encryption.getAlgorithm())
                || !"HmacSHA256".equalsIgnoreCase(authentication.getAlgorithm())
                || (aes != null && aes.length != 32)
                || (mac != null && mac.length < 32)
                || encryption == authentication
                || (aes != null && mac != null && java.security.MessageDigest.isEqual(aes, mac)))
            throw new IllegalArgumentException("Strong independent storage keys are required");
    }
}
