package io.agt.delegation;

import java.security.SecureRandom;
import java.util.Base64;

final class LeaseSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();

    private LeaseSecrets() {}

    static GrantLease create(String id) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return new GrantLease(id, Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }
}
