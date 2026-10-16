package io.agt.examples.live.web.asi02;

import dev.langchain4j.agent.tool.Tool;
import io.agt.core.policy.StructuredPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 模拟发件箱：未经授权的外部收件人和重复邮件；不会连接 SMTP。 */
public final class MailAbuseCase implements CaseStudy {
    private final List<String> outbox = new ArrayList<>();
    private int entered;

    @Tool("向指定收件人发送演示邮件，只写入内存发件箱")
    public String sendMail(String recipient, String body) {
        entered++;
        outbox.add(recipient + ": " + body);
        return "已写入模拟发件箱";
    }

    public StructuredPolicy policy() {
        return StructuredPolicy.builder("asi02-mail")
                .tool(
                        "sendMail",
                        Rules.tool(
                                Map.of(
                                        "recipient",
                                        Rules.choice("customer-a@tenant-a.test"),
                                        "body",
                                        Rules.text())))
                .build();
    }

    public Map<String, Object> snapshot() {
        return Map.of(
                "toolEntered",
                entered,
                "sentMessages",
                outbox.size(),
                "outbox",
                List.copyOf(outbox));
    }
}
