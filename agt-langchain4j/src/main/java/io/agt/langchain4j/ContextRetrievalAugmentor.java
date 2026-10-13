package io.agt.langchain4j;

import dev.langchain4j.rag.*;
import io.agt.core.*;
import java.util.concurrent.CompletableFuture;

/** 框架 RAG 上下文桥；内容在模型边界检查，本类不提供检索器 ACL。 */
final class ContextRetrievalAugmentor implements RetrievalAugmentor {
  private final AgentControl control;
  private final RetrievalAugmentor delegate;

  ContextRetrievalAugmentor(AgentControl control, RetrievalAugmentor delegate) {
    this.control = control;
    this.delegate = delegate;
  }

  public AugmentationResult augment(AugmentationRequest request) {
    control.currentSession().checkActive();
    return delegate.augment(request);
  }

  public CompletableFuture<AugmentationResult> augmentAsync(AugmentationRequest request) {
    HostSession session = control.currentSession();
    return session.supplyAsync(
        () ->
            AsyncSources.fallback(
                session, delegate.augmentAsync(request), () -> delegate.augment(request)));
  }
}
