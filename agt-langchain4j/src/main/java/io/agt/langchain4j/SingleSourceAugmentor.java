package io.agt.langchain4j;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.*;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.agt.core.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * 单数据源检索管道复用原生内容注入器，避免原生路由 fallback 丢失可信会话。
 *
 * <p>查询路由和异步 fallback 不交给无会话的默认线程池。
 */
final class SingleSourceAugmentor implements RetrievalAugmentor {
  private final AgentControl control;
  private final ContentRetriever retriever;
  private final DefaultContentInjector injector = new DefaultContentInjector();

  SingleSourceAugmentor(AgentControl control, ContentRetriever retriever) {
    this.control = control;
    this.retriever = retriever;
  }

  private Query query(AugmentationRequest request) {
    if (!(request.chatMessage() instanceof UserMessage message) || !message.hasSingleText()) {
      TextProjection.unsupported();
    }
    return Query.from(((UserMessage) request.chatMessage()).singleText(), request.metadata());
  }

  private AugmentationResult inject(AugmentationRequest request, List<Content> contents) {
    return AugmentationResult.builder()
        .chatMessage(injector.inject(contents, request.chatMessage()))
        .contents(contents)
        .build();
  }

  public AugmentationResult augment(AugmentationRequest request) {
    return inject(request, retriever.retrieve(query(request)));
  }

  public CompletableFuture<AugmentationResult> augmentAsync(AugmentationRequest request) {
    var session = control.currentSession();
    return session.supplyAsync(
        () -> {
          var source = retriever.retrieveAsync(query(request));
          var result =
              new CompletableFuture<AugmentationResult>() {
                @Override
                public boolean cancel(boolean interrupt) {
                  boolean changed = super.cancel(interrupt);
                  if (changed) {
                    source.cancel(interrupt);
                  }
                  return changed;
                }
              };
          source.whenComplete(
              (contents, error) -> {
                try (var scope = session.bind()) {
                  if (error != null) {
                    result.completeExceptionally(error);
                  } else {
                    session.checkActive();
                    result.complete(inject(request, contents));
                  }
                } catch (RuntimeException failure) {
                  result.completeExceptionally(failure);
                }
              });
          return result;
        });
  }
}
