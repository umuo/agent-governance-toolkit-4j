package io.agt.core;

import io.agt.core.approval.ApprovalResolver;
import io.agt.core.audit.*;
import io.agt.core.data.DataLimits;
import io.agt.core.policy.StructuredPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 一个不可变配置的治理运行时可服务多个隔离会话。运行时拥有审批线程池，审计 sink 由宿主关闭。 */
public final class AgentControl implements AutoCloseable {
  final PolicyEvaluator evaluator;
  final String version;
  final BudgetLimits budget;
  final DataLimits dataLimits;
  final EnforcementMode mode;
  final ApprovalResolver approvalResolver;
  final long approvalTimeoutMillis;
  final ThreadPoolExecutor approvals;
  private final AuditSink audit;
  final ThreadPoolExecutor workers;
  final ScheduledThreadPoolExecutor deadlines;
  private final ThreadPoolExecutor failureAudit;
  private final ThreadLocal<HostSession> current = new ThreadLocal<>();
  private final Set<HostSession> sessions = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;
  private volatile boolean auditFailed;

  private AgentControl(Builder b) {
    evaluator = Objects.requireNonNull(b.evaluator);
    version = Identifiers.require(evaluator.version());
    budget = Objects.requireNonNull(b.budget);
    dataLimits = Objects.requireNonNull(b.dataLimits);
    mode = Objects.requireNonNull(b.mode);
    audit = Objects.requireNonNull(b.audit, "An audit sink is required");
    approvalResolver = b.approvalResolver;
    approvalTimeoutMillis = b.approvalTimeoutMillis;
    AsyncLimits limits = Objects.requireNonNull(b.asyncLimits);
    workers =
        new ThreadPoolExecutor(
            limits.workerThreads(),
            limits.workerThreads(),
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(limits.queueCapacity()),
            r -> {
              Thread t = new Thread(r, "agt-worker");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    failureAudit =
        new ThreadPoolExecutor(
            1,
            1,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(limits.queueCapacity()),
            r -> {
              Thread t = new Thread(r, "agt-failure-audit");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    deadlines =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread t = new Thread(r, "agt-deadline");
              t.setDaemon(true);
              return t;
            });
    deadlines.setRemoveOnCancelPolicy(true);
    approvals =
        new ThreadPoolExecutor(
            2,
            2,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            r -> {
              Thread t = new Thread(r, "agt-approval");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  public static Builder builder(PolicyEvaluator evaluator) {
    return new Builder(evaluator);
  }

  public static Builder fromPath(Path path) {
    return builder(StructuredPolicy.fromPath(path));
  }

  public void requirePoints(Set<InterventionPoint> points) {
    if (!evaluator.interventionPoints().containsAll(points))
      throw new GovernanceException("point_not_configured");
  }

  public HostSession openSession(TrustedContext context) {
    return createSession(context, false);
  }

  /** 不隐式绑定调用线程，允许 future 完成后在完成线程关闭。 */
  public HostSession openDetachedSession(TrustedContext context) {
    return createSession(context, true);
  }

  private HostSession createSession(TrustedContext context, boolean detached) {
    Objects.requireNonNull(context);
    checkRuntime();
    requirePoints(EnumSet.of(InterventionPoint.AGENT_STARTUP, InterventionPoint.AGENT_SHUTDOWN));
    HostSession session = new HostSession(this, context, detached ? null : current.get(), detached);
    sessions.add(session);
    try {
      session.evaluate(InterventionPoint.AGENT_STARTUP, "startup", null, Map.of());
      checkRuntime();
      if (!detached) current.set(session);
      session.startDeadline();
      return session;
    } catch (RuntimeException e) {
      sessions.remove(session);
      session.revoke();
      throw e;
    }
  }

  public HostSession currentSession() {
    checkRuntime();
    HostSession session = current.get();
    if (session == null) throw new GovernanceException("missing_session");
    session.checkActive();
    return session;
  }

  public Object evaluate(InterventionPoint point, Object target) {
    return currentSession().evaluate(point, UUID.randomUUID().toString(), null, target);
  }

  public String run(String input, java.util.function.Function<String, String> execute) {
    return currentSession()
        .run(input, v -> execute.apply((String) v), v -> v, (original, v) -> (String) v);
  }

  public String runTool(
      String name,
      Map<String, Object> arguments,
      java.util.function.Function<Map<String, Object>, String> execute) {
    return currentSession()
        .operation(
            true,
            name,
            arguments,
            v -> {
              if (!(v instanceof Map<?, ?>))
                throw new GovernanceException("invalid_tool_arguments");
              @SuppressWarnings("unchecked")
              Map<String, Object> effective = (Map<String, Object>) v;
              return execute.apply(effective);
            },
            v -> v,
            (original, v) -> (String) v);
  }

  void checkRuntime() {
    if (closed) throw new GovernanceException("runtime_closed");
    if (auditFailed) throw new GovernanceException("audit_failed");
    if (!version.equals(evaluator.version()))
      throw new GovernanceException("policy_version_changed");
  }

  void emit(AuditRecord record) {
    boolean failedWrite = false;
    synchronized (this) {
      if (auditFailed) throw new GovernanceException("audit_failed");
      try {
        audit.write(record);
      } catch (Exception e) {
        auditFailed = true;
        failedWrite = true;
      }
    }
    // 不持有 runtime 锁取消 future，避免与会话/完成回调发生锁序反转。
    if (failedWrite) {
      sessions.forEach(s -> s.refuse("audit_failed"));
      throw new GovernanceException("audit_failed");
    }
  }

  HostSession swapSession(HostSession session) {
    HostSession old = current.get();
    if (session == null) current.remove();
    else current.set(session);
    return old;
  }

  /** 提交时捕获会话；受控入口检查有效性，finally 恢复绑定。不拦截任意业务 Runnable。 */
  public Executor contextualExecutor(Executor executor) {
    Objects.requireNonNull(executor);
    return task -> {
      HostSession session = currentSession();
      try {
        executor.execute(
            () -> {
              try (var scope = session.bind()) {
                task.run();
              }
            });
      } catch (RejectedExecutionException e) {
        throw session.refuse("async_capacity_exceeded");
      }
    };
  }

  public Executor asyncExecutor() {
    return contextualExecutor(workers);
  }

  public CompletableFuture<String> runAsync(
      String input,
      java.util.function.Function<String, ? extends CompletionStage<String>> execute) {
    try {
      return currentSession()
          .runAsync(input, v -> execute.apply((String) v), v -> v, (v, e) -> (String) e);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /** 为 HTTP/异步服务管理会话生命周期；回调应使用受控 Builder 或 core runAsync。 */
  public <T> CompletableFuture<T> withSessionAsync(
      TrustedContext context, java.util.function.Supplier<? extends CompletionStage<T>> invoke) {
    HostSession session;
    try {
      session = openDetachedSession(context);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    CompletableFuture<T> source;
    try (var scope = session.bind()) {
      source = Objects.requireNonNull(invoke.get()).toCompletableFuture();
    } catch (RuntimeException e) {
      try {
        session.close();
      } catch (RuntimeException ignored) {
      }
      return CompletableFuture.failedFuture(
          e instanceof GovernanceException ? e : new GovernanceException("async_execution_failed"));
    }
    var result =
        new CompletableFuture<T>() {
          @Override
          public boolean cancel(boolean interrupt) {
            boolean changed = super.cancel(interrupt);
            if (changed) source.cancel(interrupt);
            return changed;
          }
        };
    source.whenComplete(
        (value, error) -> {
          Throwable failure = error;
          while (failure instanceof CompletionException && failure.getCause() != null)
            failure = failure.getCause();
          try (var scope = session.bind()) {
            try {
              session.close();
            } catch (RuntimeException closeError) {
              failure = closeError;
            }
            if (failure == null) result.complete(value);
            else
              result.completeExceptionally(
                  failure instanceof GovernanceException || failure instanceof CancellationException
                      ? failure
                      : new GovernanceException("async_execution_failed"));
          }
        });
    return result;
  }

  void auditFailureAsync(Runnable write) {
    try {
      failureAudit.execute(
          () -> {
            try {
              write.run();
            } catch (RuntimeException e) {
              /* emit 已使运行时故障关闭。 */
            }
          });
    } catch (RejectedExecutionException e) {
      if (!auditFailed) {
        auditFailed = true;
        sessions.forEach(s -> s.refuse("audit_failed"));
      }
    }
  }

  void detach(HostSession session, HostSession previous) {
    sessions.remove(session);
    if (current.get() == session) {
      if (previous == null) current.remove();
      else current.set(previous);
    }
  }

  @Override
  public void close() {
    closed = true;
    sessions.forEach(s -> s.refuse("runtime_closed"));
    approvals.shutdownNow();
    workers.shutdownNow();
    deadlines.shutdownNow();
    failureAudit.shutdown();
    try {
      if (!failureAudit.awaitTermination(5, TimeUnit.SECONDS)) {
        auditFailed = true;
        failureAudit.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failureAudit.shutdownNow();
    }
    current.remove();
  }

  public static final class Builder {
    private final PolicyEvaluator evaluator;
    private BudgetLimits budget;
    private DataLimits dataLimits;
    private EnforcementMode mode = EnforcementMode.ENFORCE;
    private AuditSink audit;
    private ApprovalResolver approvalResolver;
    private long approvalTimeoutMillis = 30_000;
    private AsyncLimits asyncLimits = AsyncLimits.defaults();

    private Builder(PolicyEvaluator evaluator) {
      this.evaluator = Objects.requireNonNull(evaluator);
      budget = evaluator instanceof StructuredPolicy p ? p.budget() : BudgetLimits.defaults();
      dataLimits = evaluator instanceof StructuredPolicy p ? p.dataLimits() : DataLimits.defaults();
    }

    public Builder dataLimits(DataLimits value) {
      dataLimits = Objects.requireNonNull(value);
      return this;
    }

    public Builder asyncLimits(AsyncLimits value) {
      asyncLimits = Objects.requireNonNull(value);
      return this;
    }

    public Builder budget(BudgetLimits value) {
      budget = value;
      return this;
    }

    public Builder mode(EnforcementMode value) {
      mode = value;
      return this;
    }

    public Builder audit(AuditSink value) {
      audit = value;
      return this;
    }

    public Builder approvalResolver(ApprovalResolver value) {
      approvalResolver = value;
      return this;
    }

    public Builder approvalTimeout(Duration value) {
      long ms = value.toMillis();
      if (ms < 1 || ms > 300_000)
        throw new IllegalArgumentException("Approval timeout must be 1..300000 ms");
      approvalTimeoutMillis = ms;
      return this;
    }

    public AgentControl build() {
      return new AgentControl(this);
    }
  }
}
