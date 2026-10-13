# v0.3 流式验收与边界

本页保留 v0.3 历史结果；当前版本见 [v0.4 数据验收](data-verification.md)。

本阶段依据 [流式需求](streaming-requirements.md)，补齐严格缓冲 handler/TokenStream，保留同步与异步行为。接入方式见 [流式指南](streaming-guide.md)。完整 OWASP 需求映射继续沿用首期的“具体缓解措施 + 已验证边界”，不增加完整覆盖标签。

## 测试映射

| 验收行为 | 证据 |
|---|---|
| 完成前零交付，拆分敏感内容，最终实际改写，重复完成 | bufferUntilFinalOutputAndRewriteSplitSecret |
| 提前工具事件无副作用，模型后置拒绝 | postModelDenyPreventsEarlyToolExecution |
| 工具循环、调用 ID、实际脱敏与八个干预点 | toolLoopPreservesIdAndActualRedaction |
| 吞掉工具拒绝不能续跑 | deniedToolCannotRecoverThroughFrameworkErrorHandler |
| 最终输出拒绝零交付 | outputDenyWithholdsAllCallbacks |
| 已取得 provider handle 取消及晚到结果 | cancellationPropagatesHandleAndDiscardsLateResponse；cancelObtainedHandleEvenWhenProviderEntryHasNotReturned |
| 字符、事件与完整投影上限 | limitsFailWithoutDeliveryAndCancelHandle；eventLimitIncludesSuppressedRawEvents；fullFinalPayloadCannotBypassStreamLimit |
| provider 异常脱敏、原文旁路和不支持配置 | terminalErrorsSanitizedAndSideChannelsRefused；configurationFailsClearlyAndReactiveCannotBypass |
| 终止前原子释放预占，保留已执行计数 | terminationReleasesReservationsBeforeNotifyingButKeepsStartedCounts；原有 asyncRagPreservesContextBeforeModelCheck 回归 |
| 关闭、过期与未启动流 | closeAndExpiryFinishPendingStreams；pendingUnstartedStreamFailsWhenSessionCloses |
| 回调内取消、回调异常与阻塞回调期限 | cancellationInsidePartialStopsCompleteCallback；callbackFailureTerminatesSessionAndSanitizesError；deadlineResolvesResultWhileApplicationCallbackIsBlocked |
| 审批允许/拒绝与原生立即返回 | approvalAndImmediateReturnKeepNativeBehavior |
| 每轮预算、完整系统消息检查 | modelBudgetCountsEachStreamRound；inputAndSystemMessagesCheckedBeforeNetwork |
| 另一线程启动仍使用原身份 | startOnAnotherThreadUsesCapturedSession |
| 动态工具、自定义上下文执行器 | dynamicExecutorUsesNativeContextAndGovernedResult |
| 高级模型装饰器、原文 listener 拒绝 | lowLevelDecoratorChecksBeforeReplayingAndRejectsRawListeners |
| 必需输出审计失败、观察模式不改写 | requiredOutputAuditFailureWithholdsStreamDelivery；evaluateOnlyRecordsWithoutApplyingStreamTransform |
| 12 个并行流的租户隔离、自动会话收尾 | concurrentStreamsKeepTenantContextThroughProviderCallbacks |
| 官方客户端 SSE 工具参数分片、下一轮真实脱敏请求、token | officialSseToolLoopDeliversOnlyRewrittenFinal |

使用真实 LangChain4j 1.20.0。官方 OpenAiStreamingChatModel 和 OpenAiChatModel 连接本机 HTTP/SSE 模拟端点，不使用外部 API key。新增 SSE 测试核对请求 stream 字段、工具参数组装、两轮请求、第二轮仅出现 masked-tool、应用只收到 masked-final，以及模型 token 总计 10、工具副作用 1。

## 运行与当前记录

~~~bash
bash scripts/verify.sh
# macOS，使用已安装的 JDK 17 与 21
bash scripts/verify-java-matrix.sh
.cache/docs-venv/bin/mkdocs build --strict
~~~

验收日期：2026-10-13；平台 macOS arm64；Java 编译基线 17；LangChain4j 1.20.0，Jackson 2.22.1。

| JDK | Core | 适配器（含流式） | 本机 HTTP/SSE | 总计 | 失败 / 错误 / 跳过 |
|---|---:|---:|---:|---:|---|
| 17.0.11 | 54 | 60 | 3 | 117 | 0 / 0 / 0 |
| 21.0.4 | 54 | 60 | 3 | 117 | 0 / 0 / 0 |

两种 JDK 均完成 Maven clean verify（含 Spotless 格式检查）、六个同步场景、七个异步/并发场景、六个流式场景和 YAML 文件策略示例。YAML 结果为 masked:original；流式改写结果为 masked-tool，只有一个应用文本块；最终输出拒绝有一个已发生的工具副作用，但零应用文本块。原文未交付不等于工具副作用可以撤销。

MkDocs strict 构建通过；新增 Mermaid 图在浏览器中检查了节点、工具循环与最终交付分支。机器可读摘要见 [v0.3 证据](evidence/streaming-verification.json)。原始 Maven/演示日志保留在本机 .cache，不提交私有路径。没有运行 Sonar，也没有新增 v0.3 开销测量或性能承诺。

历史证据保留在 [v0.2 验收](async-verification.md) 与 [v0.1 验收](verification.md)。

## 实际边界

- 本阶段抑制增量并治理最终权威响应，不对每个丢弃的片段宣称语义检测能力，也不提供实时逐 token 交付。
- 应用模型原始 listener 和 TokenStream 原文旁路回调拒绝；provider 元数据沿用原生框架行为，不把 ChatResponse 元数据说成全部受内容规则检查。
- 容量计量在 SDK 接收到事件之后发生，不能限制 provider HTTP 客户端已经分配的缓存和解析对象；UTF-16 字符计数不等于 JVM 堆字节数。
- 取消传播到已获得的 provider handle；尚未获得 handle 或其不支持取消时，只保证受控结果终止与晚到结果不交付。正在执行的工具或用户回调不能承诺强制终止。
- 流式工具仅顺序同步执行。立即返回保持框架原有完成响应（可能仅含工具调用），没有自造工具结果输出。
- 最终输出拒绝、审计失败或取消可能发生在副作用之后；不能撤销已执行的工具。
- 成功交付等待必需审计确认。失败审计沿用有界后台队列，完整读取需等 runtime close 排空，详见异步指南。
- 官方客户端回归只覆盖固定 1.20.0、本机确定性 HTTP/SSE，并不等于所有 provider、长期竞态或生产就绪证明。
- RAG/Memory 数据访问授权、Reactive、多模态、流式并发工具与跨 Agent 通信仍在后续范围。
