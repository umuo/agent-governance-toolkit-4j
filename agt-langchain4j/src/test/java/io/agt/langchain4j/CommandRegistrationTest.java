package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import io.agt.core.AgentControl;
import io.agt.core.GovernanceException;
import io.agt.core.execution.CommandArgument;
import io.agt.core.execution.CommandLimits;
import io.agt.core.execution.FixedCommand;
import io.agt.core.policy.StructuredPolicy;
import io.agt.langchain4j.internal.tool.ToolAdapters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 跨包装配固定命令时，未治理的注册描述不得成为可执行入口。 */
class CommandRegistrationTest {
    @TempDir Path work;

    private FixedCommand command() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        String digest =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(java)));
        return new FixedCommand(
                "report",
                java,
                digest,
                work.toRealPath(),
                List.of(CommandArgument.literal("-version")),
                CommandLimits.DEFAULT);
    }

    @Test
    void registrationCannotExecuteThroughSyncOrContextEntryPoints() throws Exception {
        var registration = ToolAdapters.registration(command());
        var request =
                ToolExecutionRequest.builder()
                        .id("registration")
                        .name("report")
                        .arguments("{}")
                        .build();
        assertEquals(
                "command_registration_not_executable",
                assertThrows(GovernanceException.class, () -> registration.execute(request, null))
                        .code());
        assertEquals(
                "command_registration_not_executable",
                assertThrows(
                                GovernanceException.class,
                                () ->
                                        registration.executeWithContext(
                                                request, InvocationContext.builder().build()))
                        .code());
        // 未提供异步实现时框架默认拒绝；不能偷偷启动默认执行器。
        var failure =
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () ->
                                registration
                                        .executeAsync(request, InvocationContext.builder().build())
                                        .join());
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                dev.langchain4j.exception.AsyncNotSupportedException.class, failure.getCause());
    }

    @Test
    void commandRegistrationCannotBeAuthorizedUnderAnotherToolName() throws Exception {
        var registration = ToolAdapters.registration(command());
        try (var control =
                AgentControl.builder(StructuredPolicy.builder("registration-v1").build())
                        .audit(r -> {})
                        .build()) {
            assertEquals(
                    "command_tool_name_mismatch",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            new LangChain4jGovernance(control)
                                                    .guardTool("another", registration))
                            .code());
        }
    }
}
