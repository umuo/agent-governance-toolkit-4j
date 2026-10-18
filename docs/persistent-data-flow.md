# 跨服务数据流与持久来源标签

本功能防止两类边界丢失：换一个接收进程后忘记父任务已经接触机密，以及换一个任务/运行时后把此前写入的机密记忆重新当作公开。它采用单调等级、权威状态与实例级来源版本，不依赖模型自报“已脱敏”。PUBLIC 表示保密等级，不代表内容可信或没有提示注入。

同一根任务的远端接收者共用 `DataFlowLedger`；不同任务读取同一实际数据实例时，通过 `LabelStore` 继承该实例已经记录的等级。调用路径为：检查和准备 → 权威端原子检查调用配额与信息流 → 登记来源/持久写入标签 → 实际执行 → 读取结果前重检标签 → 成功审计后登记工具前置步骤。标签记录先于实际写入，实际写入未知时不删除标签。

## 组件与选型

| 组件 | 位置和作用 |
| --- | --- |
| `DataFlowPolicy`、`DataFlowState` | agt-core；沿用现有等级、资源分类、目的地与动作顺序，不新增表达式语言 |
| `DataFlowLedger` | agt-core；本机状态和远端适配共用的接口 |
| `LabelStore`、`StoredLabel`、`LabelProvenance`、`LabelHistory` | agt-core；宿主可信目录、实例等级、写入因果引用与版本链 |
| `JdbcLabelStore` | 可选 agt-provenance；JDBC 事务、单调更新、幂等动作和只读历史查询；不包含驱动或认证凭据 |
| 远端 ledger、`GrantStatusService` | agt-delegation；绑定签名授权，在同一服务执行共享配额和信息流检查 |

