package io.agt.examples.live.web.asi02;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.InterventionPoint;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.StructuredPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 故意展示结果拦截太晚：真实临时文件已经写入，不把 POST 拒绝冒充副作用回滚。 */
public final class LateRejectionCase implements CaseStudy {
    private final Path file;
    private int entered;

    public LateRejectionCase() throws Exception {
        file = Files.createTempFile("agt-asi02-late-", ".txt");
        Files.writeString(file, "before");
    }

    @Tool("写入演示临时文件，不接受任何路径参数")
    public String writeDemoFile() throws Exception {
        entered++;
        Files.writeString(file, "after");
        return "DENY-RESULT";
    }

    public StructuredPolicy policy() {
        return StructuredPolicy.builder("asi02-late")
                .tool("writeDemoFile", Rules.tool(Map.of()))
                .content(
                        new ContentRule(
                                "late_result_denied",
                                java.util.Set.of(InterventionPoint.POST_TOOL_CALL),
                                List.of("DENY-RESULT"),
                                Map.of()))
                .build();
    }

    public Map<String, Object> snapshot() {
        try {
            return Map.of("toolEntered", entered, "fileContent", Files.readString(file));
        } catch (Exception error) {
            throw new IllegalStateException("无法读取实验文件", error);
        }
    }

    @Override
    public void close() throws Exception {
        Files.deleteIfExists(file);
    }
}
