package io.agt.delegation.jdbc;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import io.agt.core.GovernanceException;
import io.agt.delegation.RegistryCheckpoint;
import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 固定 DTO、有界、禁止多态类型或旧格式回退；校验和用于检出损坏，不替代数据库访问控制。 */
final class CheckpointCodec {
    private final int maximumBytes;
    private final ObjectMapper json;

    CheckpointCodec(int maximumBytes) {
        if (maximumBytes < 1_048_576 || maximumBytes > 536_870_912)
            throw new IllegalArgumentException("Invalid checkpoint byte cap");
        this.maximumBytes = maximumBytes;
        json =
                new ObjectMapper(
                        JsonFactory.builder()
                                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                .streamReadConstraints(
                                        StreamReadConstraints.builder()
                                                .maxNestingDepth(64)
                                                .maxStringLength(1_048_576)
                                                .maxNumberLength(128)
                                                .build())
                                .build());
        json.enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        json.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        json.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    byte[] encode(RegistryCheckpoint state) {
        try {
            var output =
                    new ByteArrayOutputStream() {
                        @Override
                        public synchronized void write(int value) {
                            check(1);
                            super.write(value);
                        }

                        @Override
                        public synchronized void write(byte[] value, int offset, int length) {
                            check(length);
                            super.write(value, offset, length);
                        }

                        private void check(int length) {
                            if (length > maximumBytes - size()) throw new IllegalStateException();
                        }
                    };
            json.writeValue(output, state);
            return output.toByteArray();
        } catch (Exception invalid) {
            throw new GovernanceException("delegation_checkpoint_capacity_denied");
        }
    }

    RegistryCheckpoint decode(byte[] bytes, String checksum) {
        try {
            if (bytes == null || bytes.length > maximumBytes || !digest(bytes).equals(checksum))
                throw new IllegalArgumentException();
            try (var parser = json.getFactory().createParser(bytes)) {
                long tokens = 0;
                while (parser.nextToken() != null)
                    if (++tokens > 8_000_000) throw new IllegalArgumentException();
            }
            return json.readValue(bytes, RegistryCheckpoint.class);
        } catch (Exception invalid) {
            throw new GovernanceException("delegation_checkpoint_denied");
        }
    }

    static String digest(byte[] bytes) {
        try {
            return "sha256:"
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    int maximumBytes() {
        return maximumBytes;
    }
}
