# 工具清单审核与版本绑定

核心 `io.agt.core.tools` 提供可选的工具版本门禁：宿主固定审核过的工具描述、参数 schema、制品摘要与权限；登记时核对实际提供的字节和描述；调用与审批绑定同一摘要。LangChain4j 静态工具、动态 ToolProvider 和模型默认参数在进入模型前检查，防止一个同名工具换上未审核的描述。

这是本机审核清单与执行绑定基础。登记的制品快照不会自动加载或执行，也不证明任意 delegate 来自该制品；可信宿主工厂必须从已验证快照装配实际执行器。客户端核对文件摘要也不证明远端 MCP 服务正在运行相同代码。发布来源签名验证与远端部署证明尚未接入；持久发布审核与跨节点撤销需显式配置下文的 JDBC 目录，不能把本门禁称为完整供应链认证或代码沙箱。

## 现成方案检查

| 方案 | 能力、成本与本次取舍 |
| --- | --- |
| [Sigstore Java](https://github.com/sigstore/sigstore-java) / [Java API](https://docs.sigstore.dev/language_clients/java/) | Apache-2.0，官方库要求 Java 11，覆盖签名及验证 API，与当前 Java 17 栈兼容。它能验证制品与签名材料，还需由宿主配置受信签发者/发布身份、审核工具描述并绑定运行时。本阶段没有安装或验证 KeylessVerifier，不声称已完成 Sigstore 集成；保留为后续制品发布验证方案 |
| [SLSA 制品验证要求](https://slsa.dev/spec/v1.2/verifying-artifacts) | 强调将来源声明、制品和消费者预期匹配；属于验证规范，不能代替本 SDK 的会话、工具审批和撤销机制。用于划分发布证明与运行时清单的责任，当前没有宣称达到某一 SLSA 等级 |
| [MCP 安全最佳实践](https://modelcontextprotocol.io/docs/draft/tutorials/security/security_best_practices) | 提供令牌受众、代理与安全边界要求，禁止把收到的访问令牌直接透传给下游。它不提供 Java 工具清单的执行绑定实现；凭据代理仍是下一阶段工作，当前未通过新门禁自动实现 OAuth 令牌交换 |
| 现有 LangChain4j 1.20.0 | 复用实际 `ToolSpecification.toJson()/fromJson()`，覆盖 name、description、parameters、metadata、strict 等 SDK 描述；复用原生 ToolProvider、模型包装及异步生命周期。已通过实际 AiServices 装配测试，不为描述清单新增解析框架 |

需要自行实现的部分是固定清单、可信登记能力、模型前核对和核心派发重验；密码摘要仍使用既有 JCA 封装。没有自动下载插件、访问清单内 URL 或执行安装脚本。

## 固定审核与实际登记

`ToolDescriptor(name, provider, version, artifactDigest, specification, permissions)` 由可信宿主审核配置提供。`artifactDigest` 必须是固定的 `sha256:` 摘要；specification 是完整 SDK JSON 投影，不能只审核工具名或版本号。provider 是审核配置中的来源标识，不是已经认证的发布者证书；宿主仍须固定实际 Provider/transport 来源，不能用远端自报标识替换连接身份。

```java
// 来自受控发布/审核配置，不能从当前未信任的 Provider 描述直接生成批准清单。
ToolDescriptor reviewed = loadReviewedDescriptor();
try (var catalog = new ReviewedToolCatalog(List.of(reviewed), 4 * 1024 * 1024)) {
    ToolSpecification discovered = fetchToolSpecification();
    byte[] stagedArtifact = readBoundedStagedArtifact();
    ToolLease lease = catalog.register(reviewed.name(), stagedArtifact,
            JsonValues.parseObject(discovered.toJson()));

    // 宿主工厂必须消费这份快照；不能随后重新打开一个可能已经变化的原文件。
    ToolExecutor executor = trustedFactory.apply(lease.artifactSnapshot());
    var policy = StructuredPolicy.builder("reviewed-tools-v1")
            .tool(reviewed.name(), new ToolRule(Set.of("read"),
                    reviewedParameterRule, true, null, lease.identity()))
            .build();
    try (var control = AgentControl.builder(policy).audit(hostAuditSink)
            .approvalResolver(hostApprovalResolver)
            .reviewedTools(List.of(lease)).build()) {
        var assistant = new LangChain4jGovernance(control).aiServices(Assistant.class)
                .chatModel(hostModel).tools(Map.of(discovered, executor)).build();
        // 在已绑定的宿主会话内调用；长任务使用已认证身份 permit。
    }
}
```

片段中的工厂、发布审核配置、受限暂存读取和 Provider 获取均由可信宿主提供，不是本 SDK 自动安装器。身份会话与持久审批分别见[身份指南](authenticated-identity.md)及[审批指南](durable-approvals.md)。实际测试已覆盖 IdentitySession → 加密审批中的版本摘要 → 真实 HTTP 工具执行。

审核清单不能通过“先读取当前未知字节并计算摘要，再立即批准相同摘要”的方式自动生成。来源签名/构建来源和人工发布审核应先在宿主发布流程完成，再把固定预期交给运行时。清单不做 JSON Schema 与 ParameterRule 的语义等价证明；两者都需来自受控配置，实际参数仍经过核心权限和闭合参数校验。

## 调用、审批与披露

`ToolRule` 新增可选第五个参数 `binding`，其值为 `ToolDescriptor.identity()`；原三/四参数构造器兼容，空绑定不改变旧签名策略摘要。

`AgentControl.Builder.reviewedTools(...)` 固定借用的本机租约集合。结构化策略要求版本但缺少对应租约时，运行时构造即拒绝。提供了审核租约集合后，未登记的额外工具也不能通过模型描述入口；不是仅校验列表中恰好命中的名字。运行时和 descriptor 同时检查权限。

本机核心调用使用：

```java
session.reviewedToolOperation(lease, parameters,
        hostPrepare, hostExecute, hostProject, hostRestore);
session.reviewedToolOperationAsync(lease, parameters,
        hostPrepare, hostAsyncExecute, hostProject, hostRestore);
```

普通 `session.operation(true, name, ...)` 没有可信租约，不能执行要求版本绑定的工具。模型 JSON 内的 `toolBinding`、artifactDigest 或 version 字段也不能创建租约。其他 catalog 即使含有相同 descriptor，也不能替换此运行时已经固定的租约对象。

绑定摘要进入 `PolicyRequest.toolBinding` 和审批动作摘要，`withTarget` 保留它；持久审批向人工展示同一值。核心准入、准备之后的实际派发前和结果交付都检查租约；原生工具回调前也检查，不能先通过 raw-result 回调泄露已被撤销工具的结果。工具审计 warnings 包含 `tool_binding:sha256:...`，不把制品字节加入模型参数或审计正文。

LangChain4j 的受审工具描述经 JSON 往返生成独立快照，宿主原 Provider 对象不作为可随意变化的模型描述。动态 Provider 每次提供的描述都与固定清单匹配；直接 ChatModel 请求与 delegate 默认工具参数在合并后检查，模型实际派发前再查。发现篡改时，该模型调用尚未发生。

新边界要求 ENFORCE，EVALUATE_ONLY 不能跳过版本校验。既有未配置版本绑定的运行时保留原工具模式；它们不会因新增类自动得到供应链防护。默认业务错误处理器也不能把 `GovernanceException` 恢复为一次成功执行。

## 撤销、资源与生命周期

`catalog.revoke(name)` 在当前 catalog 中不可逆；不能再次登记相同名字来重置撤销。`catalog.close()` 使其所有租约失效。关闭控制器不负责关闭宿主共享 catalog，生命周期由宿主显式管理。

| 限制 | 行为 |
| --- | --- |
| 工具数量 | 1–128，不允许重复名字 |
| specification | 完整 JSON 投影最多 64 KiB，沿用核心 JSON 层级/节点限制 |
| artifact 总预算 | 构造器显式指定 1–16777216 字节；同时限制单次输入及已保留快照总量 |
| 登记复用 | 同名工具重新核对实际字节/描述后复用原租约，不不断保留新快照 |
| 可变输入 | 制品输入复制后再校验，工厂取得独立快照；清单 JSON 与权限集合不可变 |

这里的字节预算只覆盖 catalog 持有的数据，不约束宿主工厂额外复制、MCP 客户端此前的网络解析或插件运行内存。供应链门禁没有安装进程级 CPU/网络/文件系统隔离。

撤销不能回滚已经发生的副作用，也不保证终止任意 Java 代码；派发与外部系统之间不是一个分布式原子事务。已开始的执行仍需外部终止和副作用核对机制。默认撤销保存在本机 catalog；多节点持久撤销需显式接入下文的 JDBC 发布目录。

## 与可靠副作用组合

受审版本可直接用于 `session.effectOperation(...)`、`effectOperationAsync(...)`、原生 `guardEffectTool(...)` 和 Builder 的 `effectTool(...)`。宿主先配置同一 `ToolRule` 的 `effect` 与 `binding`，并通过 `reviewedTools(...)` 固定实际租约。副作用入口从该固定登记选择租约，不从模型参数构造身份；普通工具入口仍不能绕过副作用日志。实际后端仍必须由可信宿主从审核制品装配。

```java
var rule = new ToolRule(Set.of("pay"), closedArguments, true,
        new EffectRule("orders", "v1", EffectLedger.Phase.FORWARD, ledger.identity()),
        lease.identity());
// 在包含该 rule、reviewedTools 和审批配置的控制器内：
var tool = governance.guardEffectTool("pay", ledger, session -> hostIntent, hostBackend);
```

`closedArguments`、`hostIntent` 与 `hostBackend` 是宿主已绑定配置，具体后端、意图及补偿要求见[可靠副作用指南](reliable-effects.md)。本组合沿用原有配置，不增加新的预算、存储或凭据字段。

审批请求同时携带版本摘要和业务目标；准备后、实际后端派发前、缓存恢复后及原生回调前后重验租约。撤销后不交付恢复结果，但已经发生的业务提交仍保留 COMMITTED 凭据，不能因交付失败而重做。补偿使用自己的受审版本、权限与独立审批，绑定精确的正向成功凭据。

受审版本加入持久请求指纹；业务意图键和后端幂等键保持稳定。因此，同意图换成新版本，或在未受审与受审入口之间切换，会以 `effect_request_mismatch` 拒绝执行或恢复，不会自动迁移已存在的日志。新任务或模型调用 ID 不能重置意图。版本升级需要宿主核对旧业务状态，不能通过换意图 ID 绕过此约束。未配置版本的旧工具保持原请求摘要。

本阶段验证本机版本门禁与 JDBC 副作用日志的组合，包含同步/异步调用、实际 AI Service 循环、审批、回调中撤销和补偿。签名委托的版本提交也已接通，见下节。默认清单撤销仍局限本机；持久发布目录见下节，凭据代理仍未接通。见[组合验收记录](evidence/reviewed-effects-governance.json)。

## 远端签名版本提交

发送端与接收端分别登记可信宿主审核的同一 `ToolDescriptor`；接收端仍需自己核对实际制品与完整工具描述。`DelegationAuthority.register` 从固定策略导出 `toolBindings`（工具名到版本摘要的映射），映射进入授权文档摘要和签名，并由子授权原样继承。接收端必须同时匹配完整策略摘要及版本映射；同名不同版本、删除绑定或缺少本机租约都不能启动有效执行。该映射最多 128 项，未使用版本的旧授权不增加空字段，保留旧文档摘要。

核心准备阶段将不可伪造租约的摘要和实际副作用目标带入提交。在线协议 `reviewed_commit` 只传工具名、绑定摘要、有效动作指纹和必要的数据流投影，不传制品字节、完整参数、业务意图正文或结果。权威端核对签名映射、租约/会话与祖先范围，再原子扣减共享配额；重复动作只有指纹完全相同才复用，不能改名、换摘要或重复扣减。JDBC 恢复保留同一记录。

只要授权中包含受审工具，所有工具提交都经过新协议，包括同策略中显式未受审的工具（绑定为 null，仍受工具名、范围和完整策略检查）。旧的 `commit` 和工具 `guarded_commit` 对这些授权拒绝准入，不能丢弃版本后绕过；数据流检查与配额扣减仍在同一权威事务内执行。旧端点或自定义客户端未实现新接口时抛出 `delegation_tool_binding_unsupported`，必须协调升级，不提供自动降级。

正常授权、审批、同步/异步原生副作用工具以及跨 JVM/JDBC 已组合验证。根任务撤销、单次授权领取、未知业务结果和独立补偿继续沿用原有机制。每个节点的本机 catalog 撤销仍只影响该节点；共享目录撤销见下节。发布者签名验证、远端二进制部署证明或凭据代理仍未接通。不能将匹配的版本摘要当作不可信接收 JVM 的远端证明。

验收范围见[远端版本证据](evidence/remote-reviewed-tools-governance.json)。

## 验收与未完成边界

新增 8 项核心测试与 7 项原生集成测试覆盖制品和描述替换、不可变快照、总量预算、未绑定普通调用、缺失注册、审批摘要、准备/审批期间撤销、权限、其他 catalog 替换、观察模式、异步晚到结果、动态/静态/默认模型工具描述及模型前拒绝。额外覆盖 parameters、metadata、strict 的改动，以及执行前/后回调中的撤销。另补身份与加密审批到真实 HTTP 后端的组合测试。详见[结构化证据](evidence/reviewed-tool-governance.json)。

当前没有验证任意 Java delegate 的二进制来源，也没有接入 Sigstore/SLSA 证明验证、远端 MCP 实际部署证明、依赖/SBOM 审核或恶意插件隔离。宿主不能把仅有清单匹配的任意插件当作不可信代码放进主 JVM。

已审核版本与 `EffectToolRegistration` 的本机装配和跨进程签名 SessionAuthority 的版本化提交均已接通；缺少新协议的远端仍拒绝执行，不会退回普通未校验调用。持久审核/发布目录与多节点撤销已通过下节可选模块接入；凭据代理仍按总计划继续实施，不能以本阶段测试通过声称整个供应链与凭据目标完成。


## 持久审核目录

可选 `ToolDescriptor.reviewAuthority` 将发布目录身份加入版本摘要，并通过 `ReviewedToolCatalog` 的三参数构造器固定对应 `ToolReviewAuthority`。`agt-tools-jdbc` 已提供同租户的持久提案、独立人工审核、不可逆撤销、管理访问审计与跨节点在线检查；使用它的节点不再只依赖本机撤销集合。不配置此字段的旧清单仍保持本机语义。装配、容量、确认与信任边界见[工具发布目录](durable-tool-releases.md)。发布者来源、远端二进制证明、归档和凭据代理仍未完成。
