# v0.3 严格缓冲流式需求与选型

本阶段承接首期路线：完成异步/并发后，实现严格缓冲流式；RAG 和 Memory 访问授权留待下一阶段。

## 行为与范围

- 保留治理运行时、可信会话、八个干预点、审批和审计的产品方式；适配 LangChain4j 1.20.0 handler 模型与 TokenStream AI Services。
- 流式 provider 的原始文本、thinking、工具参数、完整工具事件和 raw event 不得提前向框架或应用传播。只有完整 ChatResponse 是模型后置检查的输入；增量与最终文本不一致时以最终响应为准，增量被丢弃。
- 完整响应通过 post_model_call 后才允许工具循环；工具每次执行仍经过独立参数/权限/审批/预算检查。最终响应通过 output 和必需审计后才重放一个有效文本块以及完成事件。
- 流式模型装饰器覆盖 pre/post_model_call；只有受控 Builder 同时覆盖 input/output。原始组件引用不受治理。
- TokenStream 延迟到 start 才执行，不允许重复启动。捕获创建时的可信会话，后续线程启动不改变身份；关闭/撤销/期限或取消后不得交付晚到结果。
- 每轮流限制累计字符和事件数；超限关闭会话并尝试取消已取得的 provider handle。字符包含增量、工具参数与最终消息 JSON 投影；最终响应与增量重复计算，不能把限额解释为最终文本长度。
- 启动前拒绝并发工具、异步注解工具、混合流式/普通方法、raw listener 和 Reactive。自定义执行器走同步工具路径。仅支持已有文本消息和文本结果。
- 应用回调支持最终有效文本（含带上下文的版本）、完成和错误。thinking/tool/retrieved/intermediate/raw 回调明确拒绝，禁止 ignoreErrors；错误对外只带稳定错误码。
- 一个结果交付块不是实时逐 token。取消不回滚副作用，未获得 provider handle 时不能承诺停止网络请求，用户回调及宿主 Java 代码不保证能强制终止。

## 源码 spike 与取舍

复用已固定版本的 LangChain4j handler API、原生 TokenStream 与工具循环，不加入另一套 reactive 框架。依据 [StreamingChatModel](https://github.com/langchain4j/langchain4j/blob/1.20.0/langchain4j-core/src/main/java/dev/langchain4j/model/chat/StreamingChatModel.java)、[TokenStream](https://github.com/langchain4j/langchain4j/blob/1.20.0/langchain4j/src/main/java/dev/langchain4j/service/TokenStream.java) 和 [AiServiceStreamingResponseHandler](https://github.com/langchain4j/langchain4j/blob/1.20.0/langchain4j/src/main/java/dev/langchain4j/service/AiServiceStreamingResponseHandler.java)。本机 Maven 对应源码也已核对：完整工具事件能预先提交工具任务，因此严格模式不把 provider 的此事件传入框架，并关闭原生并发工具配置，让完整响应驱动顺序工具执行。

内部把流收敛为单个 future，复用现有 AsyncInvocation 处理检查、预算、会话终止和审计。自行实现的缺口是事件抑制、容量计量和最终输出交付门；没有复制上游源码。LangChain4j 为 Apache-2.0，治理代码按本项目许可证归属。

## 验收

- 完成之前应用零文本/完成事件，拆分敏感文本不会泄露；post_model/output 拒绝时应用零受限内容。
- 改写后的文本同时到达 partial 和 complete，原值没有进入下一轮模型或最终回调。
- provider 提前发出的工具事件不执行工具；完整响应被拒绝则副作用为零。
- 真实工具循环预算、错误处理器、审批、立即返回均维持治理。
- 容量/事件超限、取消/关闭/期限、provider 异常、晚到响应、重复完成和重复启动均正确处理。
- 官方 OpenAiStreamingChatModel 接本机 SSE 模拟端点，覆盖拆分文本、工具参数和实际下一轮请求。
- Java 17/21 真实依赖回归，演示可运行，中文文档严格构建。
