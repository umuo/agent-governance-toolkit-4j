# v0.2 异步与并发验收

本文件保留历史验收；当前版本见 [v0.4 数据验收](data-verification.md)。

本阶段依据 [异步需求](async-requirements.md)，保留同步行为并补齐原生 future、并发工具、上下文、取消、期限和会话生命周期。使用方式见 [异步指南](async-guide.md)。OWASP 的具体缓解措施与缺口继续使用 [首期映射](verification.md)，不增加“完整覆盖”声明。

## 测试证据

| 行为 | 测试 |
|---|---|
| 会话恢复、避免在 provider 完成线程检查 | callbacksRestoreSessionAndAvoidProviderThread |
| 线程复用清理、排队任务关闭后不挂起 | contextualExecutorCleansReusedThreads；queuedContextFutureFailsAfterCloseInsteadOfHanging |
| 源 future 取消 | cancellationPropagatesAndStopsSession；nativeModelFutureReceivesCallerCancellation；explicitAsyncExecutorKeepsNativeContextAndCancelSource |
| 关闭、撤销、过期 | closeCancelsPendingResultAndSource；expiryEndsNeverCompletingSource；runtimeCloseAndRevokeResolvePendingFutures |
| 自动管理异步会话 | ownedAsyncSessionClosesWithoutBlockingCallerOrLeakingBinding；ownedAsyncCancellationClosesSession |
| 单次顶层调用、队列容量 | overlappingTopLevelCallsRefused；capacityRejectedAndCanceledQueueSlotReclaimed |
| 审批与有效动作绑定 | asyncApprovalRemainsBoundToEffectiveAction |
| 原生异步 AI Services / @Tool future / 改写 | realAsyncServicesAndFutureToolRedactBeforeNextModel；asyncInputAndFinalOutputTransformsApplied |
| CompletionStage 与 AiMessage | stagesAiMessageAndMissingSessionUseFutureFailure |
| 吞掉拒绝仍不可续跑 | deniedAsyncToolCannotBeRestoredByErrorHandler |
| 多租户同时调用 | concurrentAsyncTenantsDoNotShareIdentities（24 个会话） |
| 并发工具共享预算、宿主执行器清理 | parallelAsyncToolsCannotExceedSharedBudget；parallelSyncToolsShareSessionWithHostExecutor |
| Memory/RAG 上下文桥 | asyncMemoryFallbackPropagatesContextAndChecksHistory；asyncRagPreservesContextBeforeModelCheck |
| 输出拒绝 | asyncFinalOutputDenyIsTerminal |
| 慢成功审计不阻止期限 future 失败 | deadlineCanFinishWhileSuccessAuditIsBlocked |
| 官方客户端真实线上请求格式 | officialProviderRoundTripAndRedactionOnWire（同步/异步各一次） |

测试使用真实 LangChain4j 1.20.0；官方 OpenAiChatModel 连接本机 HTTP 模拟服务，不访问外部模型、不需要真实 API key。HTTP 验收核对第二轮请求含脱敏结果、原值未进入请求，以及 token 总数和工具副作用计数。

## 运行方式

```bash
bash scripts/verify.sh
# macOS，分别选择已安装的 JDK 17 和 21；顺序 clean / verify / 运行所有演示
bash scripts/verify-java-matrix.sh
.cache/docs-venv/bin/mkdocs build --strict
```

脚本同时运行六个同步场景、七个异步/并发场景和 YAML 示例。测试 XML 在各模块 target/surefire-reports，Maven 日志在本机 .cache，不提交包含本机私有路径的原始日志。

## 限制与后续

- 异步 future 完成与并发预算的自动化验收不等于长期竞态、所有 provider 或生产就绪证明。
- 取消传播到适配器收到的源 future；框架内部衍生 future、自建线程和外部副作用不保证全部响应取消。晚到结果不得交付。
- 同步默认顺序；显式并发或原生异步中的多工具不是批次事务，一项拒绝不能回滚已开始动作。
- 成功交付前审计需确认。异步失败记录可能晚于异常 future；关闭 runtime 后读取完整记录，最多有界等待 5 秒排空。执行已成功而交付被取消时，执行记录与后续失败记录可并存。
- 宿主自定义策略、工具、AuditSink 和同步 future 回调需约束自己的运行时间；不能依靠 SDK 强制终止任意 Java 代码。
- Memory/RAG 桥只适配上下文、future 和取消，不实施数据源或存储 ACL。
- 原始组件引用和任意 Runnable / HTTP / SQL 仍不受治理；contextualExecutor 只传播身份，不提供自动安全沙箱。
- 流式、Reactive Publisher、多模态、复杂输出、跨 Agent 身份/通信仍未实现。

## 本机最终记录

验收日期：2026-10-13；环境：macOS arm64；Java 编译基线 17；LangChain4j 1.20.0，Jackson 2.22.1。

| JDK | Core | 适配器 | 本机 HTTP | 总计 | 失败 / 错误 / 跳过 |
|---|---:|---:|---:|---:|---|
| 17.0.11 | 53 | 34 | 2 | 89 | 0 / 0 / 0 |
| 21.0.4 | 53 | 34 | 2 | 89 | 0 / 0 / 0 |

两种 JDK 均完成 Maven clean verify、六个同步和七个异步/并发演示。YAML 文件策略另行在两个 JDK 上复测，实际结果为 masked:original，模型调用 2 次、工具执行 1 次。文档通过 MkDocs strict 构建，新增异步 Mermaid 流程图经过浏览器渲染检查。

机器可读摘要见 [验收证据](evidence/async-verification.json)。原始日志保留在本机 .cache；历史 v0.1 开销测量不作为 v0.2 性能结论，本阶段没有新增未经测量的性能承诺。
