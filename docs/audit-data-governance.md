# 加密审计与正文数据治理

核心 `AuditRecord` 继续只保存动作摘要和治理结果。新增可选 `agt-audit-jdbc`：按实际宿主租户、主体和任务保存加密记录；正文默认关闭，明确选定字段和干预点后才脱敏采集；读取、保留与清理分别受控。

本指南描述 SDK 审计存储。教学页面现有 `TraceStore` 快照是另一条存储路径，尚未迁移到这个加密库，生产管理面认证也没有在本阶段自动完成。启用新审计库不会自动加密既有教学快照、终端日志或第三方 Provider 日志。

## 现成方案检查

本阶段先核对官方资料与接入边界：

| 方案 | 能力与成本 | 本次选择 |
| --- | --- | --- |
| [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html) | 强调采集目的、排除敏感数据、访问控制、可靠性与日志保护；属于设计参考，不是存储实现 | 用于划分元数据、正文权限与故障验收 |
| [Tink Java](https://github.com/tink-crypto/tink-java) / [AEAD](https://developers.google.com/tink/aead) | Apache-2.0 密码库，提供认证加密与密钥管理机制；不提供本项目需要的审计事务、主体隔离、保留规则和独立检查点 | 本阶段复用 Java 17 JCA 标准算法，不增加密码库依赖；KMS/Tink 适配仍可由宿主提供 |
| [Trillian](https://github.com/google/trillian) / [Tessera](https://github.com/transparency-dev/tessera) | 两者为 Apache-2.0；Trillian 已处于维护模式，官方建议新部署先看 Tessera。Tessera 是 Go 透明度日志库，Java 宿主仍需跨栈部署和存储适配；不直接处理本项目私有正文的采集/权限 | 后续外部见证集成重新评估 Tessera，不将 Trillian 视为新部署的默认选择 |
| [immudb](https://github.com/codenotary/immudb) | 提供可验证存储与历史；当前仓库使用 Business Source License 1.1，部署前需另审授权条件，不能按 Apache/MIT 使用假设处理；仍需要服务和客户端验证 | 本阶段没有部署或认证其客户端，不声称已经集成 |

已有 JDBC、授权拒绝和审计同步确认路径可以复用。本阶段补充审计领域协议、独立见证与策略校验，密码计算使用 JDK 的 `AES/GCM/NoPadding` 和 `HmacSHA256`，不编写自定义密码算法。[JCA 官方文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/Cipher.html)说明了 GCM 的 AAD 和 nonce 要求；这里每次加密使用新的 96 位随机 nonce 和 128 位认证标签。

上述候选并未部署、压测或获得生产认证。密钥使用量限制、轮换、生产 KMS/HSM 和数据库故障切换仍需实际接入验证。

## 从实际会话绑定范围

`AuditEvent(eventId, scope, record, body)` 包含三个彼此分离的部分：

- `AuditScope`：实际宿主会话的 tenantId、principalId、taskId、sessionId。
- `AuditRecord`：既有治理元数据、动作摘要及可选执行关联。
- `AuditBody`：明确启用后的采集投影，或者不采集/超限原因，不进入 `AuditRecord`。

模型正文中的 `tenantId`、`principalId`、traceId 或自报信任不会替换范围。异步路径使用原会话对象，不依赖读取线程当前的身份。父子会话沿用各自 sessionId 和共享真实 taskId。

旧 `AuditSink` lambda 和 `JsonlAuditSink` 默认仍只接收元数据。采集策略在 `AgentControl` 构造时快照；接收器收到已采集正文却没有实现事件接收时拒绝，不能把正文默默降级到旧文件写入器。启用采集应使用完整事件接收器，以保留省略原因等状态。装饰器应转发 `bodyPolicy()`、完整事件和 `close()`；`AgentTracer.correlate` 已支持这条路径，并保留原 eventId、范围和正文。

接收器和数据源生命周期仍由宿主管理；`AgentControl.close()` 不负责关闭宿主共享的审计接收器。宿主关闭关联包装器时，会继续关闭其委托接收器。

## 明确选择采集内容

```java
var bodies = new AuditBodyPolicy(
        Set.of(InterventionPoint.PRE_TOOL_CALL, InterventionPoint.POST_TOOL_CALL),
        Set.of("payload", "messages", "text"), // 对象只选这些顶层字段
        true,                                 // 明确允许自由文本
        50000,                                // 原始与有效目标合计字符上限
        16000,                                // 脱敏后 UTF-8 字节上限
        Set.of("contactEmail", "业务密钥")); // 任意层级额外屏蔽字段
```

默认是 `AuditBodyPolicy.NONE`，不遍历或保存目标正文。单独构造策略时必须明确给出干预点、字段集合、自由文本开关和容量。startup/shutdown 不允许采集正文。

选择后的子树递归屏蔽凭据字段、HTTP headers、Cookie、Bearer/API key 标记及常见凭据赋值。字段比较先做 Unicode NFKC 归一化，额外排除项同样生效。字符串中的 JSON 先解析再处理；不可解析的 JSON 外观内容省略。自由文本并不因此自动获得完整 PII 检测，宿主仍须正确选字段、排除敏感业务项。

只保存实际干预点的原目标和有效目标投影。它们是 SDK 层的结构化请求/返回，不等同于 Provider 原始 HTTP 报文；不采集 HTTP 身份头。采集不会改写真正传给模型或工具的业务数据，业务脱敏/外发限制仍由原策略与信息流机制执行。

输入、投影深度或存储大小超限时整份正文省略，记录 `input_limit`、`projection_limit` 或 `storage_limit`，不保存未完成脱敏的前缀。默认/未选点是 `disabled`，非正文执行事件是 `not_collected`。这些事件仍保留正常治理元数据。

## 初始化与运行

增加 `io.agt:agt-audit-jdbc`，版本与其他 AGT 模块保持一致。H2 仅为测试依赖，生产驱动由宿主提供。

```java
var policy = new AuditVaultPolicy(bodies,
        Duration.ofHours(24).toMillis(),   // 正文保留
        Duration.ofDays(30).toMillis(),    // 完整审计记录保留
        1000,                             // 每个主体流最大条目数，含访问/清理事件
        16 * 1024 * 1024L);               // 仍保留的密文总容量

var witness = new JdbcAuditWitness(independentWitnessDataSource, 3);
var vault = new JdbcAuditVault(primaryDataSource, "governance", reviewedCatalogId,
        policy, hostKeyProvider, witness, Clock.systemUTC(), 3);

// 仅首次可信部署迁移：witness.initializeSchema(); vault.initializeSchema();
vault.identity(); // 正常启动校验已审核目录、策略和密钥，不能自动重建空库

try (var control = AgentControl.builder(runtimePolicy).audit(vault).build();
     var session = control.openSession(authenticatedIdentity)) {
    // 继续使用已有受治理模型、工具、检索和记忆入口。
}
```

`reviewedCatalogId` 由首次可信部署确定并持久配置；不能每次重启重新生成。数据源、实际身份、策略和密钥提供者由宿主绑定。Spring 宿主仍显式创建 `AgentControl`，可将 vault 或 `tracer.correlate(vault)` 传给其 builder，Starter 不替用户创建默认身份或密钥。

| 配置 | 范围/默认与含义 |
| --- | --- |
| maxInputCharacters | 显式配置，1–1048576；原始和有效目标序列化长度之和 |
| maxStoredBytes | 显式配置，1–65536；采集投影 UTF-8 大小 |
| fields / excludedFields | 各最多 64 项；顶层允许与任意层排除；省略第六参数时无额外排除 |
| bodyRetentionMillis | 显式配置，至少 1 毫秒 |
| recordRetentionMillis | 不短于正文，最多 3650 天；按可信写入时间计算，不采信事件自报时间 |
| maxEntriesPerStream | 显式配置，1–10000；包含 SDK、读取和清理事件 |
| maxRetainedBytes | 四参数构造器默认 16 MiB；显式范围 128 KiB–64 MiB；其中 64 KiB 留给管理事件 |
| statementTimeoutSeconds | 各 JDBC 适配器显式配置，1–30 秒；连接获取和网络超时须另配到数据源 |

每条元数据的固定上限为 32 KiB、最多 64 项机器告警；超限拒绝审计写入并沿用 `audit_failed` 语义。自定义策略插件不能把正文塞进原因代码/告警或无界扩展这些字段。

校验链会扫描该主体的有界历史，当前优先保证明确的恢复/篡改语义，不是大规模 SIEM 查询引擎。容量满会拒绝，不删除旧证明、自动换 catalog 或重置序列。生产需要容量监控、归档/分区迁移与适当连接池并发限制。

## 加密、链与独立见证

元数据和正文分别用 AES-256-GCM 加密；AAD 绑定 catalog、主体流、序号、eventId、类型、密钥版本、保留期限和投影身份，并区分 METADATA 与 BODY，防止交换列或跨事件复用。数据库只保存 key id；宿主提供不同的加密键与 HMAC-SHA256 键。

每条头部的 HMAC 绑定前一条链值与密文摘要，头部和目录也有认证。篡改密文、头部、过期时间、顺序或删除中间记录都拒绝。密钥版本进入每条记录，轮换后旧记录仍需相应旧版本；丢失密钥会拒绝读取，不降级到明文。

仅有数据库内的链仍可能被整体回滚到旧的有效前缀。因此 `AuditWitness` 必填：它在独立边界保存 catalog/stream/sequence/hash，CAS 只允许 genesis 或严格递增一条。`JdbcAuditWitness` 是可用的 JDBC 实现；生产须使用独立数据库/凭据/删除权限或其他可信见证服务，不能把两份表交给相同的任意删除者后声称抵御整体回滚。

写入顺序为主库提交 → 见证确认 → SDK 确认。主库提交后进程中断或见证回执丢失，会留下待见证的已认证记录；新接收器校验链和现有检查点后补齐见证。相同 eventId 重试必须匹配原内容，不会追加第二条或重放模型/工具回调。已有流的见证缺失、超前或不匹配时拒绝，不能静默重建。

每次追加前先确认既有头部，正常协议最多只有一条待见证记录。差距超过一条会拒绝，不能把见证库回退到旧备份解释成任意长度的待重放队列。这也限制单次恢复需要执行的外部见证推进次数。

多实例通过数据库流行锁协调；尚无流行时锁住目录、重读最新头部后再初始化，覆盖并发首次创建的旧“缺失”查询。整个过程只重试纯审计元数据，不重试业务操作。

主库、见证和密钥若被同一攻击者一起控制，或者可信发布者主动造假，本阶段不能提供独立真实性证明。JDBC 见证不是 WORM 产品认证，也没有部署 Trillian/immudb 服务。实际验证数据库为 H2，其他方言、HA/灾备和 KMS/HSM 仍待验收。

密钥提供者与自定义见证适配器也是可信宿主组件，必须配置有界连接与调用期限。库中的 statement timeout 不代表任意 Java 插件已经具备端到端终止证明。

## 读取与访问审计

```java
var rows = vault.readMetadata(authenticatedReviewer, 0, 20);
var selected = rows.stream().filter(JdbcAuditVault.RecordView::bodyAvailable).findFirst();
if (selected.isPresent()) {
    Object body = vault.readBody(authenticatedBodyReviewer, selected.get().sequence());
}
var proof = vault.verify(authenticatedReviewer, externallySavedCheckpoint);
```

| 权限 | 能力 |
| --- | --- |
| governance:audit:read | 读取本租户、本主体的未过期元数据，或校验检查点 |
| governance:audit:body | 同时具有 read 时，读取本主体未过期采集正文 |
| governance:audit:purge | 清理本主体已经到期的密文，不允许提前清理 |

查询方法从认证 actor 计算主体流，不接收可更换的“目标租户”或“目标用户”参数。猜测序号、trace 或 eventId 不会获得另一主体的数据。分页最多 100 条；元数据响应不夹带正文。

长分页应先用 `verify(reviewer, null).sequence()` 固定读取上界，再按 after 游标读取到该序号；过期记录可以形成空缺。读取自身也会追加 ACCESS 事件，不能把这个持续追加的流当成静态列表，或仅靠“空页”判断整次扫描结束。

完整记录/正文读取先校验链，随后持久保存 ACCESS 事件并确认见证，成功后才交付数据。见证或访问审计失败，即使已经在服务内部解密，也拒绝向调用者返回正文。到期/不存在的正文返回相同的不可用原因；授权失败先于记录查询。只校验不含正文的检查点不另增 ACCESS 条目，允许在满容量时进行完整性诊断。

`TrustedContext` 不是 token 验证器，API 不能直接作为匿名 Web 端点暴露。跨主体运维授权、访问者真实身份、职责分离及生产认证仍需管理面接入；当前没有通配租户管理员查询接口。

## 到期与清理

到期先拒读，宿主再按运维周期调用：

```java
int rowsPurged = vault.purgeExpired(authenticatedRetentionOperator);
```

正文与完整记录按不同期限清除密文，并留下 RETENTION 事件。链的非正文头部、摘要、opaque eventId、类型与时间继续保留，以便证明原有顺序和检查点；这不是删除全部相关元数据。保留期限被签名绑定，攻击者不能改日期把删除伪装成正常过期。

摘要、标识符和时间仍可能被关联，不能把“没有正文”或“只有哈希”当作匿名化证明。

授权访问和清理会追加可信观察时间；进程恢复或正常时钟回退不会让已到期内容重新可读。过期 eventId 重试也不恢复已清理正文。旧密文已经发生损坏时不会自动清理来掩盖异常。

当前没有后台自动清理器、完整头部归档/删除协议、备份销毁或密码学擦除保证。旧版本密钥包仍包含加密键和认证键，保留链验证还需要旧认证键；独立删除加密键、重封装和签名迁移尚未实现。完整流达到条目上限后需要可信迁移，不能通过清理密文重置证明链。

## 验证与剩余工作

```bash
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 \
  -pl agt-langchain4j,agt-observability -am spotless:apply verify \
  -Dtest=AuditCaptureTest,JdbcAuditVaultTest,AuditRuntimeTest,AuditProcessTest,AuditCryptoTest,AuditAdapterTest,AgentTracerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

测试使用合成密钥/凭据和临时 H2/回环 HTTP 服务，不读取生产密钥。独立两个 SDK JVM 共享加密链与独立见证数据库，并在存储重启后继续校验；另在预执行审计已经提交但尚未见证时强制终止工作 JVM，确认业务 HTTP 请求为 0，新 JVM 补齐记录后才正常执行。

还覆盖数据库尾部恢复到真实旧头部、密文/列/日期篡改、见证与回执丢失、并发首建的确定性竞态、读取权限、时钟回退、密文清理、容量、密钥轮换、自定义脱敏字段及原生模型/同步异步工具。实际结果见[结构化验收记录](evidence/audit-data-governance.json)。

生产密钥/身份与外部见证、其他数据库/HA、备份和完整归档、教学 TraceStore 的安全迁移，以及生产审批/供应链项目仍未完成。本阶段不代表整个安全补齐目标完成。
