package io.agt.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.rag.content.Content;
import io.agt.core.data.DataFragment;
import io.agt.core.data.DocumentSegment;
import io.agt.langchain4j.internal.message.TextProjection;
import java.util.LinkedHashMap;
import java.util.Objects;

/** 与受控适配器一致的公开摘要入口，供可信宿主分类和查询来源历史。 */
public final class LangChain4jFragments {
    private LangChain4jFragments() {}

    public static DataFragment content(Content content) {
        return DataFragment.document(Objects.requireNonNull(content).textSegment().text());
    }

    public static DataFragment content(Content content, DocumentSegment reference) {
        return DataFragment.document(
                Objects.requireNonNull(content).textSegment().text(), reference);
    }

    public static DataFragment message(ChatMessage message) {
        var canonical =
                new LinkedHashMap<>(TextProjection.message(Objects.requireNonNull(message)));
        canonical.remove("attributes");
        return DataFragment.message(canonical);
    }
}
