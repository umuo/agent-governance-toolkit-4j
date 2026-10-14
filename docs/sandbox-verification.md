# v0.6 当前沙箱接入验收

本阶段直接引用 Central `io.github.umuo:agent-sandbox-sdk:0.1.0-alpha.1`，没有使用 sandbox-demo 正在维护的源码或未发布 API。新增可选 agt-sandbox，复用原有固定命令工具治理。

## 新增证据

| 行为 | 证据 |
|---|---|
| execute / executeWithContext / executeAsync 同一治理路径 | effectiveArgumentsAndRedactedResultsReachRealToolEntryPoints；SDK 收到 new，交付 masked，工具计数 1，无原文回调/额外环境/stdin。 |
| 权限、审批拒绝零 SDK 调用 | permissionsAndApprovalRefusalsNeverReachSdk |
| 审批允许绑定有效参数 | approvalAllowedExecutesOnceAndUnsupportedCapabilitiesRefuse |
| 截断、聚合超限、UTF-8、退出、超时、SDK 异常安全关闭 | unsafeSdkResultsAndErrorsNeverPropagateRawContent，9 类结果；SDK 私有错误不进入公开消息。 |
| HOST 需显式 opt-in，目录/能力/路径搜索不得绕过 | hostReadsRequireOptInAndBindingMustMatch；unsupported capability 明确拒绝。 |
| SDK DTO 长度约束准备阶段检查 | sdkRequestConstraintsAreCheckedBeforeExecutionCount，工具计数为 0。 |
| SDK 不响应取消时调用方不挂起、后端不复用 | uncooperativeSdkCannotBlockCallerOrReuseBackend，覆盖 timeout/revoke/close/cancel。 |
| 零队列、有限容量、关闭后拒绝 | noQueueAndClosedBackendRefuseWithoutAdditionalSdkCalls |
| 晚到取消不误中断池线程的新任务 | lateCancellationDoesNotInterruptReusedSdkWorker |
| 真实 AI Services 工具循环 | realAiServicesLoopUsesCheckedSandboxResult；真实框架 + fake SDK runner 接入契约，两个模型调用、一个工具调用。 |
| 真 SDK 工作区写与脱敏交付 | hostOptInAllowsWorkspaceWriteAndDeliversRedactedOutput |
| 只读配置不恢复隐式写权限 | readOnlyWorkspaceDoesNotRegainImplicitWriteGrant |
| 真 SDK 工作区外写拒绝 | outsideWriteIsDeniedAndOriginalCanaryUnchanged；合成 canary 内容未改变。 |
| 真 SDK 网络拒绝 | denyNetworkPreventsLoopbackConnectionWithAllowedControl；ALLOW 对照接入回环服务，DENY 没有接入。 |
| strict 没有降级 | strictReadEitherPreflightRefusesOrActuallyBlocksUndeclaredCanary；本机 alpha.1 profile 被拒绝，目标启动标记不存在。 |

前 10 行是接入/故障契约，fake runner 不证明内核隔离；后 5 行使用真实 Central 制品和 macOS Seatbelt。原生测试在其他 OS 明确跳过，不能当作跨平台通过。

## 当前实际行为

本机 macOS 15.5 arm64：HOST/DENY 的工作区写隔离及网络拒绝有效；HOST 不提供读取机密隔离。DECLARED_ONLY 的 profile 在 alpha.1 下不可用，工具收到 sandbox_unavailable，没有目标 workspaceWrite。适配器没有修改 SDK 权限或自动使用普通进程。

七个新演示：host-allowed、async-allowed → masked/写入生效；permission-denied → 无 SDK 调用/工具计数 0；strict → sandbox_unavailable/无目标写入/执行尝试计数 1；outside-write → sandbox_exit_denied/canary 未改变；output-limit → sandbox_output_limit；timeout → sandbox_timeout。

调用方等待有界，但 SDK 后台工作可能继续。取消/超时/未知执行失败后 backend 永久隔离，不能用重建实例替代外部回收核验。源码级保护避免了已完成任务的晚到取消中断其他任务正在使用的池线程。未提供 SDK 回收确认、CPU/内存/磁盘/PID 硬限额，也未运行 Windows/Linux 原生后端。

## 验证记录

2026-10-14，Java 17 编译基线，LangChain4j 1.20.0，SDK 0.1.0-alpha.1 / JNA 5.19.1，macOS arm64。最终 JDK 矩阵及测试计数见 [sandbox-verification.json](evidence/sandbox-verification.json)。

| JDK | core | LangChain4j（含 HTTP/SSE） | sandbox 契约 / 原生 | 合计 | 失败 / 错误 / 跳过 |
|---|---:|---:|---:|---:|---|
| 17.0.11 | 74 | 114 | 23 / 5 | 216 | 0 / 0 / 0 |
| 21.0.4 | 74 | 114 | 23 / 5 | 216 | 0 / 0 / 0 |

两个 JDK 均通过 clean verify、40 个原有演示场景与 2 个 YAML 示例。安全教学补充另有 ASI02 的 16 个、ASI05 的 9 个独立场景类，两个 JDK 均实际运行通过；其中修复审批线程超时错误码竞争，增加 2 项核心回归测试。见 [逐场景学习指南](security-demos.md)。MkDocs 严格构建通过，浏览器确认了新增沙箱指南导航与首页 Mermaid 流程图渲染。

原有模型/工具/异步/流式/RAG/记忆/固定命令测试全部回归；新增 SDK 依赖没有进入单独 agt-core/agt-langchain4j 的运行时依赖。验证使用本机 HTTP/SSE 模拟端点与合成 canary，无外部模型 API key、真实秘密或不可信下载程序。

```bash
bash scripts/verify-java-matrix.sh
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar sandbox all
```

本次没有新增性能测量或 Sonar 扫描，不将单元测试数换算为 OWASP 覆盖率。ASI05 新增 OS 后端的具体已验证措施，但仍不是任意代码或 VM 级安全保证。历史 [v0.5 验收](command-verification.md) 与 [接入前调研](sandbox-integration-review.md) 保留原始阶段结论；维护中的下一 SDK 版本须完成真实依赖回归后再升级。
