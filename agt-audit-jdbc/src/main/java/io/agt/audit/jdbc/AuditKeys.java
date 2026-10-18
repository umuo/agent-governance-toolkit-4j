package io.agt.audit.jdbc;

import io.agt.core.Identifiers;
import java.util.Objects;
import javax.crypto.SecretKey;

/** 宿主/KMS 提供版本化密钥；加密与链认证使用不同密钥，数据库只保存 key id。 */
public interface AuditKeys {
    record Version(String id, SecretKey encryption, SecretKey authentication) {
        public Version {
            Identifiers.require(id);
            Objects.requireNonNull(encryption);
            Objects.requireNonNull(authentication);
            if (!"AES".equalsIgnoreCase(encryption.getAlgorithm())
                    || !"HmacSHA256".equalsIgnoreCase(authentication.getAlgorithm()))
                throw new IllegalArgumentException("Invalid audit key algorithms");
            byte[] aes = encryption.getEncoded(), mac = authentication.getEncoded();
            if ((aes != null && aes.length != 32)
                    || (mac != null && mac.length < 32)
                    || encryption == authentication
                    || (aes != null
                            && mac != null
                            && java.security.MessageDigest.isEqual(aes, mac)))
                throw new IllegalArgumentException("Audit keys must be strong and independent");
        }

        @Override
        public String toString() {
            return "AuditKeyVersion[" + id + ",redacted]";
        }
    }

    Version active();

    Version find(String id);
}
