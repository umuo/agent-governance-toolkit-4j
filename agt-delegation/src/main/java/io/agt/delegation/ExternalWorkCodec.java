package io.agt.delegation;

import com.fasterxml.jackson.databind.*;
import io.agt.core.*;
import io.agt.core.execution.ExternalExecutionState;
import java.math.BigInteger;
import java.util.*;

/** 元数据分页的严格 JSON DTO，不接收正文、可执行类型或浮点索引。 */
final class ExternalWorkCodec {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(
                            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                            DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

    static String encode(Object value) {
        return JsonValues.write(JSON.convertValue(value, Object.class));
    }

    private static void keys(Map<?, ?> values, String... fields) {
        if (!values.keySet().equals(Set.of(fields))) throw new IllegalArgumentException();
    }

    private static void integers(Map<?, ?> values, String... fields) {
        for (var field : fields)
            if (!(values.get(field) instanceof BigInteger)) throw new IllegalArgumentException();
    }

    static ExternalExecutionState.Summary summary(String encoded) {
        try {
            var fields = JsonValues.parseObject(encoded);
            keys(
                    fields,
                    "taskId",
                    "revision",
                    "records",
                    "active",
                    "pendingCancellation",
                    "unknown",
                    "allTerminationConfirmed",
                    "fault");
            integers(fields, "revision", "records", "active", "pendingCancellation", "unknown");
            if (!(fields.get("taskId") instanceof String)
                    || !(fields.get("allTerminationConfirmed") instanceof Boolean)
                    || (fields.get("fault") != null && !(fields.get("fault") instanceof String)))
                throw new IllegalArgumentException();
            return JSON.convertValue(fields, ExternalExecutionState.Summary.class);
        } catch (RuntimeException invalid) {
            throw new GovernanceException("external_execution_snapshot_denied");
        }
    }

    static ExternalExecutionState.Page page(String encoded) {
        try {
            var fields = JsonValues.parseObject(encoded);
            keys(fields, "revision", "offset", "total", "rows");
            integers(fields, "revision", "offset", "total");
            if (!(fields.get("rows") instanceof List<?> rows)
                    || rows.size() > ExternalExecutionState.PAGE_SIZE)
                throw new IllegalArgumentException();
            for (var row : rows) {
                if (!(row instanceof Map<?, ?> values)
                        || !(values.get("entry") instanceof Map<?, ?> entry))
                    throw new IllegalArgumentException();
                keys(values, "entry", "cancellationUntil");
                integers(values, "cancellationUntil");
                keys(
                        entry,
                        "executionId",
                        "sessionId",
                        "kind",
                        "state",
                        "cancellationRequested",
                        "evidence");
                for (var name : Set.of("executionId", "sessionId", "kind", "state"))
                    if (!(entry.get(name) instanceof String)) throw new IllegalArgumentException();
                if (!(entry.get("cancellationRequested") instanceof Boolean)
                        || (entry.get("evidence") != null
                                && !(entry.get("evidence") instanceof String)))
                    throw new IllegalArgumentException();
            }
            return JSON.convertValue(fields, ExternalExecutionState.Page.class);
        } catch (RuntimeException invalid) {
            throw new GovernanceException("external_execution_snapshot_denied");
        }
    }
}
