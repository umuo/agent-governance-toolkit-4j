# v0.4 数据访问验收与边界

依据 [数据需求](data-requirements.md)，增加实际检索器与记忆接口的授权、改写、独立预算和审计；接入方式见 [数据使用指南](data-guide.md)。旧同步、异步和严格缓冲流式回归保持。

## 测试映射

| 行为 | 测试证据 |
|---|---|
| YAML/Java 同一裁决；非法资源/权限/操作失败 | yamlAndProgrammaticPoliciesUseSameDecisionAndEffectiveValue；invalidResourcesAndUnknownActionsFailAtLoad |
| 默认资源拒绝、访问后失败计数、观察模式 | absentResourceOrOperationDeniedWithZeroEffects；failedActualDataIoStillCountsAndAsyncDoesNotDoubleCount；observingModeRecordsWithoutRewritingOrBlocking |
| 跨租户检索零调用，同步/异步 | unauthorizedSourceNeverRetrieves |
| 实际查询与结果改写，同步/fallback | queryAndRetrievedTextActuallyRewritten |
| Query metadata 文本改写与关联字段保留 | queryMetadataTextRewritePreservesNativeInvocationContext |
| 原生 UUID/数字/枚举来源字段保留；来源改写拒绝 | nativeDocumentMetadataTypesSurviveCheckedProjection；provenanceMetadataCannotBeRewrittenToHideOriginalData |
| 所有者/操作权限限制与零读写 | memoryOwnershipBlocksReadWriteAndClear；readOnlyPermissionDoesNotPermitAppendReplaceOrClear |
| 写入改写持久化，读取改写不回写原存储 | writesPersistRewrittenValuesAndReadsDoNotMutateRawStore |
| 替换/清空计数；独立额度与在途配额 | memoryReplacementAndClearCountOnce；dataBudgetRejectsBeforeAnotherIo；inFlightDataCallConsumesSharedQuotaAndCannotBeBypassed |
| 数据后置拒绝阻止模型，写前拒绝阻止存储 | resultDenyPreventsModelAndWriteDenyPreventsPersistence |
| 身份改写与数量上限拒绝 | resourceIdentityTransformRefusedBeforeIo；itemLimitsRejectWriteBeforeIoAndReadBeforeDelivery |
| MemoryId 不建立所有权、未知 ID/冲突配置/物理 ID | registeredMemoryIdCannotEstablishOwnership；memoryIdBindingUnknownIdAndConflictingConfigurationRefused；changedPhysicalMemoryIdIsRefusedBeforeStorageIo |
| 真实同步/future/TokenStream RAG | realServicesUseAuthorizedRagContent |
| 原生异步取消与期限/关闭 | nativeAsyncSourceCancellationReachesFuture；nativeAsyncMemoryCancellationKeepsCommittedDataCount；closeOrDeadlineStopsPendingDataDelivery |
| 访问前/后的必需审计故障 | auditFailureStopsIoBeforeAccessOrStopsDeliveryAfterAccess |
| 12 个并行注册记忆的身份与数据隔离 | concurrentRegisteredMemoriesRemainIsolated |
| 原策略不能静默放行新数据入口、原文 listener 拒绝 | oldPolicyCannotSilentlyBuildDataGuardAndRawListenersRejected |
| JSONL 包含操作/资源摘要，无敏感原文 | auditContainsDataPointsAndNoSensitiveData |

测试使用真实 LangChain4j 1.20.0。新增数据 I/O 使用可观察的本机 delegate，核对实际检索/读写次数、持久化值和实际送入模型的注入内容。已有官方 OpenAiChatModel HTTP 与 OpenAiStreamingChatModel SSE 测试继续回归，不访问外部模型或使用真实 API key。

## 执行与结果

~~~bash
bash scripts/verify.sh
bash scripts/verify-java-matrix.sh
.cache/docs-venv/bin/mkdocs build --strict
~~~

验收日期：2026-10-13；平台 macOS arm64；Java 编译基线 17；LangChain4j 1.20.0，Jackson 2.22.1。

| JDK | Core | 适配器（含数据访问） | 本机 HTTP/SSE | 总计 | 失败 / 错误 / 跳过 |
|---|---:|---:|---:|---:|---|
| 17.0.11 | 59 | 92 | 3 | 154 | 0 / 0 / 0 |
| 21.0.4 | 59 | 92 | 3 | 154 | 0 / 0 / 0 |

两种 JDK 均完成 clean verify（含 Spotless），六个同步、七个异步/并发、六个流式和七个数据场景，共 26 个演示场景；原模型/工具 YAML 与新增数据 YAML 均实际加载并执行。数据改写分别输出 masked-document/masked-memory；越权场景的实际源调用为零，数据预算场景先执行一次、再拒绝第二次。

MkDocs strict 构建通过；新增 Mermaid 图和分组导航已经过浏览器检查。机器可读证据见 [v0.4 摘要](evidence/data-verification.json)。原始日志留在本机 .cache，不提交私有路径；没有运行 Sonar，也没有新增未经测量的性能结论。

历史证据保留在 [v0.3 流式验收](streaming-verification.md)、[v0.2 异步验收](async-verification.md) 和 [v0.1 验收](verification.md)。

## OWASP 缓解与限制

- ASI03：由宿主可信身份和真实资源绑定实施租户/所有者/操作权限约束；MemoryId、提示词和文档 metadata 不能作为授权依据。宿主认证和实际存储隔离仍必需。
- ASI06：进入检索器的 Query metadata、读取结果与记忆读写消息检查/改写；不能保证识别全部记忆或上下文投毒，也不提供存储事务或内容真实性认证。
- ASI02/ASI08/ASI10：数据操作许可、独立额度、期限、关闭/取消与不可恢复拒绝；SDK 不分析源内部代码或强制终止任意 Java/正在执行的存储操作。
- 取消向实际获得的源 future 传播，原生框架和 delegate 内部衍生 future 不保证全部响应；访问后的拒绝/故障不能撤销读取、写入或清空。
- 本机确定性测试不等于所有后端、长期竞态、行级 ACL 或生产就绪证明。本阶段不增加完整 OWASP 覆盖标签，也不新增未经测量的性能承诺。
