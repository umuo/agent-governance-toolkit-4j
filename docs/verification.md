# 首期 v0.1 历史验收与边界

本页保存首期结果；当前 v0.4 支持异步、并发、严格缓冲流式和显式数据权限，见 [最新验收](data-verification.md)。以下首期限制不代表最新功能状态。

## 验收方式

使用真实 LangChain4j 1.20.0 的 AI Services、@Tool、ToolExecutor 和 ToolProvider。模型测试包含确定性 ChatModel，以及官方 OpenAiChatModel 对接本机 HTTP 模拟端点。无需访问外部模型或配置真实 API key。

```bash
bash scripts/verify.sh
# macOS 有 JDK 17/21 时，顺序执行，避免两个 clean 互相删除输出
bash scripts/verify-java-matrix.sh
java -jar examples/target/agt-examples-0.1.0-SNAPSHOT.jar benchmark
```

矩阵命令在两个 JDK 下分别 clean 编译、测试、打包并运行六个演示与 YAML 示例。格式检查随 Maven validate 执行。原始测试报告保存在各模块 target/surefire-reports，忽略提交。

## 需求与测试证据

| 需求 | 行为证据 |
|---|---|
| 生命周期、调用计数 | eightPointsThroughRealAiServicesAndTool；fullLifecycleAndNoDuplicateCount |
| 权限、参数与租户 | permissionTenantAndUnknownToolsProduceZeroEffects；deniesArgumentsBeforeEffect；parameterNumbersArraysAndEnumsUseNoCoercion |
| 严格 JSON/YAML | strictJson；rejectsUnknownDuplicateAndIncompleteYaml；malformedToolJsonIsAuditedBeforeEffects |
| 改写和重校验 | effectiveArgumentsReachDelegateWithoutChangingCallId；transformCannotChangeTenant；modelTransformCannotAlterProtectedRoleAndReleasesBudget |
| 防泄露 | transformedToolResultNeverReachesNextModelInOriginalForm；toolResultSideChannelsAlsoRewritten；officialProviderRoundTripAndRedactionOnWire |
| 完整消息/输出 | systemHistoryThinkingAndToolArgumentsAreChecked；inputRewritingAndOutputGuardBeforeDisclosure；finalOutputDenyNeverReturnsToCaller |
| 审批 | approvalAllowDenyAndMissing；approvalTimeoutIncludesBlockingResolver；approvalExceptionAndIdentityMismatch；approvalResponseCannotBeReplayedAcrossActions；revokeCancelsPendingApproval |
| 预算/期限/隔离 | concurrentReservationNeverExceedsLimit；executionFailureConsumesBudget；modelBudgetTokensAndExpiration；concurrentTenantsHaveIndependentSessions |
| 框架错误处理 | swallowedDenialCannotResumeModelOrImmediateReturn |
| 动态/立即返回/注入 | dynamicAndImmediateToolsAreGoverned；annotatedToolKeepsTrustedInjectedMemoryId；bothToolEntryPointsAndContextPreserved |
| 未支持形态 | missingSessionAndUnsupportedAiServiceShapes；multimodalAndAsyncRefusedWithoutDelegateCalls；directToolAsyncAndComplexResultRefused |
| 故障与审计 | auditFailurePreventsSideEffectsAndIsSticky；auditFailureAfterExecutionCannotUndoEffect；jsonlDoesNotContainContentOrPrincipal；policyFailureIsSanitizedAndStopsSession |
| 资源上限 | oversizedAggregateAndRewriteExpansionRefused；prepareFailureReleasesBudgetWithoutExecution |

行为以具体测试场景为界，不等同于全部 provider、恶意 JVM 代码隔离、长期竞态或生产就绪证明。

## OWASP 映射

依据 [OWASP Top 10 for Agentic Applications 2026](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/) 建立缓解措施映射。微软原项目的覆盖标签是其自评，本项目不继承认证或「完整覆盖」结论。

