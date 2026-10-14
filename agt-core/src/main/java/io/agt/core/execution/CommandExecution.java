package io.agt.core.execution;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 经过模板恢复的不可变执行计划。
 *
 * <p>程序与目录由宿主绑定，参数仍须通过工具策略和固定模板；默认日志不显示原文。
 */
public record CommandExecution(
        Path executable, Path directory, List<String> arguments, CommandLimits limits) {
    public CommandExecution {
        Objects.requireNonNull(executable);
        Objects.requireNonNull(directory);
        Objects.requireNonNull(limits);
        arguments = List.copyOf(arguments);
    }

    @Override
    public String toString() {
        return "CommandExecution[argumentCount=" + arguments.size() + "]";
    }
}
