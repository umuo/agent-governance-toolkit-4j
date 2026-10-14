package io.agt.core.execution;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本机受信任测试程序。
 *
 * <p>用于真实子进程验证，不读取外部服务或真实凭据。
 */
public final class CommandFixture {
    public static void main(String[] args) throws Exception {
        Path marker = Path.of(args[1]);
        Path pending = marker.resolveSibling(marker.getFileName() + ".pending");
        Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
        Files.move(
                pending,
                marker,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        switch (args[0]) {
            case "echo" -> System.out.print("secret-result");
            case "environment" ->
                    System.out.print(
                            System.getenv().keySet().stream()
                                            .allMatch(
                                                    key ->
                                                            key.equals("__CF_USER_TEXT_ENCODING")
                                                                    || key.equals(
                                                                            "JAVA_MAIN_CLASS_"
                                                                                    + ProcessHandle
                                                                                            .current()
                                                                                            .pid()))
                                    ? "empty"
                                    : "inherited");
            case "timeout" -> Thread.sleep(60000);
            case "overflow" -> System.out.print("x".repeat(100000));
            case "exit" -> System.exit(3);
            case "invalid" -> System.out.write(new byte[] {(byte) 0xff});
            default -> System.out.print(args[0]);
        }
    }
}
