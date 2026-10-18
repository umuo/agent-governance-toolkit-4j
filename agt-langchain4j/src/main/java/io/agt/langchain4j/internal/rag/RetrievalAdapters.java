package io.agt.langchain4j.internal.rag;

import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.data.DataResource;
import io.agt.langchain4j.DocumentResolver;
import java.util.Objects;

/**
 * 检索内部装配入口。
 *
 * <p>不属于稳定公开 API；具体包装实现保持包级可见，治理组件经检查后组装。
 */
public final class RetrievalAdapters {
    private RetrievalAdapters() {}

    public static ContentRetriever guard(
            AgentControl control, DataResource resource, ContentRetriever retriever) {
        return guard(control, resource, retriever, null);
    }

    public static ContentRetriever guard(
            AgentControl control,
            DataResource resource,
            ContentRetriever retriever,
            DocumentResolver resolver) {
        io.agt.langchain4j.internal.data.DataAccessSupport.require(
                control, Objects.requireNonNull(resource), DataResource.Kind.RETRIEVAL);
        Objects.requireNonNull(retriever);
        if (retriever instanceof GovernedContentRetriever) {
            throw new GovernanceException("already_governed");
        }
        if (retriever
                .getClass()
                .getName()
                .equals("dev.langchain4j.rag.content.retriever.ListeningContentRetriever")) {
            throw new GovernanceException("data_listener_unsupported");
        }
        return new GovernedContentRetriever(control, resource, retriever, resolver);
    }

    public static RetrievalAugmentor singleSource(
            AgentControl control, ContentRetriever retriever) {
        return new SingleSourceAugmentor(control, retriever);
    }

    public static boolean isSingleSource(RetrievalAugmentor augmentor) {
        return augmentor instanceof SingleSourceAugmentor;
    }

    public static RetrievalAugmentor context(AgentControl control, RetrievalAugmentor augmentor) {
        return new ContextRetrievalAugmentor(control, augmentor);
    }
}
