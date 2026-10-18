# 跨进程委托与在线撤销

`agt-delegation` 是显式启用的实验模块，依赖 `agt-core` 与 Apache-2.0 的 [Nimbus JOSE+JWT](https://connect2id.com/products/nimbus-jose-jwt/download) 10.10。复用成熟库处理 RSA 签名，在线授权状态、治理会话和共享配额仍由本项目接入。设计参考 [RFC 8725](https://www.rfc-editor.org/rfc/rfc8725.html) 的算法、类型与受众约束；没有实现通用 OAuth Token Exchange 服务，也不声称完整符合该协议。

本模块解决“可信宿主把一个有范围的任务交给另一个治理进程”的授权关联。Trace ID 仍只用于追踪。接收进程必须是受信任、实际执行 SDK 治理的宿主；本模块不会让绕过 SDK 的任意代码自动获得隔离，也不替代工具端自己的身份认证与授权。

## 组件与信任边界

| 组件 | 职责 |
| --- | --- |
| `DelegationAuthority` | 在唯一授权服务中注册源会话、持有私钥、签发更窄的子任务凭据、监听源会话终止 |
| `GrantRegistry` | 保存原始授权文档、祖先链、单次接收状态、租约与根任务共享配额 |
| `DelegationVerifier` | 固定可信公钥/签发方/受众，核对签名、宿主认证身份、文档摘要与在线状态 |
| `SessionAuthority` | 将验证结果绑定到实际 `HostSession`，在检查、提交及交付阶段执行治理 |
| `GrantStatusService` | 框架无关的接收协议处理器，接收宿主已认证身份并核对租约归属，不提供根管理权限 |
| `HttpGrantStatusClient` | 对固定 HTTPS 端点执行接收方在线操作；不包含私钥、签发或根任务撤销权限 |

`AuthenticatedAgent` 必须由宿主根据已认证的 mTLS、IAM 或其他身份映射构造。不能从提示词、Trace 头、请求正文中的 `agentId` 或 Token 本身推断调用者已认证。签名凭据只能说明签发方授权了谁，不能代替调用者身份认证。

`InMemoryGrantRegistry` 代表一个授权服务的权威状态，供多个接收进程共同访问。不能在每个接收端分别创建一份注册表。进程重启丢失注册状态后，旧凭据默认拒绝。需要持久状态时可显式换用实验级 [JdbcGrantRegistry](durable-authority.md)；生产集群一致性与高可用仍待验证。

## 创建和接收

发送端需要有 `DelegationScope` 的 ENFORCE 会话，基础策略必须是可导出的 `StructuredPolicy`：

```java
// senderControl、identity、taskLimits、execution、scope 均来自宿主可信配置。
try (var source = senderControl.openTaskSession(identity, taskLimits, execution, scope);
     var authority = new DelegationAuthority(
             "urn:example:authority", "key-1", signingKey, registry, Clock.systemUTC());
     var root = authority.register(source)) {
    var token = authority.issue(root.lease(), "reader", "reader-service",
            Set.of("read"), narrowerScope, Duration.ofSeconds(30));
    // 经已认证的宿主通道交给 reader；source/root 在远端工作结束前保持打开。
}
```

接收端使用预配置的公钥集合、固定签发方和受众。`statusClient` 可以是同一授权服务的受认证 HTTP 适配：

```java
var statusClient = new HttpGrantStatusClient(
        URI.create("https://authority.example/grants"), hostCredentialSupplier);
try (var verifier = new DelegationVerifier(
             "urn:example:authority", "reader-service",
             Map.of("key-1", verificationKey), statusClient, Clock.systemUTC());
     var permit = verifier.accept(token, authenticatedAgent);
     var session = receiverControl.openAuthorizedSession(permit, receiverLimits, execution);
     var binding = session.bind()) {
    // 调用该会话绑定的受控模型、工具或数据适配器。
}
```

发送端和接收端必须使用相同的结构化策略文档，接收端必须为 ENFORCE，运行时单会话调用/数据上限不能宽于发送端。策略摘要保留内容替换的先后顺序。接收端自己的策略继续执行，此外原策略中 `equalsContext` 仍按发送端原始身份绑定检查，不能因更换 Agent ID 扩权。因此绑定原 Agent ID 的规则可能使某些委托无法执行，应由宿主设计合适的资源约束，不能通过丢弃原规则解决。

子权限只能缩小，租户和用户主体不能改变，任务标签保持一致，工具、数据资源和操作不能新增。每层参数规则保留原签发身份并执行交集检查。凭据还绑定 taskId、祖先链、接收服务和剩余深度，期限不超过父级及十分钟。

## 防重放、配额和停止语义

- 固定 RS256、明确 `typ` 与本地可信 `kid`；不接受令牌指定的远端密钥、其他算法、额外头字段或重复 JSON 字段。RSA 密钥要求 2048–8192 位。
- 同一凭据只能被一个接收方原子认领，随后只能绑定一个实际会话。未绑定前不能继续派生授权，未绑定租约两秒后关闭。改变接收方身份或服务受众不会获得新的执行机会。
- 注册源会话时，根任务剩余模型/工具/数据调用次数及活跃会话名额单向交给权威注册表。已有本机会话保守占用原有名额；此后本机不能再开启新操作或新子会话。导出失败或结果未知时不自动恢复额度，避免双重消费。
- 不同接收进程共享同一份调用次数和剩余活跃名额。每个远端接收会话占一个名额，结束或租约失效后释放名额，已执行调用次数不退回。所有远端受控动作提交前由注册表原子扣减。
- 这一版不允许远端接收会话直接通过 `openDelegatedSession` 创建本机子会话，返回 `delegation_remote_child_requires_grant`。继续派生应由宿主将已绑定 `permit.lease()` 交给同一授权服务签发子凭据，并通过 verifier 创建新接收会话，统一计入深度、名额与祖先撤销链。租约不暴露给模型。
- 提交记录绑定授权、会话、动作 ID、动作类型和有效参数摘要；同一动作重试不能通过换类型或参数重复使用已批准记录。这是配额准入幂等，不代表外部业务副作用恰好执行一次。
- 源会话终止、显式撤销、祖先结束、期限到达或授权服务不可达后，拒绝新操作及结果交付；接收端后台监测同时终止已追踪的等待调用。已发生副作用不能回滚，也没有任意 Java 代码/外部进程的强制停止保证。

源端与接收端每 250ms 监测/续租，在线租约两秒，过期租约不能重新续活。HTTP 连接超时 500ms，完整请求与正文期限一秒；实际停止还受网络、线程调度及宿主终止回调影响，不提供硬实时承诺。验证器同时接收的授权上限为 16，注册表最多保存 10000 条未过期记录，单根任务最多保存 100000 条动作提交记录，达到上限拒绝。

根任务配置 `DataFlowPolicy` 后，接收方自动绑定同一权威 DataFlowLedger，签名文档同时绑定数据流配置；已有敏感等级、来源和前置步骤不会因换进程重置。宿主可配置 agt-provenance 的 LabelStore 以保留跨任务实例标签，见[跨服务数据流与持久来源](persistent-data-flow.md)。`UsageLimits` 支持下述权威账本交接。

## 跨进程 Token 与费用账本

源 `TaskLimits` 配置 `UsageLimits` 后，`authority.register(source)` 会同时交接剩余调用权和用量账本。签名授权绑定价格表、模型及输出上限。既有 settled 消耗完整保留，接收端不会获得一个重置后的余额；这里的交接是从本机账本向单一授权服务的转移，不是离线复制额度。

交接要求本机没有未决模型预占：有准备中、已发送或用量未知的预占时返回 `model_usage_inflight_export_denied`，原任务继续保有额度。准备中的调用可先取消释放，或等待实际用量结算后重试；已经未知的费用不能通过导出重新清零。交接成功后，原本机任务禁止新操作、新子会话及新用量预占。

接收方无需在 `receiverLimits` 中重新声明 UsageLimits，`permit` 自动绑定远端 `UsageLedger`。接收端再配置独立 UsageLimits 会以 `model_usage_local_override_denied` 拒绝，防止本机额度替换权威额度。模型适配器仍必须配置可信 `ModelUsageMeter`、实际 Provider 输出限制并关闭内部重试，具体契约见[用量预算指南](model-usage-budget.md)。没有计量凭据的模型调用会被拒绝。

一次调用的过程为：远端原子预占 → 单次 dispatch 确认 → 发出实际 Provider 请求 → 按响应模型与可信用量结算。不同 JVM 的预占共用根账本，余额不足时在发送前拒绝。幂等记录绑定授权与 reservationId：相同参数的 reserve/dispatch/settle 重试不重复扣款；同一 ID 不能用于另一授权、不同模型、不同预占或不同结算结果。

- 未 dispatch 的预占在取消、接收会话结束或失去租约时释放。
- dispatch 后取消、崩溃、响应未知、模型不匹配或用量非法时保留预占，增加 uncertain，并冻结整棵根任务。其他接收者不能继续消耗剩余余额。
- 父级租约结束时，其所有后代的预占也会清理。即使孙 Agent 自己仍在续租，也不能维持已失效祖先下的待结算调用。
- 实际用量超过上界时记录实际费用与 Token，冻结根任务；无法撤销已经发生的 Provider 消耗。
- complete 会在权威端批量结束所属子树的预占。本机后续逐项清理不逐笔等待远端超时；若 complete 不可达，权威端依据租约到期处理，不假定退款成功。

`receiverSession.usageSnapshot()` 返回权威统计；发送端使用 `root.usageSnapshot()`，而不是已冻结的原账本。持久化交接补充后，`source.usageSnapshot()` 也绑定权威账本，不再返回本机旧消费；数据流快照和父任务输出校验同时使用共享状态。持有正确私有租约和会话的宿主可在凭据期限内读取结束后的统计及 fault，用于核对未知用量；这个只读查询不能恢复执行。

默认 InMemoryGrantRegistry 位于单个授权进程内存中，重启后旧授权拒绝；可选 [JDBC 后端](durable-authority.md)保存授权与费用检查点，但不自动恢复失效租约，不提供自动核账或不确定消费解冻。每根账本最多保存 100000 条单次预占记录，达到上限拒绝；已结束记录保留至任务授权到期，避免重复结算。价格为宿主配置，不会从响应中接受新价格。

## HTTP 接口契约

本模块交付客户端、SPI 和 `GrantStatusService` 协议处理器，生产服务端仍需宿主接入自己的身份系统、请求限制、审计与部署。测试中的 `TestGrantEndpoint` 只用于本机随机凭据映射，不是可直接上线的管理服务。

客户端固定端点，不接受用户提供的任意 URL，禁止 URL 凭据、查询参数与 fragment，不跟随重定向。默认只允许 HTTPS 并使用系统 TLS 信任配置，`loopbackForTesting` 仅允许字面回环地址的 HTTP。Bearer 凭据由宿主供应，不写入异常或正文。响应正文最多 1 MiB，非法 UTF-8、结构错误、超时和无法确定的返回均拒绝。

全部接收操作为 POST JSON，公共字段为 `op` 和 `lease: {grantId, secret}`：

| op | 附加字段 | 成功响应 |
| --- | --- | --- |
| claim | grantId、digest、agent、audience | `{"document":"GrantCodec.encode 的授权文档"}` |
| bind | sessionId | `{"ok":true}` |
| check | sessionId | `{"ok":true}` |
| commit | sessionId、actionId、kind、digest | `{"ok":true}` |
| complete | sessionId，未绑定清理时可为 null | `{"ok":true}` |
| guarded_commit | sessionId、actionId、point（枚举名）、tool、projection、digest | `{"ok":true}` |
| flow_validate | sessionId、point、tool、projection | `{"ok":true}` |
| flow_complete | sessionId、actionId、tool | `{"ok":true}` |
| flow_snapshot | sessionId | `{"snapshot":"GrantCodec.encodeFlowSnapshot 的 JSON"}` |
| usage_reserve | sessionId、reservationId、model、input、output；计数为预占上界 | `{"ok":true}` |
| usage_dispatch | sessionId、reservationId | `{"ok":true}` |
| usage_settle | sessionId、reservationId、model、input、output、total；来自可信实际用量，缺失可为 null | `{"ok":true}` |
| usage_close | sessionId、reservationId | `{"ok":true}` |
| usage_snapshot | sessionId | `{"snapshot":"GrantCodec.encodeUsageSnapshot 的 JSON"}` |

宿主服务端先认证传输身份，将其与授权的接收 Agent 关联，再调用 `service.handle(authenticatedAgent, boundedJsonBody)`，把返回 status/body 写入 HTTP 响应。处理器限制操作字段，claim 的 agent 必须与宿主身份一致，后续操作通过 `registry.authorizeCaller` 核对租约所属调用方；不能从正文构造 authenticatedAgent。返回 4xx 和单独 `code` 字段表示领域拒绝，5xx、重定向及非预期正文统一视为不可用。签发、根注册、根续租、撤销接口不应向仅有接收权限的客户端开放。

授权 Token、租约 secret、私钥和宿主认证凭据不进入提示词、Trace、URL 或公开日志。授权文档也可能含敏感资源范围，状态服务与宿主仍需按租户控制访问并加密传输。

## 验证与后续

测试覆盖独立 JVM 经 HTTP 的父级撤销和服务断联、慢速/超大/异常正文、重定向拒绝、并发单次认领、共享调用次数/活跃名额、策略不匹配、观察模式、原始身份绑定、算法/类型/密钥混淆、签名篡改、重复字段、过期及注册表重启默认拒绝。进程测试只输出执行状态和副作用计数，不输出凭据。

后续已补实验级 [JDBC 检查点与多 JVM 共享账本](durable-authority.md)，验证持久消费、撤销、到期与未知预占；当前没有验证生产 mTLS/IAM、真实网络分区、高可用注册表、自动恢复执行、文档片段级来源或所有外部任务的取消确认。这些仍属于[安全能力补齐计划](security-hardening-plan.md)的未完成内容。结构化结果见[跨进程委托证据](evidence/remote-delegation-governance.json)。

跨进程预算的最新验证见[分布式用量证据](evidence/distributed-usage-governance.json)：两个 JVM 共用同一余额，第三次超额请求未到达模拟 Provider；强制终止已发送请求的进程后，预占保留并阻断另一进程。最终全工程回归 588 项，587 通过、1 项可选跳过。

跨服务数据流补充：guarded_commit 将共享调用配额与来源登记放在同一权威提交中，带数据流的授权不能使用旧 commit 绕过。信息流 RPC 只包含目的地标识、实例/操作及摘要，数据 value 为 [null]，不发送模型或数据正文。前置步骤只在实际工具成功、后置检查和审计确认后登记。持久目录中的 CLASSIFICATION 与 WRITE 事件分别记录分类者和写入者，不把读取误报为数据生产。验收见[持久来源证据](evidence/persistent-data-flow-governance.json)。

后续已接入[外部执行的权威登记和持久回执](external-cancellation.md)：实际派发前确认共享名额，节点失联保留未知，旧绑定可补报终止而不能恢复执行；已结束的源端历史交接时保留。
