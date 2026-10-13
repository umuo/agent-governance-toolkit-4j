package io.agt.langchain4j;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverListener;
import dev.langchain4j.rag.query.Query;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.data.DataOperation;
import io.agt.core.data.DataResource;
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

    GovernedContentRetriever(
            AgentControl control, DataResource resource, ContentRetriever delegate) {
        this.control = control;
        this.resource = resource;
        this.delegate = delegate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        var session = control.currentSession();
        try {
            return session.dataOperationPrepared(
                    resource,
                    DataOperation.RETRIEVE,
                    DataProjection.query(query, session),
                    v -> DataProjection.replaceQuery(query, v, session),
                    delegate::retrieve,
                    result -> DataProjection.contents(result, session),
                    (result, v) -> DataProjection.replaceContents(result, v, session));
        } catch (RuntimeException error) {
            throw DataProjection.failure(session, error);
        }
    }

    @Override
    public CompletableFuture<List<Content>> retrieveAsync(Query query) {
        HostSession session = null;
        try {
            session = control.currentSession();
            HostSession captured = session;
            return session.dataOperationAsyncPrepared(
                    resource,
                    DataOperation.RETRIEVE,
                    DataProjection.query(query, session),
                    v -> DataProjection.replaceQuery(query, v, captured),
                    prepared ->
                            AsyncSources.fallback(
                                    captured,
                                    delegate.retrieveAsync(prepared),
                                    () -> delegate.retrieve(prepared)),
                    result -> DataProjection.contents(result, captured),
                    (result, v) -> DataProjection.replaceContents(result, v, captured));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    session == null ? error : DataProjection.failure(session, error));
        }
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
