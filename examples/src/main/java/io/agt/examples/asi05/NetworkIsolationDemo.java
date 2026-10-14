package io.agt.examples.asi05;

import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandLimits;
import io.agt.examples.support.CommandScenario;
import io.agt.sandbox.SandboxCommandBackend;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实 ALLOW 对照与 DENY 请求证明网络阻断；目标仅为本机回环服务。
 *
 * <p>直接运行 main；只使用经审核的固定程序与合成数据，不运行模型生成代码。
 */
public final class NetworkIsolationDemo {
    public static void main(String[] args) throws Exception {
        if (!CommandScenario.nativeMac()) {
            return;
        }
        var accepted = new AtomicInteger();
        try (var demo = new CommandScenario();
                var server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            var thread =
                    new Thread(
                            () -> {
                                while (!server.isClosed()) {
                                    try (var socket = server.accept()) {
                                        accepted.incrementAndGet();
                                        socket.getOutputStream()
                                                .write(
                                                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .US_ASCII));
                                    } catch (Exception error) {
                                        if (!server.isClosed()) {
                                            throw new IllegalStateException("回环服务失败", error);
                                        }
                                    }
                                }
                            },
                            "asi05-loopback");
            thread.setDaemon(true);
            thread.start();
            String url = "http://127.0.0.1:" + server.getLocalPort();
            for (var network : List.of(NetworkPolicy.ALLOW, NetworkPolicy.DENY)) {
                int before = accepted.get();
                try (var backend =
                        SandboxCommandBackend.allowHostReads(
                                SandboxClient.create(),
                                SandboxPolicy.builder(demo.work)
                                        .readPolicy(ReadPolicy.HOST)
                                        .network(network)
                                        .build())) {
                    var command =
                            demo.command(
                                    "/usr/bin/curl",
                                    List.of(
                                            CommandArgument.literal("--silent"),
                                            CommandArgument.literal("--max-time"),
                                            CommandArgument.literal("1"),
                                            CommandArgument.literal(url)),
                                    CommandLimits.DEFAULT,
                                    backend);
                    CommandScenario.execute(
                            command,
                            "{}",
                            network == NetworkPolicy.ALLOW ? null : "sandbox_exit_denied",
                            network == NetworkPolicy.ALLOW ? "OK" : null);
                    int count = accepted.get() - before;
                    if (count != (network == NetworkPolicy.ALLOW ? 1 : 0)) {
                        throw new IllegalStateException("网络隔离验收失败");
                    }
                    System.out.printf("网络策略=%s，服务器实际接入=%d%n", network, count);
                }
            }
        }
    }
}
