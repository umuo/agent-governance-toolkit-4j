# 持久工具审核与目录撤销

可选 `agt-tools-jdbc` 将工具发布提案、人工审核、终态和历史保存在加密 JDBC 检查点中。多个 SDK 节点接入同一目录和独立见证后，已创建的租约也会在下一次检查中观察撤销；重建 Java 对象不会恢复被撤销的版本。

它是工具部署的审核准入，不是制品仓库、发布者签名验证、远端二进制证明或任意插件沙箱。宿主仍须从已核对的制品快照装配执行器，并正确绑定真实工具描述、身份源、存储、密钥和见证。

## 需求与选型

本阶段要求：固定 provider/name/version 的不可变含义；发布者与人工审核人分离；查看与决定绑定；批准状态在登记、派发及交付前在线核对；未知、超时、损坏、旧快照与缺失见证拒绝；跨实例和独立 JVM 共享撤销。管理端还应能在访问审计后读取目录与历史，核对未确认的管理操作。

| 可复用能力 | 取舍 |
| --- | --- |
| [TUF 规范](https://theupdateframework.github.io/specification/v1.0.36/)与 [Python 参考实现](https://github.com/theupdateframework/python-tuf) | 参考实现采用 MIT/Apache-2.0 双许可，关注回滚、冻结及文件完整性；可用于未来制品分发验证，但 Python 客户端不能直接替代 Java 会话门禁。本轮没有部署 TUF 客户端或仓库，不宣称实现 TUF 合规 |
| [Harbor 签名管理](https://goharbor.io/docs/main/working-with-projects/working-with-images/sign-images/) | Apache-2.0 制品服务，可保存关联签名并复制制品；需要独立运维，仍须接入 SDK 的执行与交付门禁。本轮不为元数据审核安装完整制品服务 |
| 现有 JDBC、Nimbus 身份、`StorageCrypto`、`AuditWitness` 与结构化审计 | 直接复用已采用的事务、验签、AES-GCM/HMAC 和独立见证接口；新增的目录状态机只负责发布审核和在线资格，未另写密码算法或身份协议 |

没有调用安装脚本、自动下载制品或自动执行目录内容。HTTP 管理面、企业登录与 CSRF 仍由后续工作接入；当前 API 接收的是可信宿主验证后的 `AuthenticatedActor`，不能接受请求正文自报的角色或审批人。

## 配置与使用

```xml
<dependency>
  <groupId>io.agt</groupId>
  <artifactId>agt-tools-jdbc</artifactId>
  <version>0.6.0-SNAPSHOT</version>
</dependency>
```

```java
var policy = new ToolReleasePolicy(
        "tenant-a", identityPolicy.identity(),
        Set.of("tools:publish"), Set.of("tools:review"), Set.of("tools:revoke"),
        16, 4, 262144, 5000);
try (var releases = new JdbcToolReleaseStore(
        primaryDataSource, "approved-tools", "independent-tool-witness",
        policy, storageKeys, independentWitness, Clock.systemUTC())) {
    releases.initializeSchema();
    var descriptor = new ToolDescriptor(
            "lookup", "reviewed-provider", "v1", reviewedArtifactDigest,
            reviewedSpecification, Set.of("read"), releases.identity());
    String binding = releases.submit(publisherActor, descriptor);

    // 在独立的人工审核流程中执行；reviewerActor 必须是另一主体且具有近期 MFA。
    var view = releases.review(reviewerActor, binding);
    releases.decide(reviewerActor, view, true);

    try (var local = new ReviewedToolCatalog(List.of(descriptor), 4 * 1024 * 1024, releases)) {
        var lease = local.register("lookup", actualArtifactBytes, actualSpecification);
        // 将 lease.identity() 写入 ToolRule.binding，并用 reviewedTools(List.of(lease)) 固定租约。
        // 核心、原生工具和签名委托继续沿现有受审工具路径接入。
    }
    releases.revoke(securityActor, binding);
}
```

示例中的身份、数据源、密钥和见证由宿主显式配置，不是默认放行实现。`independentWitness` 可复用 `JdbcAuditWitness`，必须使用独立数据库、凭据和管理边界；同一个管理员能同时回滚主库及见证时，本机制不能提供独立回滚证明。测试使用两份逻辑库，不代表生产独立管理域已经验收。

| 配置 | 语义与范围 |
| --- | --- |
| `tenantId` / `identityPolicyId` | 固定租户和验签 profile 摘要，无默认值；发布与管理身份必须精确匹配，运行时上下文必须同租户 |
| `publishers` / `reviewers` / `revokers` | 每类 1–16 个明确权限，要求全部具备；审核与管理读取另要求人工 MFA。撤销允许具备权限的可信自动化身份收窄权限 |
| `maxReleases` | 1–128，含 PENDING、APPROVED、REJECTED 和 REVOKED；不自动删除墓碑腾出名额 |
| `maxReviews` | 每版本最多 1–16 次持久查看能力；同一账号重新登录必须重新查看，不复用旧 token 的能力 |
| `maxStateBytes` | 加密前整个状态容量，上限 1048548 字节，下限为 `maxReleases * 4096 + 8192`；条数和字节限制同时生效 |
| `operationTimeoutMillis` | 单次目录操作 100–30000 毫秒，含排队；2 个工作线程、16 个排队名额，不创建无限线程 |
| `name` / `witnessCatalog` | 宿主固定存储名称和独立见证域；与策略一起进入目录身份，不是从连接地址自动认证出的服务器身份 |

所有配置无隐式租户、角色或存储回退。改变策略、名称或见证域会改变目录身份；不能直接用更宽松配置打开旧目录。密钥版本可在同一 `StorageKeys` 中轮换，读取仍需要保留旧版本；这不是已经接入生产 KMS、灾备或密钥擦除。

## 不可变版本与审核能力

`ToolDescriptor.reviewAuthority` 是可选目录摘要。配置后它进入 `ToolDescriptor.identity()`，再进入已有工具策略、审批和签名委托的绑定；缺少相同目录检查器时不能创建 catalog，接收端不能换成旧本机租约继续使用同一策略。未配置该字段的旧 descriptor 保留原 identity 计算，旧清单继续是本机模式，不会自动获得持久审核。

`submit` 创建 PENDING 提案。相同发布者重试相同提案可复用已有状态，但相同 provider/name/version 不能替换制品摘要、描述、权限或目录身份；REJECTED/REVOKED 不能重新提交为批准。新版本需要独立提案和审核。

`review` 先持久记录查看事件并完成见证，再返回 `ToolReleaseReview`。它绑定确切描述、人工主体、当前令牌和私有随机凭据；最多有效五分钟，且不超过该令牌剩余有效期。`decide` 在进入、数据库提交前和响应交付前重新验证在线状态、MFA、角色、原主体和凭据；等待期间令牌被撤销或 MFA 变陈旧时，不能提交批准。申请人不能自审，服务账号不能冒充人工；另一登录令牌也不能复用旧查看能力。决定不可相互覆盖；原决定的准确重试不重复推进目录，撤销后不能以旧批准恢复资格。

`revoke` 可终结待审、批准或拒绝版本，不回滚已发生的业务行为，不强制终止任意 Java 代码。每个接入节点都在实际执行与结果交付边界重新检查目录；管理操作与外部业务之间不是分布式原子事务，撤销不能追回已经派发的请求。

## 管理读取与访问审计

```java
var summaries = releases.list(reviewerActor, accessAuditSink);
var info = releases.inspect(reviewerActor, binding, accessAuditSink);
```

目录列表只包含绑定、名称、provider、版本和状态；`inspect` 返回完整描述、发布主体及有界事件历史，用于核对批准、拒绝、撤销和管理请求的未知结果。两者要求独立人工审核权限和在线身份检查，且在结构化访问审计成功后才交付。真实租户与主体取自认证身份；事件中只保存输入/输出摘要，默认不采集工具描述正文。生产可将 `accessAuditSink` 接入已配置的 `JdbcAuditVault`。审计不可用时不交付正文，读取结果也不能代替私有审核能力。

运行时资格检查是内部元数据读取，不为每个 Token/工具重复写入发布事件，避免读操作耗尽发布历史。业务调用仍执行宿主配置的原有治理审计。目录管理读取的访问记录由独立审计接收器负责保留与清理，不能用换目录删除发布墓碑。

## 持久确认、容量与异常

状态采用单目录行锁、版本化认证加密和有界哈希历史。持久 DTO 独立限制 64 层、65536 字符单串、128 字符数字和整个状态字节数，并拒绝重复字段、未知字段及尾随内容；不把多个版本挤进一次模型目标的 8192 节点预算。每个工具描述仍遵守原有 JSON 上限，数字精度保留，模型请求限制不变。每个新管理事件先提交主库，再确认独立见证；最多允许一条尚未确认的尾部，下一次操作必须先确认它。另一实例先推进时，从当前加密状态的历史验证原写入确为祖先，不能把陌生分支当成成功。该布局按单租户目录串行化写入，适合有界审核控制面，不是高吞吐制品数据库。

回退已见证历史的旧有效密文、整行删除、缺失/超前见证、状态不一致、未知密钥或字节超限都拒绝，不回退为空目录或本机清单。所有版本的事件数、查看数、状态转换与历史长度会在恢复时核对。普通管理变更预留每个容量名额 2048 字节给撤销；达到发布容量边界不会主动清除审核或撤销记录。

超时、调用取消或提交回执丢失，不证明数据库变更已经回滚。重开目录只补元数据见证，不重放业务；管理端可在授权访问审计后核对实际状态。迟到的内部检查不会在调用已取消或目录已关闭后向调用方放行。忽略中断的驱动/见证可能继续占用有界线程；宿主仍需给 DataSource 配置连接获取、连接池和网络期限。

## 验收范围

专项覆盖真实 JWT 与 MFA、跨租户、自审批及服务账号拒绝；审核票据与状态机；并发首次发布和竞争决定；关闭、取消、慢见证；有效旧密文、删除、损坏及未知密钥；独立见证确认故障；容量预留；密钥轮换；管理访问审计。真实独立 JVM 先建立租约和会话，另一节点撤销后，其下一次业务调用被拒绝，业务调用数为 0。另以文件库完全关闭、重开验证批准与撤销保留。

实际结果见[目录验收记录](evidence/durable-tool-releases-governance.json)。目前验证 H2 和测试身份源；未完成生产数据库/连接池、高可用、企业身份源、制品来源证明、MCP 部署证明、任意恶意插件隔离、审批工作台、归档和密钥销毁。目录 API 不是自动运行或安装插件的授权。
