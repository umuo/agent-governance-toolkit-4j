package io.agt.delegation.jdbc;

import java.nio.file.*;

/** 固定可信 Java 子进程；被 SDK JVM 遗留后由测试宿主按已知 PID 清理。 */
public final class ExternalNativeFixture {
    public static void main(String[] args) throws Exception {
        Path marker = Path.of(args[0]);
        var pending = marker.resolveSibling("job.pending");
        Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
        Files.move(
                pending,
                marker,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        Thread.sleep(60000);
    }
}