现成方案检查：[OPA 的数据过滤](https://www.openpolicyagent.org/docs/filtering)可以生成授权过滤条件，但不能直接替代本 SDK 的执行提交、模型交付和写入来源生命周期。本轮复用现有数据流引擎，以 JDBC 保存元数据，不另建策略语言或自制数据库。文件重启、事务与并发验证采用 [H2 2.4.240 的官方发布](https://github.com/h2database/h2database/releases/tag/version-2.4.240)，其[许可证为 MPL-2.0 或 EPL-1.0](https://github.com/h2database/h2database/blob/version-2.4.240/LICENSE.txt)。H2 仅为项目测试依赖；真实应用自行选择驱动。当前只验证了 H2，没有把未测试的其他数据库宣称为已兼容。

## 本机接入

加入 `io.agt:agt-provenance:0.6.0-SNAPSHOT`，在可信宿主启动或迁移流程中创建目录。`dataSource` 必须由宿主提供身份、连接池和连接期限：

```java
var labels = new JdbcLabelStore(dataSource, 2); // 每条 SQL 的超时，单位秒；范围 1–30。
labels.initializeSchema(); // 显式迁移入口，不在读取失败后自动重建。

var memory = new DataResource("customerMemory", DataResource.Kind.MEMORY,
        "demo-tenant", "demo-user", "customer-summary-v1");
var policy = StructuredPolicy.builder("persistent-label-demo")
        .resource("customerMemory", new DataRule(DataResource.Kind.MEMORY,
                DataRule.Scope.PRINCIPAL, Map.of(
                        DataOperation.MEMORY_READ, Set.of("memory:read"),
                        DataOperation.MEMORY_ADD, Set.of("memory:write"))))
        .build();
var flow = new DataFlowPolicy(Map.of(memory, DataLabel.CONFIDENTIAL),
        Map.of(), Map.of(), Set.of(), DataLabel.CONFIDENTIAL,
        DataLabel.CONFIDENTIAL, DataLabel.CONFIDENTIAL);
var identity = new TrustedContext("writer", "demo-tenant", "demo-user",
        Set.of("memory:read", "memory:write"));
try (var control = AgentControl.builder(policy).labelStore(labels).audit(audit).build();
     var task = control.openTaskSession(identity,
             new TaskLimits(new BudgetLimits(8, 8, 60000), 8, 4, 2, flow))) {
    task.dataOperationPrepared(memory, DataOperation.MEMORY_ADD,
            "合成数据摘要", effective -> effective,
            effective -> actualMemoryWrite(effective),
            result -> result, (original, effective) -> effective);
}
```

`audit` 和 `actualMemoryWrite` 由应用实现；生产接入可使用既有受控 ChatMemory/RAG 适配器，实际 handle 必须与声明实例一致。这个目录不包含记忆正文，只记录数据等级和动作引用。

默认不启用持久目录，`DataFlowPolicy` 仍须显式配置。配置 `.labelStore(...)` 后，数据访问或导出委托若没有任务数据流规则，会以 `data_flow_policy_required` 拒绝。仅配置数据库不会自动分类资源，不能把已有敏感数据默认为 PUBLIC。初始化新目录也不会从内容猜测等级；宿主应为既有数据提供安全的基线分类。

## 实例身份、写入与读取

资源键由完整 `DataResource.descriptor()` 的摘要产生，同时按 tenantId 分区。name、kind、tenantId、principalId、instanceId 中任何一项不同，都不是同一实例。instanceId 必须稳定对应实际分区或记忆，重启时不能随意生成新 ID 来绑定旧数据，也不能从模型或检索文档的 metadata 构造该描述符。

第一次分类或写入事件产生版本 1，后续不同动作依次增加版本。读取已知非公开实例时，会先把可信策略基线保存为 CLASSIFICATION 事件，防止后续任务降低配置而忘记已知等级；它不代表读取者生产了数据。新写入标为旧等级、当前根任务等级和目的地基线的最高者：用公开内容覆盖已经标为机密的实例也不会自动降级。数据清空、删除或任务结束都不会删除标签，没有模型可调用的清零/降级接口。

写入前先在同一个数据库事务中更新当前等级与来源事件，成功后才进入实际数据适配器。准备失败不会创建标签；实际写入失败或部分成功时，先前登记的标签保留。这可能造成保守的高等级记录，不代表该次底层写入已经成功。标签数据库与业务数据存储不是分布式事务。

读取时使用“策略基线等级与持久等级的较高者”，并在实际读取返回、交付给 Agent 之前再次查询目录版本。若另一个任务在读取期间写入敏感数据，返回前会提升根任务等级，后续低等级模型、外发或输出会被阻止。内存目录、本机任务和远端任务采用相同语义。

数据库不可达、表缺失、当前记录与历史不一致、来源格式损坏或已观察版本回退/消失均拒绝，不按公开等级回退。底层读取可能已经发生，但未经后置检查的数据结果不会交付。模型/工具的直接 HTTP、SQL、文件访问绕过 SDK 时不受此机制治理；对外响应仍应经过 `run`、`disclose` 或既有流式交付入口。

## 来源与查询

持久事件包含以下信息：

| 字段 | 含义 |
| --- | --- |
| 实例摘要、tenantId | 绑定宿主实际实例，避免不同租户、用户或分区混用 |
| revision、previousRevision | 单调版本及前一版本引用 |
| label | 本次记录后的实例保密等级 |
| kind | CLASSIFICATION 表示可信目录分类，WRITE 表示实际写入前的标签登记；分类者与生产者分别识别 |
| taskId、actionId | 由宿主 SDK 生成/传递的根任务与实际写入动作，可关联执行链路和审计 |
| sources | 有界的宿主输入、工具、数据实例或历史版本引用；不含请求/响应正文 |

`labels.read(resource)` 返回当前 `StoredLabel`；`labels.history(resource, revision)` 返回 `LabelHistory`，可沿前一版本查询写入者或分类者任务和来源引用，按 kind 区分角色。重新读取后的任务快照增加 `stored:<实例摘要>:<版本>`，由这个引用追溯具体写入。单个任务对同一实例只保留最新版本引用，之前的版本仍通过持久链可查，避免重复读取导致任务元数据无界增长。

同一实例、taskId、actionId 的重试只能匹配原来等级与来源。相同请求不增加版本，改变内容指纹返回 `label_store_action_mismatch_denied`。这只对标签记录幂等，不保证实际业务写入恰好执行一次。

每次来源记录最多 512 个引用，每个引用最多 512 字符，序列化来源正文最多 1 MiB；事务在当前实例目录行上串行化，首次写入通过唯一键竞争后重试，最多五次处理重复键/序列化冲突。SQL 使用固定表名与绑定参数。保留期、加密、备份、连接期限和数据库访问认证由宿主部署提供；当前没有自动清理历史。

## 跨进程接入与隐私

在发送端 `AgentControl` 上绑定目录，根任务配置数据流规则，随后调用 `authority.register(source)`。签名文档绑定完整数据流配置，接收会话自动获得远端 ledger，已有等级、来源和完成步骤不会因换进程重置。交接前本机受控动作必须结束；交接后原发送端的快照和输出校验绑定同一权威状态，来源只升不降。

接收端不再声明独立 `DataFlowPolicy` 或 `LabelStore` 来替代权威目录，配置冲突分别以 `data_flow_local_override_denied`、`data_flow_label_store_override_denied` 拒绝。需要新的分类或更严格策略时，由宿主统一配置权威规则，不能把接收端独立状态误当作全局状态。

权威端用 `guarded_commit` 原子检查调用配额并登记来源/标签。带数据流约束的授权不能退回旧版仅计数的 commit 接口。原动作 ID 的重试若状态等级已变化，会拒绝复用旧等级下的准入结果。`flow_complete` 只能匹配该接收者已准入的同名工具动作，SDK 在执行成功、后置检查和成功审计确认后调用。

信息流请求只包含判定必需的元数据：

- 模型/最终输出：仅干预点与会话，不发送提示词或响应正文。
- 工具：工具名与已注册的目的地标识，不发送 body；未知目的地映射为未识别值，不传递任意字符串。
- 数据：完整宿主实例描述符与操作，value 固定为 `[null]`，不发送检索查询、记忆输入或检索结果。
- 提交：动作 ID 与有效请求摘要，用于绑定幂等记录；完成步骤引用真实准入动作。

SDK 仍在受信任接收宿主上执行原始策略与完整参数规则，权威服务补充共享状态约束。以上摘要不是在不可信工作机上远程证明代码执行；宿主身份必须由认证通道提供。公共实验中的 `TestGrantEndpoint` 只用随机本机测试凭据，没有验证生产 IAM/mTLS。

远端检查不可用时拒绝，不使用缓存的较低等级继续执行。流式交付也持续检查，可能引入额外 RPC 延迟；不能以旧等级缓存优化来放行新产生的敏感内容。工具前置步骤是整根任务“已有该工具成功”的条件，不是与某份数据绑定的一次性人工审批。

## 验证和限制

`RemoteFlowProcessTest` 启动两个独立 JVM：公开阶段发送一次；一个进程读取机密后，另一进程相同外发被拒绝，执行次数不增加。前者把摘要写入实际记忆文件并登记标签。关闭原授权服务及运行时后，以新注册表、新 JDBC 数据源和新工作进程重新打开该文件，即使新策略将该实例基线设为 PUBLIC，读取仍提升为 CONFIDENTIAL，外发执行次数为零，并能查询原生产任务 ID。

持久运行时测试验证同步/异步读取期间发生敏感写入时，输出前重检阻止交付；目录失败时底层读写次数为零。另覆盖并发首写、版本不丢失、来源冲突、目录损坏、审计失败不登记前置步骤、准备后升级重检，以及信息流拒绝前已预占的远端用量正常释放，不被误记为未知费用。官方模型客户端的独立 JVM 用例验证低等级模型服务收到的请求为零。

初始阶段仅提供实例/分区级标签；后续已补[内容寻址片段/消息来源](fragment-provenance.md)，仍不做字节级污点分析。标签目录自身不恢复原授权租约、动作步骤或费用账本；这些状态可显式使用[持久授权后端](durable-authority.md)保存，但不自动复活失效任务。目录不是防篡改审计，拥有数据库管理权限的人仍可改写记录；其他数据库后端、生产认证、加密与保留策略也仍需验证。进度见[安全补齐计划](security-hardening-plan.md)，证据见[数据流与持久来源验收](evidence/persistent-data-flow-governance.json)。

全工程回归 614 项，613 通过、1 项可选真实 Provider 测试跳过。补充独立 JVM 直接重新打开数据库文件后，相关 130 项通过，验证原等级和来源不依赖当前进程堆内状态。此验证不等于模拟断电或恢复原授权服务会话。

后续已补[内容寻址文档片段/消息来源](fragment-provenance.md)：受控原生适配器记录摘要、片段分类和写入版本；实例级约束仍作为上界，不自动降低，内容摘要不等于逻辑文档版本。

可信文档修订现在可显式接入[逻辑文档版本与清单](document-versions.md)，旧内容摘要路径保持兼容；版本与真实后端绑定必须由宿主提供。
