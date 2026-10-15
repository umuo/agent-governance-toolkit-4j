package io.agt.langchain4j.internal.rag;

import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import io.agt.core.AgentControl;
import io.agt.core.HostSession;
import io.agt.langchain4j.internal.concurrent.AsyncSources;
import java.util.concurrent.CompletableFuture;

/**
 * 框架 RAG 上下文桥。
 *
 * <p>内容在模型边界检查，本类不提供检索器 ACL，仅负责会话检查与异步执行流桥接。
 */
final class ContextRetrievalAugmentor implements RetrievalAugmentor {
    private final AgentControl control;
    private final RetrievalAugmentor delegate;

    ContextRetrievalAugmentor(AgentControl control, RetrievalAugmentor delegate) {
        this.control = control;
        this.delegate = delegate;
    }

    @Override
    public AugmentationResult augment(AugmentationRequest request) {
        control.currentSession().checkActive();
        return delegate.augment(request);
    }

    @Override
    public CompletableFuture<AugmentationResult> augmentAsync(AugmentationRequest request) {
        HostSession session = control.currentSession();
        return session.supplyAsync(
                () ->
                        AsyncSources.fallback(
                                session,
                                delegate.augmentAsync(request),
                                () -> delegate.augment(request)));
    }
}
