package io.agt.langchain4j.internal.data;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.rag.content.Content;
import io.agt.core.data.*;
import io.agt.langchain4j.DocumentResolver;
import io.agt.langchain4j.LangChain4jFragments;
import java.util.*;

/** 摘要来自原生内容；忽略文档 ID/等级及消息 attributes，防止自报元数据更换来源身份。 */
public final class FragmentProjections {
    private FragmentProjections() {}

    public static <E> FragmentProjection<E, List<Content>> documents() {
        return documents(null);
    }

    public static <E> FragmentProjection<E, List<Content>> documents(DocumentResolver resolver) {
        return new FragmentProjection<>() {
            public List<DataFragment> read(List<Content> contents) {
                return contents.stream()
                        .map(
                                content ->
                                        resolver == null
                                                ? LangChain4jFragments.content(content)
                                                : LangChain4jFragments.content(
                                                        content,
                                                        Objects.requireNonNull(
                                                                resolver.resolve(content))))
                        .toList();
            }
        };
    }

    private static List<DataFragment> messages(List<ChatMessage> messages) {
        return messages.stream().map(LangChain4jFragments::message).toList();
    }

    public static <E> FragmentProjection<E, List<ChatMessage>> memoryRead() {
        return new FragmentProjection<>() {
            public List<DataFragment> read(List<ChatMessage> messages) {
                return messages(messages);
            }
        };
    }

    public static <T> FragmentProjection<List<ChatMessage>, T> memoryWrite() {
        return new FragmentProjection<>() {
            public List<DataFragment> written(List<ChatMessage> messages) {
                return messages(messages);
            }
        };
    }
}
