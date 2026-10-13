package io.agt.core;

/** 临时绑定会话，不延长会话生命周期，也不授予终止后的执行权限。 */
public final class SessionScope implements AutoCloseable {
  private final AgentControl control;
  private final HostSession previous;
  private final Thread owner = Thread.currentThread();
  private boolean closed;

  SessionScope(AgentControl control, HostSession session) {
    this.control = control;
    previous = control.swapSession(session);
  }

  @Override
  public void close() {
    if (Thread.currentThread() != owner) throw new GovernanceException("scope_owner_required");
    if (!closed) {
      closed = true;
      control.swapSession(previous);
    }
  }
}
