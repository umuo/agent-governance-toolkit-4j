package io.agt.langchain4j.internal.service;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.*;
import com.fasterxml.jackson.databind.jsontype.impl.StdTypeResolverBuilder;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.langchain4j.internal.PolymorphicTypes;
import io.agt.core.*;
import java.io.*;
import java.math.*;
import java.time.*;
import java.util.*;

/**
 * 普通值的有界 Jackson 写入。
 *
 * <p>固定版本原生 codec 没有流式写入接口；使用相同的公开序列化配置与多态元数据， 写入阶段限制字符、节点、容器和深度，不构造超限完整 JSON。恢复仍由原生 codec 执行。
 */
final class BoundedPojoJson {
    private static final ObjectMapper MAPPER = mapper();

    private BoundedPojoJson() {}

    private static ObjectMapper mapper() {
        var module = new SimpleModule("agt-native-value-dates");
        module.addSerializer(
                LocalDate.class,
                temporal(LocalDate.class, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        module.addSerializer(
                LocalTime.class,
                temporal(LocalTime.class, java.time.format.DateTimeFormatter.ISO_LOCAL_TIME));
        module.addSerializer(
                LocalDateTime.class,
                temporal(
                        LocalDateTime.class,
                        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        var mapper =
                JsonMapper.builder(
                                JsonFactory.builder()
                                        .streamWriteConstraints(
                                                StreamWriteConstraints.builder()
                                                        .maxNestingDepth(JsonValues.MAX_DEPTH)
                                                        .build())
                                        .build())
                        .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                        .disable(SerializationFeature.INDENT_OUTPUT)
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                        .build()
                        .findAndRegisterModules()
                        .registerModule(module);
        // 复用固定版本公开的 sealed 层次和 discriminator 解析，不访问 codec 私有字段。
        mapper.setAnnotationIntrospector(
                AnnotationIntrospectorPair.pair(
                        new NopAnnotationIntrospector() {
                            private boolean handles(Class<?> type) {
                                return type.getAnnotation(JsonTypeInfo.class) == null
                                        && PolymorphicTypes.isPolymorphic(type);
                            }

                            public TypeResolverBuilder<?> findTypeResolver(
                                    MapperConfig<?> config,
                                    AnnotatedClass annotated,
                                    JavaType base) {
                                if (!handles(annotated.getRawType())) return null;
                                return new StdTypeResolverBuilder()
                                        .init(JsonTypeInfo.Id.NAME, null)
                                        .inclusion(JsonTypeInfo.As.PROPERTY)
                                        .typeProperty(
                                                PolymorphicTypes.discriminatorPropertyName(
                                                        annotated.getRawType()))
                                        .typeIdVisibility(false);
                            }

                            public List<NamedType> findSubtypes(Annotated annotated) {
                                var type = annotated.getRawType();
                                if (!handles(type)) return null;
                                return PolymorphicTypes.findConcreteSubtypes(type).stream()
                                        .map(
                                                child ->
                                                        new NamedType(
                                                                child,
                                                                PolymorphicTypes.discriminatorValue(
                                                                        type, child)))
                                        .toList();
                            }
                        },
                        mapper.getDeserializationConfig().getAnnotationIntrospector()));
        return mapper;
    }

    private static <T extends java.time.temporal.TemporalAccessor> JsonSerializer<T> temporal(
            Class<T> type, java.time.format.DateTimeFormatter format) {
        return new com.fasterxml.jackson.databind.ser.std.StdSerializer<T>(type) {
            public void serialize(T value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(format.format(value));
            }
        };
    }

    static Object project(Object value, int characters, int nodes, int depth) {
        var output = new LimitedWriter(characters);
        try (var generator =
                new LimitedGenerator(MAPPER.getFactory().createGenerator(output), nodes, depth)) {
            MAPPER.writeValue(generator, value);
        } catch (Exception error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof GovernanceException g) throw g;
                if (cause instanceof com.fasterxml.jackson.core.exc.StreamConstraintsException)
                    throw limit();
            }
            throw new GovernanceException("typed_value_unsupported");
        }
        return JsonValues.parse(output.toString());
    }

    private static GovernanceException limit() {
        return new GovernanceException("payload_limit");
    }

    private static final class LimitedWriter extends Writer {
        private final StringBuilder value = new StringBuilder();
        private int remaining;

        LimitedWriter(int maximum) {
            remaining = maximum;
        }

        private void reserve(int length) {
            if (length < 0) throw new IndexOutOfBoundsException("negative write length");
            if (length > remaining) throw limit();
            remaining -= length;
        }

        public void write(char[] chars, int offset, int length) {
            reserve(length);
            value.append(chars, offset, length);
        }

        public void write(String text, int offset, int length) {
            reserve(length);
            value.append(text, offset, offset + length);
        }

        public void write(int ch) {
            reserve(1);
            value.append((char) ch);
        }

        public void flush() {}

        public void close() {}

        public String toString() {
            return value.toString();
        }
    }

    private static final class LimitedGenerator extends JsonGeneratorDelegate {
        private int nodes;
        private final int depth;
        private final ArrayDeque<Integer> sizes = new ArrayDeque<>();

        LimitedGenerator(JsonGenerator delegate, int nodes, int depth) {
            super(delegate, false);
            this.nodes = nodes;
            this.depth = depth;
        }

        private void node() {
            if (--nodes < 0) throw limit();
        }

        private void value() {
            node();
            if (!sizes.isEmpty()) {
                int size = sizes.pop() + 1;
                if (size > JsonValues.MAX_CONTAINER_SIZE) throw limit();
                sizes.push(size);
            }
        }

        private void begin(int size) {
            value();
            if (sizes.size() >= depth || size > JsonValues.MAX_CONTAINER_SIZE) throw limit();
            sizes.push(0);
        }

        public void writeStartObject() throws IOException {
            begin(0);
            delegate.writeStartObject();
        }

        public void writeStartObject(Object value) throws IOException {
            begin(0);
            delegate.writeStartObject(value);
        }

        public void writeStartObject(Object value, int size) throws IOException {
            begin(size);
            delegate.writeStartObject(value, size);
        }

        public void writeEndObject() throws IOException {
            delegate.writeEndObject();
            sizes.pop();
        }

        public void writeStartArray() throws IOException {
            begin(0);
            delegate.writeStartArray();
        }

        public void writeStartArray(int size) throws IOException {
            begin(size);
            delegate.writeStartArray(size);
        }

        public void writeStartArray(Object value) throws IOException {
            begin(0);
            delegate.writeStartArray(value);
        }

        public void writeStartArray(Object value, int size) throws IOException {
            begin(size);
            delegate.writeStartArray(value, size);
        }

        public void writeEndArray() throws IOException {
            delegate.writeEndArray();
            sizes.pop();
        }

        public void writeFieldName(String name) throws IOException {
            node();
            delegate.writeFieldName(name);
        }

        public void writeFieldName(SerializableString name) throws IOException {
            node();
            delegate.writeFieldName(name);
        }

        public void writeString(String text) throws IOException {
            value();
            delegate.writeString(text);
        }

        public void writeString(char[] chars, int offset, int length) throws IOException {
            value();
            delegate.writeString(chars, offset, length);
        }

        public void writeString(SerializableString text) throws IOException {
            value();
            delegate.writeString(text);
        }

        public void writeString(Reader text, int length) throws IOException {
            value();
            delegate.writeString(text, length);
        }

        public void writeNumber(int number) throws IOException {
            value();
            delegate.writeNumber(number);
        }

        public void writeNumber(long number) throws IOException {
            value();
            delegate.writeNumber(number);
        }

        public void writeNumber(short number) throws IOException {
            value();
            delegate.writeNumber(number);
        }

        public void writeNumber(float number) throws IOException {
            value();
            delegate.writeNumber(number);
        }

        public void writeNumber(double number) throws IOException {
            value();
            delegate.writeNumber(number);
        }

        public void writeNumber(BigInteger number) throws IOException {
            JsonValues.freeze(number);
            value();
            delegate.writeNumber(number);
        }

        public void writeNumber(BigDecimal number) throws IOException {
            JsonValues.freeze(number);
            value();
            delegate.writeNumber(number);
        }

        public void writeNumber(String number) throws IOException {
            if (number != null && number.length() > JsonValues.MAX_NUMBER_LENGTH) throw limit();
            value();
            delegate.writeNumber(number);
        }

        public void writeBoolean(boolean booleanValue) throws IOException {
            value();
            delegate.writeBoolean(booleanValue);
        }

        public void writeNull() throws IOException {
            value();
            delegate.writeNull();
        }

        public void writeBinary(Base64Variant variant, byte[] bytes, int offset, int length)
                throws IOException {
            value();
            delegate.writeBinary(variant, bytes, offset, length);
        }

        public int writeBinary(Base64Variant variant, InputStream input, int length)
                throws IOException {
            value();
            return delegate.writeBinary(variant, input, length);
        }

        public void writeNumber(char[] value, int offset, int length) throws IOException {
            if (length > JsonValues.MAX_NUMBER_LENGTH) throw limit();
            value();
            delegate.writeNumber(value, offset, length);
        }

        public void writeUTF8String(byte[] bytes, int offset, int length) throws IOException {
            value();
            delegate.writeUTF8String(bytes, offset, length);
        }

        public void writeRawUTF8String(byte[] bytes, int offset, int length) throws IOException {
            value();
            delegate.writeRawUTF8String(bytes, offset, length);
        }

        public void writeRawValue(SerializableString value) throws IOException {
            value();
            delegate.writeRawValue(value);
        }

        public void writeFieldId(long id) throws IOException {
            node();
            delegate.writeFieldId(id);
        }

        public void writeEmbeddedObject(Object value) throws IOException {
            value();
            delegate.writeEmbeddedObject(value);
        }

        public void writeRawValue(String text) throws IOException {
            value();
            delegate.writeRawValue(text);
        }

        public void writeRawValue(String text, int offset, int length) throws IOException {
            value();
            delegate.writeRawValue(text, offset, length);
        }

        public void writeRawValue(char[] chars, int offset, int length) throws IOException {
            value();
            delegate.writeRawValue(chars, offset, length);
        }

        public void writeArray(int[] array, int offset, int length) throws IOException {
            writeStartArray(array, length);
            for (int i = offset; i < offset + length; i++) writeNumber(array[i]);
            writeEndArray();
        }

        public void writeArray(long[] array, int offset, int length) throws IOException {
            writeStartArray(array, length);
            for (int i = offset; i < offset + length; i++) writeNumber(array[i]);
            writeEndArray();
        }

        public void writeArray(double[] array, int offset, int length) throws IOException {
            writeStartArray(array, length);
            for (int i = offset; i < offset + length; i++) writeNumber(array[i]);
            writeEndArray();
        }

        public void writeArray(String[] array, int offset, int length) throws IOException {
            writeStartArray(array, length);
            for (int i = offset; i < offset + length; i++) writeString(array[i]);
            writeEndArray();
        }
    }
}
