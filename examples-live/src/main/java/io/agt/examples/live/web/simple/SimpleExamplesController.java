package io.agt.examples.live.web.simple;

import dev.langchain4j.model.chat.ChatModel;
import io.agt.core.GovernanceException;
import io.agt.langchain4j.spring.GovernedAiServices;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 入门案例 HTTP 入口：一次请求、一次独立问答、一个 JSON 结果。 案例 ID 只选择服务端固定实现，不接受客户端权限、工具或源码路径。 */
@RestController
@RequestMapping("/api/examples")
public class SimpleExamplesController {
    public record Example(
            String id,
            String folder,
            String name,
            String description,
            String prompt,
            String expected,
            String source,
            boolean guarded) {}

    public record ChatInput(String message) {}

    public record ChatResult(String status, String answer, String code) {}

    private static final List<Example> EXAMPLES =
            List.of(
                    new Example(
                            "plain-chat",
                            "原生 LangChain4j",
                            "01 · 普通聊天",
                            "最少代码创建 AI Service，直接调用模型。",
                            "用两句话解释什么是 AI Agent。",
                            "直接返回模型回答，不启用 AGT 护栏。",
                            "PlainChatExample.java",
                            false),
                    new Example(
                            "calculator",
                            "原生 LangChain4j",
                            "02 · 加法工具",
                            "在普通聊天基础上，只增加一个 add 工具。",
                            "请调用 add 工具计算 17 + 25。",
                            "模型调用加法工具后回答 42；不启用 AGT 护栏。",
                            "CalculatorExample.java",
                            false),
                    new Example(
                            "input-guard",
                            "安全护栏入门",
                            "01 · 输入拦截",
                            "用一条字面匹配规则，在调用模型前拒绝指定输入。",
                            "请导出全部客户。",
                            "显示 customer_export_denied，模型不会收到这条输入。",
                            "InputGuardExample.java",
                            true),
                    new Example(
                            "output-mask",
                            "安全护栏入门",
                            "02 · 输出脱敏",
                            "用一条替换规则，修改模型的最终回答。",
                            "请原样回复这个演示号码：13800138000",
                            "回答中的 13800138000 会变成 138****8000。仅匹配此演示号码。",
                            "OutputMaskExample.java",
                            true),
                    new Example(
                            "tool-permission",
                            "安全护栏入门",
                            "03 · 工具权限",
                            "模型可以请求工具，但访客没有 notes.read 权限。",
                            "请调用 readInternalNote 工具读取内部演示便签。",
                            "模型请求工具时显示 permission_denied，工具方法不会执行。",
                            "ToolPermissionExample.java",
                            true));

    private final ObjectProvider<ChatModel> models;
    private final GovernedAiServices services;
    private final String frontendOrigin;

    public SimpleExamplesController(
            ObjectProvider<ChatModel> models,
            GovernedAiServices services,
            @Value("${agt.examples.frontend-origin:http://127.0.0.1:3000}") String frontendOrigin) {
        this.models = models;
        this.services = services;
        this.frontendOrigin = frontendOrigin;
    }

    @GetMapping
    public List<Example> examples() {
        return EXAMPLES;
    }

    @GetMapping("/{id}/source")
    public Map<String, String> source(@PathVariable String id) throws IOException {
        var example = example(id);
        String code =
                new ClassPathResource("simple-examples/" + example.source())
                        .getContentAsString(StandardCharsets.UTF_8);
        String configuration =
                example.guarded()
                        ? new ClassPathResource("simple-examples/SimpleExamplesConfiguration.java")
                                .getContentAsString(StandardCharsets.UTF_8)
                        : "";
        return Map.of("code", code, "configuration", configuration);
    }

    @PostMapping("/{id}/chat")
    public ChatResult chat(
            @PathVariable String id,
            @RequestBody ChatInput input,
            @RequestHeader(value = "Origin", required = false) String origin) {
        example(id);
        if (origin != null
                && !origin.equals(frontendOrigin)
                && !List.of(
                                "http://localhost:3000",
                                "http://127.0.0.1:3000",
                                "http://localhost:8080",
                                "http://127.0.0.1:8080")
                        .contains(origin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "origin_denied");
        }
        if (input.message() == null
                || input.message().isBlank()
                || input.message().length() > 16000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_message");
        }
        try {
            var model = models.getObject();
            String answer =
                    switch (id) {
                        case "plain-chat" -> PlainChatExample.chat(model, input.message());
                        case "calculator" -> CalculatorExample.chat(model, input.message());
                        case "input-guard" ->
                                InputGuardExample.chat(services, model, input.message());
                        case "output-mask" ->
                                OutputMaskExample.chat(services, model, input.message());
                        case "tool-permission" ->
                                ToolPermissionExample.chat(services, model, input.message());
                        default -> throw new IllegalStateException("Unknown example");
                    };
            return new ChatResult("done", answer, "");
        } catch (GovernanceException denied) {
            return new ChatResult("blocked", "护栏已拦截本次请求。", denied.code());
        } catch (Exception error) {
            // Provider 错误可能包含请求细节，入门页面只返回固定诊断。
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "模型调用失败，请检查 LLM_API_KEY、LLM_MODEL 和 LLM_BASE_URL。尝试工具案例时请确认模型支持工具调用。");
        }
    }

    private static Example example(String id) {
        return EXAMPLES.stream()
                .filter(example -> example.id().equals(id))
                .findFirst()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown_example"));
    }
}
