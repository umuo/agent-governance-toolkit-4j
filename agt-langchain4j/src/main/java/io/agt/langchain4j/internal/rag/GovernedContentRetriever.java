package io.agt.langchain4j.internal.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverListener;
import dev.langchain4j.rag.query.Query;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.data.DataOperation;
import io.agt.core.data.DataResource;
import io.agt.langchain4j.DocumentResolver;
import io.agt.langchain4j.internal.concurrent.AsyncSources;
import io.agt.langchain4j.internal.data.DataAccessSupport;
import io.agt.langchain4j.internal.data.FragmentProjections;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 先授权实际绑定的数据源，再检索；检查和改写结果后才允许进入注入器。
 *
 * <p>读取正文和来源字段一同检查；只允许文字正文改写。
 */
final class GovernedContentRetriever implements ContentRetriever {
    private final AgentControl control;
    private final DataResource resource;
    private final ContentRetriever delegate;
    private final DocumentResolver resolver;

    GovernedContentRetriever(
            AgentControl control, DataResource resource, ContentRetriever delegate) {
        this(control, resource, delegate, null);
    }

    GovernedContentRetriever(
            AgentControl control,
            DataResource resource,
            ContentRetriever delegate,
            DocumentResolver resolver) {
        this.control = control;
        this.resource = resource;
        this.delegate = delegate;
        this.resolver = resolver;
    }

    @Override
    public List<Content> retrieve(Query query) {
        var session = control.currentSession();
        try {
            requireFlow(session);
            return session.dataOperationPrepared(
                    resource,
                    DataOperation.RETRIEVE,
                    RetrievalProjection.query(query, session),
                    v -> RetrievalProjection.replaceQuery(query, v, session),
                    delegate::retrieve,
                    result -> RetrievalProjection.contents(result, session),
                    (result, v) -> RetrievalProjection.replaceContents(result, v, session),
                    FragmentProjections.documents(resolver));
        } catch (RuntimeException error) {
            throw DataAccessSupport.failure(session, error);
        }
    }

    @Override
    public CompletableFuture<List<Content>> retrieveAsync(Query query) {
        HostSession session = null;
        try {
            session = control.currentSession();
            requireFlow(session);
            HostSession captured = session;
            return session.dataOperationAsyncPrepared(
                    resource,
                    DataOperation.RETRIEVE,
                    RetrievalProjection.query(query, session),
                    v -> RetrievalProjection.replaceQuery(query, v, captured),
                    prepared ->
                            AsyncSources.fallback(
                                    captured,
                                    delegate.retrieveAsync(prepared),
                                    () -> delegate.retrieve(prepared)),
                    result -> RetrievalProjection.contents(result, captured),
                    (result, v) -> RetrievalProjection.replaceContents(result, v, captured),
                    FragmentProjections.documents(resolver));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    session == null ? error : DataAccessSupport.failure(session, error));
        }
    }

    private void requireFlow(HostSession session) {
        if (resolver != null && session.dataFlowSnapshot() == null)
            throw new GovernanceException("document_flow_required");
    }

    @Override
    public ContentRetriever addListener(ContentRetrieverListener listener) {
        throw new GovernanceException("data_listener_unsupported");
    }

    @Override
    public ContentRetriever addListeners(Collection<ContentRetrieverListener> listeners) {
        throw new GovernanceException("data_listener_unsupported");
    }
}
