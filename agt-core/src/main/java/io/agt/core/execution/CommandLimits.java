package io.agt.core.execution;

/**
 * 固定命令执行的有限资源配置。
 *
 * <p>输出按合并 stdout/stderr 的字节计数；并发容量属于单个命令实例。
 */
public record CommandLimits(long timeoutMillis, int maxOutputBytes, int maxConcurrent) {
    public static final CommandLimits DEFAULT = new CommandLimits(5000, 65536, 2);

    public CommandLimits {
        if (timeoutMillis < 1
                || timeoutMillis > 30000
                || maxOutputBytes < 1
                || maxOutputBytes > 1048576
                || maxConcurrent < 1
                || maxConcurrent > 32) {
            throw new IllegalArgumentException("Invalid command limits");
        }
    }
}
