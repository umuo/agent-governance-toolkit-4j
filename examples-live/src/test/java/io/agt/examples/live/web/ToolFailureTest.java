package io.agt.examples.live.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import org.junit.jupiter.api.Test;

class ToolFailureTest {
    @Test
    void bashFailureReportsExitCodeAndConfiguredNetworkWithoutClaimingRootCause() {
        for (int exitCode : new int[] {1, 6, 7, 127}) {
            var raw = new io.agt.core.GovernanceException("sandbox_exit_denied");
            raw.addSuppressed(new io.agt.examples.live.tools.BashTool.CommandFailed(exitCode));
            var diagnostic = ToolFailure.from(new RuntimeException(raw));
            assertEquals("bash_command_failed", diagnostic.code());
            assertTrue(diagnostic.message().contains("退出码：" + exitCode));
            assertTrue(diagnostic.hint().contains("DENY"));
            assertTrue(diagnostic.hint().contains("不代表所有命令失败均由网络导致"));
            assertFalse(diagnostic.toString().contains("接口未公开"));
        }
    }

    @Test
    void unwrapsKnownErrorsWithoutPublishingRawExceptionText() {
        var denied =
                ToolFailure.from(new RuntimeException(new AccessDeniedException("private-path")));
        assertEquals("file_access_denied", denied.code());
        assertFalse(denied.toString().contains("private-path"));
        var unknown = ToolFailure.from(new IOException("secret-token"));
        assertEquals("file_io_failed", unknown.code());
        assertFalse(unknown.toString().contains("secret-token"));
        assertEquals("file_io_failed", ToolFailure.from(new IOException()).code());
        assertEquals(
                "workspace_path_denied",
                ToolFailure.from(new RuntimeException(new IOException("workspace_path_denied")))
                        .code());
    }
}
