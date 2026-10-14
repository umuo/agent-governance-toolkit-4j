package io.agt.core.execution;

import io.agt.core.HostSession;

/**
 * 固定受信任命令的可替换执行后端。
 *
 * <p>准备阶段不得执行目标程序；实现必须处理会话终止，不能在失败后普通执行回退。
 */
public interface CommandBackend {
    void prepare(CommandExecution execution);

    String execute(CommandExecution execution, HostSession session);
}
