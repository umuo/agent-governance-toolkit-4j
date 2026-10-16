package io.agt.langchain4j.internal.service;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.JacksonChatMessageJsonCodec;
import dev.langchain4j.data.message.UserMessage;
import io.agt.core.GovernanceException;
import io.agt.core.JsonValues;
import io.agt.langchain4j.internal.message.TextProjection;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模态内容的原生编解码与形状约束。
 *
 * <p>检查实际载荷和地址，不下载媒体，也不把文本规则当作媒体语义识别。
 */
public final class MediaValues {
    private static final JacksonChatMessageJsonCodec CODEC = new JacksonChatMessageJsonCodec();

    private MediaValues() {}

    public static List<?> project(List<Content> contents) {
        var value = JsonValues.parseObject(CODEC.messageToJson(UserMessage.from(contents)));
        var projected = TextProjection.list(value.get("contents"));
        validate(projected);
        return projected;
    }

    public static List<Content> restore(List<Content> original, Object effective) {
        var before = project(original);
        var after = TextProjection.list(JsonValues.freeze(effective));
        if (before.size() != after.size()) {
            TextProjection.unsupported();
        }
        for (int i = 0; i < before.size(); i++) {
            var a = TextProjection.object(before.get(i));
            var b = TextProjection.object(after.get(i));
            TextProjection.sameKeys(a, b);
            TextProjection.fixed(a, b, "type");
            if (a.containsKey("detailLevel")) {
                TextProjection.fixed(a, b, "detailLevel");
            }
            for (String key : List.of("image", "audio", "video", "pdfFile")) {
                if (a.containsKey(key)) {
                    var x = TextProjection.object(a.get(key));
                    var y = TextProjection.object(b.get(key));
                    TextProjection.sameKeys(x, y);
                    TextProjection.fixed(x, y, "mimeType");
                }
            }
        }
        validate(after);
        var envelope =
                new LinkedHashMap<>(
                        JsonValues.parseObject(CODEC.messageToJson(UserMessage.from(original))));
        envelope.put("contents", after);
        try {
            var restored =
                    ((UserMessage) CODEC.messageFromJson(JsonValues.write(envelope))).contents();
            if (!TypedValues.equivalent(project(restored), after)) {
                TextProjection.unsupported();
            }
            return restored;
        } catch (RuntimeException error) {
            throw new GovernanceException("invalid_media_content");
        }
    }

    public static Object image(Image image) {
        return TextProjection.object(project(List.of(ImageContent.from(image))).get(0))
                .get("image");
    }

    public static Image restoreImage(Image original, Object effective) {
        var wrapper =
                new LinkedHashMap<>(
                        TextProjection.object(
                                project(List.of(ImageContent.from(original))).get(0)));
        wrapper.put("image", effective);
        return ((ImageContent)
                        restore(List.of(ImageContent.from(original)), List.of(wrapper)).get(0))
                .image();
    }

    private static void validate(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach(
                    (key, item) -> {
                        try {
                            if (item != null
                                    && (key.equals("base64Data") || key.equals("binaryData"))) {
                                String text = TextProjection.requiredString(item);
                                if (text.length() > 1_048_576) {
                                    throw new GovernanceException("payload_limit");
                                }
                                Base64.getDecoder().decode(text);
                            } else if (item != null && key.equals("url")) {
                                URI.create(TextProjection.requiredString(item));
                            }
                        } catch (IllegalArgumentException error) {
                            throw new GovernanceException("invalid_media_content");
                        }
                        validate(item);
                    });
        } else if (value instanceof List<?> list) {
            list.forEach(MediaValues::validate);
        }
    }
}
