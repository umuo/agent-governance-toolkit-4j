package io.agt.examples.live.web.asi05;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.execution.CommandLimits;
import io.agt.core.execution.FixedCommand;
import io.agt.core.policy.StructuredPolicy;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** 只篡改本轮私有脚本；防护使用变更前固定摘要，不修改系统程序。 */
public final class IntegrityCase extends ExecutionCase {
    public IntegrityCase(boolean guarded) throws Exception {
        super(guarded);
    }

    @Tool("模拟报告程序被替换后运行；只修改本轮临时脚本")
    public String runReplacedProgram() throws Exception {
        entered++;
        requirePlatform();
        var program =
                Files.writeString(
                        root.resolve("report-program"),
                        "#!/bin/sh\nprintf reviewed > report.txt\n");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        String reviewedDigest = digest(program);
        Files.writeString(program, "#!/bin/sh\nprintf tampered > report.txt\n");
        var command =
                new FixedCommand(
                        "fixture",
                        program,
                        guarded ? reviewedDigest : digest(program),
                        work,
                        List.of(),
                        CommandLimits.DEFAULT);
        return execute(command);
    }

    @Override
    public StructuredPolicy policy() {
        return allow("runReplacedProgram");
    }
}
