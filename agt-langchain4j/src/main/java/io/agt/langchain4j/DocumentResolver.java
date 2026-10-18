package io.agt.langchain4j;

import dev.langchain4j.rag.content.Content;
import io.agt.core.data.DocumentSegment;

/** 可信宿主将实际检索结果绑定到已发布版本；稳定、无副作用，不直接相信正文中的来源声明。 */
@FunctionalInterface
public interface DocumentResolver {
    DocumentSegment resolve(Content actualContent);
}
