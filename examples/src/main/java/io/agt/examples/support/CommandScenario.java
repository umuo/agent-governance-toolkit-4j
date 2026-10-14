package io.agt.examples.support;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandBackend;
import io.agt.core.execution.CommandLimits;
import io.agt.core.execution.FixedCommand;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import io.agt.langchain4j.LangChain4jGovernance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 固定命令演示的公共接线与合成工作区。
 *
 * <p>程序、摘要、工作区和模板由宿主固定；不会把模型自由文本拼接为 shell 命令。
 */
public final class CommandScenario implements AutoCloseable {
    public final Path root = Files.createTempDirectory("agt-asi05-learning-").toRealPath();
    public final Path work = Files.createDirectory(root.resolve("work"));
    public final Path canary = root.resolve("outside.txt");

    public CommandScenario() throws Exception {
        Files.writeString(canary, "synthetic-canary");
    }

    /** 演示自动算摘要；生产部署应从审核的制品清单读取预期摘要。 */
    public static String digest(Path executable) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(executable)));
    }

    public FixedCommand command(
            String executable,
            List<CommandArgument> args,
            CommandLimits limits,
            CommandBackend backend)
            throws Exception {
        Path binary = Path.of(executable).toRealPath();
        return new FixedCommand("report", binary, digest(binary), work, args, limits, backend);
    }

    /** 每个执行结果使用新会话；拒绝后旧会话不能继续使用。 */
    public static String execute(
            FixedCommand command, String arguments, String expectedCode, String expectedText) {
        var policy =
                StructuredPolicy.builder("asi05-learning-v1")
                        .tool(
                                command.name(),
                                new ToolRule(Set.of("execute"), command.parameters(), false))
                        .build();
        try (var control = AgentControl.builder(policy).audit(r -> {}).build();
                var session = control.openSession(ToolScenario.identity(Set.of("execute")))) {
            String result = null;
            String code = null;
            try {
                result =
                        new LangChain4jGovernance(control)
                                .guardCommand(command)
                                .execute(
                                        ToolExecutionRequest.builder()
                                                .id("fixed-call")
                                                .name(command.name())
                                                .arguments(arguments)
                                                .build(),
                                        null);
            } catch (GovernanceException error) {
                code = error.code();
            }
            if (!Objects.equals(code, expectedCode) || !Objects.equals(result, expectedText)) {
                throw new IllegalStateException("命令场景验收失败，实际错误码=" + code);
            }
            System.out.printf("错误码=%s 工具执行尝试=%d%n", code, session.budgetSnapshot().toolCalls());
            return result;
        }
    }

    /** 本阶段只实测 macOS；其他平台不假装原生隔离验证通过。 */
    public static boolean nativeMac() {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            System.out.println("跳过：此原生演示目前仅在 macOS 验证。");
            return false;
        }
        return true;
    }

    @Override
    public void close() throws Exception {
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
