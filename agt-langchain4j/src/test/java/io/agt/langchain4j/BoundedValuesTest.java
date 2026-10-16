package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dev.langchain4j.internal.Json;
import io.agt.core.*;
import io.agt.langchain4j.internal.service.TypedValues;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 容量限制发生在序列化进行中，并对照固定版本原生 JSON 语义。 */
class BoundedValuesTest {
    enum State {
        READY
    }

    sealed interface Shape permits Circle, Square {}

    record Circle(int radius) implements Shape {}

    record Square(int width) implements Shape {}

    record Envelope(
            String text,
            State state,
            LocalDate date,
            LocalTime time,
            LocalDateTime timestamp,
            Shape shape,
            byte[] bytes,
            Map<Integer, String> indexed) {}

    static class PrivateValue {
        private String value = "hello";
        @JsonIgnore private String hidden = "not serialized";

        @JsonProperty("renamed")
        private String label = "label";

        @JsonProperty("renamed")
        public String getLabel() {
            return label;
        }

        @JsonProperty("renamed")
        public void setLabel(String value) {
            label = value;
        }
    }

    @Test
    void supportedPojoProfilesMatchNativeCodecIncludingSealedTypesAndDates() {
        for (Object original :
                List.of(
                        new PrivateValue(),
                        new Envelope(
                                "汉字\\\"",
                                State.READY,
                                LocalDate.of(2026, 10, 16),
                                LocalTime.of(12, 34, 56),
                                LocalDateTime.of(2026, 10, 16, 12, 34),
                                new Circle(2),
                                new byte[] {1, 2, 3},
                                Map.of(7, "value")),
                        Map.of(1, "one", 2, "two"))) {
            var projected = TypedValues.project(original);
            assertTrue(
                    TypedValues.equivalent(JsonValues.parse(Json.toJson(original)), projected),
                    () ->
                            "type="
                                    + original.getClass()
                                    + " native="
                                    + Json.toJson(original)
                                    + " bounded="
                                    + projected);
            assertTrue(
                    TypedValues.equivalent(
                            projected,
                            TypedValues.project(
                                    TypedValues.restore(
                                            original,
                                            projected,
                                            original instanceof Map<?, ?>
                                                    ? Map.class
                                                    : original.getClass()))));
        }
    }

    @JsonPropertyOrder({"value", "tail"})
    static class HugeText {
        static final AtomicInteger tail = new AtomicInteger();
        private final String value = "x".repeat(JsonValues.MAX_CHARACTERS * 2);

        public String getTail() {
            tail.incrementAndGet();
            return "late";
        }
    }

    @Test
    void oversizedStringAbortsBeforeLaterGettersRun() {
        HugeText.tail.set(0);
        assertEquals(
                "payload_limit",
                assertThrows(GovernanceException.class, () -> TypedValues.project(new HugeText()))
                        .code());
        assertEquals(0, HugeText.tail.get());
    }

    @JsonSerialize(using = FloodSerializer.class)
    static class Flood {
        final int rows, columns;
        final AtomicInteger visited = new AtomicInteger();

        Flood(int rows, int columns) {
            this.rows = rows;
            this.columns = columns;
        }
    }

    public static class FloodSerializer extends JsonSerializer<Flood> {
        public void serialize(Flood value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeStartArray();
            for (int row = 0; row < value.rows; row++) {
                generator.writeStartArray();
                for (int col = 0; col < value.columns; col++) {
                    value.visited.incrementAndGet();
                    generator.writeNumber(0);
                }
                generator.writeEndArray();
            }
            generator.writeEndArray();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 1_000_000})
    void customSerializersStopAtNodeOrContainerLimitWithoutBuildingFullJson(int columns) {
        var flood = new Flood(columns == 100 ? 100 : 1, columns);
        assertEquals(
                "payload_limit",
                assertThrows(GovernanceException.class, () -> TypedValues.project(flood)).code());
        assertTrue(flood.visited.get() <= JsonValues.MAX_NODES);
    }

    static class Chain {
        static final AtomicInteger visited = new AtomicInteger();
        private final Chain next;

        Chain(int depth) {
            next = depth == 0 ? null : new Chain(depth - 1);
        }

        public Chain getNext() {
            visited.incrementAndGet();
            return next;
        }
    }

    @Test
    void depthIsRejectedBeforeTraversingEntirePojoChain() {
        Chain.visited.set(0);
        assertEquals(
                "payload_limit",
                assertThrows(GovernanceException.class, () -> TypedValues.project(new Chain(100)))
                        .code());
        assertTrue(Chain.visited.get() <= JsonValues.MAX_DEPTH + 1);
    }

    static class Chunk {
        static final AtomicInteger visited = new AtomicInteger();

        public String getValue() {
            visited.incrementAndGet();
            return "x".repeat(1024);
        }
    }

    @Test
    void nestedPojoCollectionSharesAggregateProjectionBudget() {
        Chunk.visited.set(0);
        var value = Collections.nCopies(3000, new Chunk());
        assertEquals(
                "payload_limit",
                assertThrows(GovernanceException.class, () -> TypedValues.project(value)).code());
        assertTrue(Chunk.visited.get() < 1500);
    }

    record LargeInteger(java.math.BigInteger value) {}

    @Test
    void hugePojoNumbersAreRefusedBeforeCreatingDecimalText() {
        var integer = java.math.BigInteger.ONE.shiftLeft(1_000_000);
        assertEquals(
                "payload_limit",
                assertThrows(
                                GovernanceException.class,
                                () -> TypedValues.project(new LargeInteger(integer)))
                        .code());
        assertEquals(
                "payload_limit",
                assertThrows(GovernanceException.class, () -> TypedValues.project(integer)).code());
    }
}
