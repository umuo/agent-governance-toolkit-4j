package io.agt.examples.live.web.asi02;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import java.util.Map;

/** 对象授权对照：参数 tenant 合法，但业务查询还必须检查文档归属。使用内存表，无数据库连接。 */
public final class TenantDocumentCase implements CaseStudy {
    private final boolean guarded;
    private int entered;
    private String result = "尚未读取";

    private record Document(String tenant, String body) {}

    private final Map<String, Document> documents =
            Map.of("doc-b1", new Document("tenant-b", "DEMO-TENANT-B"));

    public TenantDocumentCase(boolean guarded) {
        this.guarded = guarded;
    }

    @Tool("读取演示文档")
    public String readDocument(String tenant, String documentId) {
        entered++;
        var document = documents.get(documentId);
        // 原始路径故意只按 ID 查询；防护路径同时检查对象归属。
        result =
                document == null || guarded && !document.tenant().equals(tenant)
                        ? "not-found"
                        : document.body();
        return result;
    }

    public StructuredPolicy policy() {
        return StructuredPolicy.builder("asi02-tenant")
                .tool(
                        "readDocument",
                        Rules.tool(
                                Map.of(
                                        "tenant",
                                        ParameterRule.string("tenantId"),
                                        "documentId",
                                        Rules.text())))
                .build();
    }

    public Map<String, Object> snapshot() {
        return Map.of("toolEntered", entered, "readResult", result);
    }
}
