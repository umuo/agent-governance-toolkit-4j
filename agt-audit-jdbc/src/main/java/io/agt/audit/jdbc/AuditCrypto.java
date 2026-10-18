package io.agt.audit.jdbc;

import io.agt.core.*;
import io.agt.core.storage.StorageCrypto;

/** 审计协议包装；标准 JCA 算法与审批持久层共享，保留既有格式和原因码。 */
final class AuditCrypto {
    private AuditCrypto() {}

    static byte[] json(Object value) {
        return StorageCrypto.json(value);
    }

    static String hash(byte[] value) {
        return StorageCrypto.hash(value);
    }

    static String mac(AuditKeys.Version key, Object value) {
        try {
            return StorageCrypto.mac(key.authentication(), value);
        } catch (GovernanceException failure) {
            throw failed();
        }
    }

    static boolean equal(String first, String second) {
        return StorageCrypto.equal(first, second);
    }

    static byte[] encrypt(AuditKeys.Version key, Object aad, byte[] plain) {
        try {
            return StorageCrypto.encrypt(key.encryption(), aad, plain);
        } catch (GovernanceException failure) {
            throw failed();
        }
    }

    static Object decrypt(AuditKeys.Version key, Object aad, byte[] encrypted) {
        try {
            return StorageCrypto.parse(StorageCrypto.decrypt(key.encryption(), aad, encrypted));
        } catch (GovernanceException failure) {
            throw failed();
        }
    }

    static GovernanceException failed() {
        return new GovernanceException("audit_integrity_failed");
    }
}
