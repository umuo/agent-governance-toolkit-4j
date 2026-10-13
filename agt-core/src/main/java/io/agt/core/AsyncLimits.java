package io.agt.core;

/** 有限工作池；同步策略/审批和阻塞工具均不得占用 provider 的完成线程。 */
public record AsyncLimits(int workerThreads, int queueCapacity) {
  public AsyncLimits {
    if (workerThreads < 1 || workerThreads > 64 || queueCapacity < 1 || queueCapacity > 4096)
      throw new IllegalArgumentException("Invalid bounded async capacity");
  }

  public static AsyncLimits defaults() {
    return new AsyncLimits(4, 128);
  }
}