| 风险 | 当前缓解措施 | 明确缺口 |
|---|---|---|
| ASI01 Agent Goal Hijack | 完整消息规则、动作权限和拒绝 | 不识别所有语义攻击或保证任务目标正确 |
| ASI02 Tool Misuse and Exploitation | 工具默认拒绝、严格参数、改写重验、执行前阻断 | 业务方法/直接 HTTP、SQL、文件调用不在受控边界 |
| ASI03 Identity and Privilege Abuse | 可信宿主身份、权限集合、租户参数绑定、会话隔离 | 宿主认证、真实数据源 ACL 不由 SDK 实现 |
| ASI04 Agentic Supply Chain Vulnerabilities | 精确注册和授权工具 | 无工具代码指纹、供应链扫描、工具来源信任体系 |
| ASI05 Unexpected Code Execution | 可拒绝危险工具或参数 | 非 JVM/进程沙箱，不阻止同 JVM 恶意代码 |
| ASI06 Memory and Context Poisoning | 检查模型消息中的历史、增强内容和结果 | 无检索前权限或记忆读写治理 |
| ASI07 Insecure Inter-Agent Communication | 首期无专用实现 | 无跨 Agent 委托、身份签名、通信协议 |
| ASI08 Cascading Agent Failures | 次数/期限预算、终止状态、审计故障关闭 | 无分布式熔断、重试去重或工具事务 |
| ASI09 Human-Agent Trust Exploitation | 有限等待、动作绑定与反重放审批 | 审批人认证、界面设计与社会工程风险由宿主负责 |
| ASI10 Rogue Agents | 会话撤销、权限和预算限制 | 无行为异常检测、跨进程隔离和全局 kill switch |

## 运行限制

- 原始组件引用不受保护；Builder 仅保证自身组装的链路。独立装饰器覆盖其声明的边界，不自动成为完整 Agent 治理。
- 首期同步文本，拒绝异步/流式/多模态/复杂返回。并发工具配置入口不开放；跨线程直接使用高级装饰器没有上下文时拒绝。
- 会话期限与撤销不能撤销已开始的工具副作用。post_tool_call 拒绝隐藏结果，不能回滚工具。
- SDK 模型调用次数不统计 provider 内部 HTTP 重试，需宿主关闭内部重试后在受控入口实施重试。
- 原始模型 listener 会早于装饰器输出检查看到响应，因此明确拒绝此配置。
- 任意宿主策略/工具实现若无限阻塞，SDK 不保证强制停止 Java 代码。审批有等待与容量限制，但不响应中断的宿主实现仍可能占用有限工作线程。
- JSONL 无多进程锁、恢复、轮转或断电保证；摘要不是签名，也不是防篡改链。
- 版本准入以制品元数据为依据，缺少或不匹配则拒绝。自定义 ClassLoader、JPMS、GraalVM native image、混用依赖、其他 provider 和真实业务环境未验证。
- 审批 demo 的即时允许回调仅用于本机演示，真实审批系统必须验证审批人并保护请求内容。

## 本机验证记录

2026-10-13，macOS arm64、Maven 3.6.3、LangChain4j 1.20.0、Jackson 2.22.1：

| 环境 | core | adapter | 本机 HTTP | 失败/错误/跳过 | 演示 |
|---|---:|---:|---:|---|---|
| JDK 17.0.11 | 40 | 22 | 1 | 0 / 0 / 0 | 六场景 + YAML 通过 |
| JDK 21.0.4 | 40 | 22 | 1 | 0 / 0 / 0 | 六场景 + YAML 通过 |

两个 JDK 均 clean 编译、格式检查、打包成功；每个环境共 63 项测试。官方 OpenAI 客户端使用本机 HTTP 验证模型 → 工具 → 模型链路，第二轮线上请求格式只携带已脱敏结果。文档严格构建通过，浏览器确认首页和架构页的三张 Mermaid 图实际渲染。

性能程序单 JVM 顺序运行，每环境 1000 次预热、5000 次采样，并通过 volatile 字段消费结果避免简单的无用调用消除。审计使用内存回调，无网络模型或持久化审计成本。原始摘要见 [verification.json](evidence/verification.json)，不是 JMH、持久化审计基准或生产 SLA。

| 环境 | 原始模型调用中位数 | 受控调用中位数 | 受控调用 P95 |
|---|---:|---:|---:|

| JDK 17.0.11 | 1.12 μs | 50.79 μs | 67.25 μs |
| JDK 21.0.4 | 1.50 μs | 49.12 μs | 77.42 μs |

这些数据仅描述本机这次冒烟测量，包含两次策略检查、消息投影、摘要、预算和审计回调；不设性能准入阈值，也不外推到真实业务负载。
