# 副作用幂等、结果核对与显式补偿

支付、发信、下单等工具即使权限检查通过，也可能因重试、网络响应丢失或工作进程中断而重复执行。本阶段增加可选的持久副作用日志：每次执行绑定真实业务意图；成功后重试只核对并读取已有结果；已派发而结果不明时阻止重做。

这是 SDK 与可信业务后端共同履行的契约。JDBC 日志和业务提交之间没有分布式事务，不能单靠 SDK 宣称任意外部操作“恰好执行一次”。当前真实验证范围是 H2、独立 JVM 和本机 HTTP 模拟业务服务。

## 方案检查与取舍

检查了两个现成实现，并核对官方文档与接口边界：

| 方案 | 覆盖能力与适配成本 | 本次决定 |
| --- | --- | --- |
| [AWS Powertools Java Idempotency](https://docs.aws.amazon.com/powertools/java/latest/utilities/idempotency/) | 支持载荷摘要、并发执行保护、结果保存及自定义持久存储；官方默认存储为 DynamoDB。面向 Lambda，支持注解和函数式入口；其过期重做语义需要另行处理不可安全重试的未知业务结果 | 不增加 Lambda/DynamoDB 运行依赖；参考其载荷校验和并发状态划分 |
| [Temporal Java SDK](https://github.com/temporalio/sdk-java) | Apache-2.0 Java SDK，适合持久工作流；接入需要工作流服务及编排迁移，外部业务提交仍需后端幂等 | 不为一个工具执行边界引入完整工作流引擎；需要持久编排的宿主可在外层使用 |

两者都有官方文档、示例与明确扩展方式。本次没有部署或压测这些候选。复用当前 SDK 的授权、审批、预算、取消登记和审计入口，持久化沿用 JDBC 事务；自行补充未知结果不自动过期重做、宿主意图绑定、凭据核对及补偿授权。

## 先定义真实业务意图

`EffectIntent(id, expectedState, parent)` 由经过认证的宿主业务流程提供。不能直接使用模型生成的 `ToolExecutionRequest.id`、随机 traceId 或模型自报的订单号作为幂等意图。重建会话、换 Agent 或重新生成工具调用 ID，仍须指向原业务意图。

日志主键绑定 `tenantId + principalId + namespace + intentId`；正向与补偿各有一个阶段。作用范围包含主体，不是跨所有用户的全局去重。业务后端还须验证实体所有权及其自身业务唯一性。

请求指纹固定实际工具名、协议版本、改写后的参数、预期状态和补偿父凭据。同一意图改参数、改预期状态或换工具会被拒绝；确实不同的业务操作应由宿主建立新的意图。SDK 摘要只保证本项目协议内稳定，不宣称通用跨语言 JSON 规范化兼容。

`EffectRule(namespace, schema, phase, ledgerId)` 是策略中的可信声明。`ledgerId` 来自初始化后的持久目录，不能每次启动新建；多节点必须配置同一个目录身份。副作用声明进入签名委托的策略摘要，接收端不能移除声明或替换目录、阶段、命名空间与协议。未声明副作用的旧策略保留原摘要。

## 状态及恢复方式

| 状态 | 含义 | 下一次调用 |
| --- | --- | --- |
| PREPARED | 已认领，尚未通过持久派发准入 | 活跃期拒绝并发认领；期限到达后可确认未派发 |
| RUNNING | 已通过派发准入，业务请求可能已经生效 | 拒绝重复执行；期限到达后转 UNKNOWN |
| COMMITTED | 有可信成功凭据及结果摘要 | 重新授权、审批后，通过只读 `recover` 获取结果并比对摘要 |
| NOT_APPLIED | SDK 确认未派发，或可信后端确认未应用 | 相同请求允许新尝试，attempt 增加 |
| UNKNOWN | 可能生效但缺少可信结果 | 持久阻断；需要业务核对，不能等 TTL 后重做 |

期限最长 300000 毫秒，实际取与会话剩余期限的较小值。这是认领/失联判定期限，不证明业务请求在到期时已停止。数据库保存单调的已观察时间，进程重启或时钟回退不会让 UNKNOWN 恢复为可执行。

流程为：宿主绑定固定业务目标 → 正常 PRE_TOOL 授权、参数校验、检测与审批 → 原子认领 → 读取业务状态 → 重检会话和准入 → 持久 RUNNING → 调用后端 → 保存结果凭据 → 正常 POST_TOOL 检查与结果交付。业务后端必须在执行事务中再次原子校验预期状态，SDK 的前置读取不能消除 TOCTOU。

业务已提交但后置策略拒绝、会话已撤销或交付失败时，成功状态仍保留，不把失败响应当成“业务未执行”。结果核对后只读恢复不会再次进行原业务状态前置读取，因为成功后的业务状态通常已经变化。

## 后端契约

`EffectBackend` 必须由可信宿主实现，包含以下三个入口：

```java
String currentState(EffectBackend.Call call) throws Exception;
EffectBackend.Reply execute(EffectBackend.Call call) throws Exception;
Object recover(EffectBackend.Call call, EffectLedger.Proof proof) throws Exception;
```

`Call` 包含可信主键、阶段、有效参数、预期状态、稳定后端幂等键、请求指纹和 attempt。私有日志租约不发送给业务后端。后端须原子验证实体权限、幂等键对应的请求指纹、预期状态及尝试序号/fence，并把业务变更与幂等结果一起提交。后端幂等键不随任务、模型调用 ID、Agent 或日志部署身份改变。

执行结果只能明确返回：

```java
EffectBackend.Reply.committed("opaque-receipt-id", "paid");
EffectBackend.Reply.notApplied("opaque-absence-receipt-id");
EffectBackend.Reply.unknown();
```

普通异常、HTTP 超时或 UNKNOWN 都不能推断为未应用。`NOT_APPLIED` 需要真实未应用证明；仅查询到 404 不够，因为原请求可能还在网络中。将 UNKNOWN 核对为未应用前，后端必须阻止旧尝试迟到生效，例如持久 fencing/tombstone，并在新尝试中验证递增 attempt。

`recover` 是可信只读查询，验证业务实体、请求指纹与原凭据，返回原始结果。SDK 检查结果摘要；不能在查不到结果时偷偷再调用执行端。日志只保存结果摘要及不含正文的凭据标识，不缓存完整业务响应。核心 API 支持受限 JSON 值，原生 LangChain4j 工具适配当前要求 String 结果。

## 接入 JDBC 与核心 API

增加可选 `io.agt:agt-effects-jdbc` 依赖，版本与其他 AGT 模块一致。宿主提供实际 JDBC `DataSource`、驱动、认证和连接超时；H2 仅是此模块的测试依赖，不会带入生产依赖。

```java
var ledger = new JdbcEffectLedger(dataSource, "orders", Clock.systemUTC(), 3);
// 首次可信迁移阶段 initializeSchema()，把 identity() 保存到审核配置；此处为正常启动。
String catalogId = reviewedLedgerId;
if (!catalogId.equals(ledger.identity())) {
    throw new GovernanceException("effect_ledger_binding_denied");
}

var policy = StructuredPolicy.builder("orders-v1")
        .tool("pay", new ToolRule(Set.of("pay"),
                ParameterRule.object(Map.of("item", ParameterRule.string(null)), "item"),
                true,
                new EffectRule("orders", "payment-v1", EffectLedger.Phase.FORWARD, catalogId)))
        .build();

try (var control = AgentControl.builder(policy)
        .audit(auditSink)
        .approvalResolver(authenticatedApprovalResolver)
        .build();
     var session = control.openSession(authenticatedIdentity)) {
    var intent = new EffectIntent(authenticatedOrderId, expectedOrderVersion, null);
    Object result = session.effectOperation("pay", Map.of("item", "book"),
            ledger, intent, trustedBusinessBackend);
}
```

类型位于 `io.agt.core.effects`、`io.agt.core.policy` 与 `io.agt.effects.jdbc`。代码中的数据源、业务身份、审核服务、审计接收器和后端均由宿主提供。目录身份在示例部署初始化后固定；不能通过重新初始化空目录来恢复生产状态。

| 配置 | 语义与边界 |
| --- | --- |
| JDBC name | 必填，稳定目录名称；不是认证凭据 |
| Clock | 必填，宿主时间源；数据库保留已观察最大值 |
| statementTimeoutSeconds | 必填，1–30 秒；只限制 JDBC statement，连接/网络超时仍需数据源配置 |
| EffectRule.schema | 必填，宿主审核的业务参数/结果协议版本；不是自动迁移器 |
| EffectRule.phase | 必填，FORWARD 或 COMPENSATION；无默认阶段 |
| EffectIntent.parent | 正向必须为空，补偿必须提供精确的原成功凭据 |

日志以单意图行锁协调，数据库事务只重试纯元数据操作，绝不重放业务回调。检查点限制 1 MiB，核对记录最多 128 条；容量耗尽、校验失败、表缺失或存储失败均拒绝。当前 SQL 和故障行为只验收 H2，其他数据库需适配和验证。校验和用于检测意外损坏，不能防有管理权限的人重写数据库。

## 接入原生工具

```java
var governance = new LangChain4jGovernance(control);
var pay = governance.guardEffectTool("pay", ledger,
        session -> hostOrderContext.intentFor(session.context()), trustedBusinessBackend);

var assistant = governance.aiServices(Assistant.class)
        .chatModel(model)
        .effectTool(paySpecification, ledger,
                session -> hostOrderContext.intentFor(session.context()), trustedBusinessBackend)
        .build();
```

直接包装器支持同步与异步工具；Builder 的 `effectTool` 沿用真实原生工具循环、回调及后置检查。意图选择器接收宿主会话，不接收可随意更换的模型工具调用 ID。使用 Builder 时传入注册信息，由 Builder 统一包装。

原生完成回调仍在核心治理范围内，在登记成功步骤之前执行。回调异常或主动撤销会话时拒绝成功交付；已提交的业务凭据继续保留，后续只能核对恢复，不能因为观察者失败而再次变更业务。

策略声明需要副作用控制后，普通 `guardTool`、普通核心工具调用或旧的未接入执行器会在实际执行前拒绝，不能绕过日志。此入口要求 ENFORCE；观察模式不会静默执行需要可靠控制的副作用。未声明的旧工具保持既有行为，SDK 不会自动判断哪些工具有副作用。

## 审批、核对与补偿

`ApprovalRequest.action().effect()` 给出宿主绑定的 `EffectTarget`，包括命名空间、协议、阶段、日志身份、真实意图、预期状态和补偿父凭据。审批界面应与有效工具参数一起展示这些信息；它们进入审批动作摘要，参数改写后仍保留。新字段不改变普通请求的工具参数或原动作摘要。

重放也需要当前权限、有效会话和新的逐动作审批，不能拿旧审批复用。审批人认证、职责分离和审批持久化仍需后续生产审批能力。

管理员核对需要原租户、原主体及 `governance:effects:inspect` / `governance:effects:reconcile` 显式权限：

```java
var key = new EffectLedger.Key(tenantId, principalId, "orders", hostIntentId);
var entry = ledger.inspect(key, authenticatedReviewer).forward();
// 在真实业务系统中验证结果；查询不存在时，先 fence 旧尝试，不能直接断言未应用。
var proof = verifiedBusinessReceipt;
ledger.reconcile(key, EffectLedger.Phase.FORWARD, entry.attempt(),
        EffectLedger.Status.COMMITTED, proof, authenticatedReviewer, uniqueReviewId);
```

核对只允许修改对应尝试的 UNKNOWN；reviewId 幂等并绑定阶段、尝试、状态和凭据。`TrustedContext` 本身不验证 token，这些管理方法不是可直接暴露的匿名 HTTP 端点。`reconcile` 不会自行认证业务厂商或验证调用方提交的凭据，业务核对责任在可信管理适配器。

退款等补偿须注册独立工具和 `COMPENSATION` 策略，独立检查权限、参数与审批；传入同一意图 ID、补偿预期状态和精确的正向成功凭据。补偿本身也幂等并可能 UNKNOWN。补偿处理中阻止重做正向，补偿成功后不会再次交付原正向成功结果。`NOT_APPLIED` 的补偿不影响原成功结果。

现有进程内 `CompensationRegistry` 回调不等于持久补偿，也不提供远端回滚证明。本阶段不会在异常时自动退款、自动重试未知请求或让核对恢复已失效根任务；核对后仍需新的有效会话。撤销只停止新派发和交付，不能回滚已生效业务。

实际操作登记为 `BUSINESS_EFFECT`，结果不明会保留未知外部执行并冻结根任务。迟到可信结果可补记日志和外部回执，不恢复已撤销的交付。SDK 在保存已知凭据时暂时清除既有取消中断位并随后恢复；存储仍失败则保留未知状态，依赖核对，不在这个阶段重做业务。

审计沿用真实 task/session/action/agent 关联，附加意图摘要、阶段、attempt 与请求摘要；不附加原始参数、完整响应或私有租约。业务凭据的正文权限、保留/加密、可靠审计和防篡改存储仍须单独建设。

## 可重复验收

```bash
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 \
  -pl agt-langchain4j -am spotless:apply verify \
  -Dtest=EffectJournalTest,EffectTargetTest,JdbcEffectLedgerTest,EffectProcessTest,EffectToolTest,EffectDelegationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

测试会在本机临时目录创建 H2 和回环 HTTP 服务，并启动独立 Java 工作 JVM；不连接真实业务或模型厂商。关键路径包含：两个 JVM 正常重复调用只变更一次；第一个 JVM 等待后端响应时第二个不能派发；后端真实提交后强制终止工作 JVM，新 JVM 拒绝重做，可信核对后只读恢复。租约到期通过显式测试时钟推进模拟。

另覆盖多实例并发认领、回执丢失、损坏检查点、时钟回退、请求替换、重放权限和审批、后置拒绝、原生同步/异步/AI Service、取消前后竞态及独立补偿。具体结果见[结构化证据](evidence/reliable-effects-governance.json)。没有验证生产 IAM、真实支付/消息接口、跨数据库事务、持久 outbox、高可用、灾备或幂等记录清理；不能删除未知或成功记录后声称保护仍在。
