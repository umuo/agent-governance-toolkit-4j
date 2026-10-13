# 严格缓冲流式治理

v0.3 支持 LangChain4j 1.20.0 的 handler 模型与 TokenStream 服务入口。产品仍由 AgentControl、策略、可信会话和适配器组成，沿用八个干预点。

## 接入与演示

~~~bash
java -jar examples/target/agt-examples-0.3.0-SNAPSHOT.jar stream all
~~~

演示包含允许、改写、跨租户拒绝、最终输出拒绝、容量超限、取消六个场景。应用只在最终检查通过后收到一个文本块；拒绝场景 textBlocks 为 0。最终输出拒绝发生在工具执行之后，所以演示中 toolEffects 为 1，这个副作用不能撤销。

~~~java
import dev.langchain4j.service.TokenStream;
import io.agt.langchain4j.LangChain4jGovernance;
import io.agt.langchain4j.StreamingLimits;
import java.util.concurrent.TimeUnit;

interface Assistant {
    TokenStream chat(String message);
}

var assistant = new LangChain4jGovernance(control)
        .aiServices(Assistant.class)
        .streamingChatModel(streamingModel)
        .streamingLimits(new StreamingLimits(1_048_576, 10_000))
        .tools(tools)
        .build();

try (var session = control.openSession(identity)) {
    var stream = LangChain4jGovernance.buffered(assistant.chat("查询当前租户"));
    stream.onPartialResponse(text -> render(text))
          .onCompleteResponse(response -> recordCompletion(response))
          .onError(error -> recordError(error));
    stream.start();
    stream.completion().get(30, TimeUnit.SECONDS);
}
~~~

streamingModel 由宿主创建，例如 OpenAiStreamingChatModel；实例不得注册收到原文的 listener。identity 从宿主认证结果建立。策略样例继续使用 [YAML 配置说明](user-guide.md)，模型检查与工具检查使用相同配置，不需要另一套流式策略。completion 返回 ChatResponse，模型消息正文及 attributes/thinking/toolCalls 纳入对应治理投影；provider 元数据沿用框架行为。

网页或 HTTP 服务可使用自动会话生命周期：

~~~java
return control.withSessionAsync(identity, () -> {
    var stream = LangChain4jGovernance.buffered(assistant.chat(message));
    stream.onPartialResponse(text -> sendAuthorizedText(text));
    stream.start();
    return stream.completion();
});
~~~

会话应活到交付结束。普通 openSession 必须在创建线程关闭；异步服务用 withSessionAsync。start 可在另一线程调用，但仍使用创建流时捕获的会话。创建 TokenStream 不调用模型或工具；start 才触发 input 和实际执行。重复 start 报 stream_already_started；已关闭会话中的未启动流也会使 completion 失败。

## 实际交付顺序

~~~mermaid
flowchart TD
    A[创建可信会话 / 创建受控 TokenStream] --> B[start / input]
    B --> C[pre_model_call / 预占模型预算]
    C --> D[provider 增量事件 / 仅计量并抑制]
    D --> E[完整响应 / post_model_call / 审计确认]
    E --> F{包含工具调用}
    F -->|是| G[顺序工具授权 / 审批 / 原子预算 / pre_tool_call]
    G --> H[工具执行 / post_tool_call / 结果改写]
    H --> C
    F -->|否或立即返回| I[output / 必需审计]
    I --> J[一个有效文本块 / 完成事件 / completion]
~~~

模型装饰器等待完整 ChatResponse，抑制 provider 的 partial/thinking/tool/raw 事件。最终响应是权威值：即使增量与最终文本不同，应用也只收到经过检查的最终值。原始增量不保留，也不重放。完整工具事件也不会提前传入框架，避免工具在整轮响应检查前执行。

本期流式工具按顺序执行。@Tool 返回 String/void，自定义 ToolExecutor 使用 executeWithContext 的同步路径；动态 ToolProvider 也按相同规则包装。审批等待有界，但顺序工具/审批会占用 SDK worker。立即返回工具保留原生 TokenStream 的完成响应语义（可能是带工具调用的 AiMessage，text 为空），不人为合成另一种返回值。

受控 Builder 同时检查 input/output。高级入口 guardStreamingModel(model[, limits]) 提供 StreamingChatModel 装饰器，只覆盖模型前后检查；需显式创建和绑定同一会话，再调用 chat(request, handler) 或 doChat。它在模型后置检查通过后交付单个有效文本块与完整响应，不能把它解释为完整 Agent 输出检查。

## 容量与线程

| 配置 | 默认值与含义 |
|---|---|
| StreamingLimits.maxCharacters | 1,048,576 UTF-16 code units；可设 1..16,777,216 |
| StreamingLimits.maxEvents | 10,000；可设 1..100,000 |
| Builder.streamingLimits | 本服务每轮模型流使用的上限 |
| 超限 | stream_capacity_exceeded，终止会话并尝试取消 provider |
| 工作池 | 沿用 AgentControl.asyncLimits |

字符计量包含增量 text/thinking/工具参数和最终消息 JSON 投影。原始增量与最终消息重复计量，JSON 元数据也会占额度；这不是“最多交付多少正文”的承诺。raw event 被丢弃，仅占事件计数；provider 自身的 HTTP 缓存/解析分配不属于 SDK 可限制的内存。已有 JSON 深度、长度和结构限制仍适用；策略与改写同样不能绕过形状校验。

策略、工具与框架继续处理在 SDK worker 上运行，不在 provider 完成线程执行。回调前恢复捕获的可信会话，结束后恢复线程原有绑定。应用回调应短且不阻塞；长逻辑交给宿主有界执行器。文本回调阻塞时 completion 仍会因会话期限而失败，后续完成回调不能继续；SDK 无法强制停止已经开始的任意 Java 回调。

## 取消、异常与边界

调用 stream.cancel() 或 stream.completion().cancel(true) 可以终止会话。使用 handler 入口时，通过会话 revoke/close 取消；模型装饰器交付后的 PartialResponseContext 也提供治理 handle。SDK 尝试取消已经收到的 provider StreamingHandle，未取得 handle 或 provider 不支持取消时，只保证晚到结果不交付。get(timeout) 只结束调用方等待，要停止任务需显式取消。

onPartialResponse 与 onPartialResponseWithContext 二选一，最多配置一次；它们收到的都是最终有效文本。onCompleteResponse 和 onError 同样最多一次。失败的 completion 可以先于错误回调被调用者观察到；错误只包含稳定错误码，不透传 provider 异常原文。主动取消通过取消状态反馈，不要求 onError 回调。

明确拒绝 onPartialThinking、onPartialToolCall、onRetrieved、onIntermediateResponse、beforeToolExecution、onToolExecuted、onUnmappedRawEvent 及对应 context 回调；这些通道没有自动原文放行。ignoreErrors 拒绝，安全拒绝不能变成继续执行。

Builder 拒绝流式与普通方法混用、同时配置普通 ChatModel、并发工具和异步 @Tool。Reactive Publisher、多模态和复杂输出仍不支持。流式不会提供实时逐 token 体验；这是以延迟换取执行前/交付前治理的产品选择。原始模型、原始工具引用和任意宿主 I/O 仍不受治理，RAG/Memory 访问 ACL 留待后续。

会话的 watchTermination(promise) 可供适配器把交付 future 关联到期限/撤销/关闭；它不治理 promise 内任意代码，也不计为模型或工具执行。

验证与具体覆盖边界见 [v0.3 验收](streaming-verification.md)，设计与源码依据见 [流式需求](streaming-requirements.md)。
