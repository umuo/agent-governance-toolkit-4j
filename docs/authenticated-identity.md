# 已认证身份与在线撤销

可选模块 `agt-identity` 把可信身份源签发的访问令牌绑定到实际治理会话：验签后映射租户与应用权限，在会话准入、工具执行前、结果交付前及后台监测时重查在线状态。它是生产身份接入的实验基础，不包含企业 IdP 部署或登录 UI；持久人工审批需另配[审批模块](durable-approvals.md)。教学网页不会因引入该模块自动获得认证。

## 现成方案与取舍

| 候选 | 适配范围与接入成本 | 本阶段结果 |
| --- | --- | --- |
| [Nimbus JOSE + JWT](https://connect2id.com/products/nimbus-jose-jwt) | Apache-2.0，现有委托模块已经使用的 Java 签名验证组件；不负责应用权限映射、持续撤销和 SDK 会话生命周期 | 复用已有 10.10 依赖，由 Nimbus 验证 RS256 签名；增加固定访问令牌 profile 和会话绑定，并运行真实签名测试 |
| [Keycloak OIDC](https://www.keycloak.org/securing-apps/oidc-layers) | Apache-2.0 身份服务，提供签发、密钥、内省等端点；需要独立部署、配置 client、角色和应用声明映射 | 可作为宿主身份源，当前没有部署或完成兼容认证；不能将本阶段 profile 假定为其默认令牌格式 |
| [Spring Security JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) | Apache-2.0，适合宿主 Web 层认证；纯 Java SDK 无需为此强依赖 Spring；单次 HTTP 认证也不能代替长任务的持续检查 | 保留为宿主入口方案，不替换应用现有安全链；当前没有自动配置或认证过滤器 |
| [Flowable User Task](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs#user-task) | Apache-2.0，覆盖持久用户任务与完整工作流，需要流程引擎及其数据模型 | 后续长时审批可接入；当前没有引入引擎，也没有把身份校验等同于审批工作流完成 |

只自行实现本项目需要的身份声明映射、有限资源状态查询与 `SessionAuthority` 绑定，签名算法使用成熟组件。实际验证范围是本机生成 RSA 密钥、真实 HTTP 内省、核心工具调用和撤销竞态，没有连接外部身份服务。

## 固定访问令牌 profile

采用 [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068.html) 中区分访问令牌的类型标识，但本模块实现的是更严格的应用 profile，并非完整通用 OIDC 客户端。

- Header 必须恰为 `alg`、`typ`、`kid`；算法仅 RS256，类型为 `at+jwt` 或 `application/at+jwt`，密钥来自宿主固定的受信公钥表。拒绝 ID Token、未知 key、远端 key URL、扩展 header、重复 JSON 字段和非规范 base64url。不自动读取令牌中的 URL 或发现新签发者。
- 必须提供准确 `iss`、唯一预期 `aud`、白名单 `client_id`、`sub`、`jti`、整数秒 `iat/exp`；`nbf` 可选。禁止未来签发、未生效、过期和超出配置有效期的令牌；不配置时间宽限。
- 身份源须通过受控 mapper 提供 `agt_tenant`、`agt_roles`（角色列表）、`agt_actor_kind`（`human` 或 `service`）。未知角色拒绝；SDK 权限只由宿主审核的角色映射产生，忽略令牌自行携带的 `permissions`。
- SDK 主体为签发者与外部 subject 的命名空间摘要，不把可能包含邮箱或分隔符的 subject 直接当作 SDK 标识。Agent ID 是宿主实例标签，不能替代认证主体或审批人。
- `requireHuman()` 除在线有效外，要求 human 类型、近期 `auth_time`、审核过的 `acr` 及 `amr` 中明确的 `mfa`。它只验证可信签发者的声明，不证明某个人实际操作了页面；服务账号即使持有审批角色也不能通过此项检查。仅调用此方法也没有实现职责分离或一次性审批。

凭据包装类 `toString()` 不返回 token。`AccessToken.value()` 和 `VerifiedToken.credential()` 仅供可信宿主适配器，不能交给模型、审计正文或工具输出。主体摘要是可关联的假名标识，不是匿名化保证。

## 在线状态与资源限制

`TokenStatus` 必须显式配置，没有跳过撤销的默认值。`HttpTokenStatus` 向固定 HTTPS 端点发送表单内省请求和宿主认证头，不跟随重定向；请求地址禁止用户信息、query 和 fragment。测试专用工厂只允许字面回环地址 HTTP，不接受任意主机名。

协议以 [RFC 7662](https://www.rfc-editor.org/rfc/rfc7662.html) 为基础，但当前适配器要求返回更多应用字段：HTTP 200、JSON 类型、`active: true`，并匹配已验签令牌的 issuer、subject、client、jti、过期时间、tenant、actor kind、角色；令牌带有 MFA 声明时这些字段也必须一致。标准允许省略的字段在本 profile 中可能是必需字段；需要身份源或受信网关支持，不能对接任意内省端点后直接假定兼容。

响应最多 64 KiB，严格 UTF-8；总期限包含排队和响应体读取。错误、过载、超时、非 JSON、字段不匹配均拒绝，不能降级到离线放行。身份对象一旦拒绝不会被后来的有效响应恢复，需重新验签创建新对象。实例同时使用单调计时与不回退的已观察时间，避免本机时钟回拨延长有效期或 MFA 新鲜度。

`IdentityPolicy` 显式配置，无隐式签发者、角色或 MFA 默认规则：

| 配置 | 范围/含义 |
| --- | --- |
| issuer / audience / clients | 固定 HTTPS 签发者、唯一服务受众、1–32 个允许的客户端 |
| rolePermissions | 1–64 个审核过的角色，每角色至多 128 个 SDK 权限 |
| maxTokenLifetimeMillis | 1 秒–1 小时，令牌最大有效期 |
| maxHumanAuthAgeMillis / humanAcr | 1 秒–1 小时，人工认证最大年龄，以及 1–16 个认可的认证等级 |
| statusTimeoutMillis | 1–5000 毫秒，在线检查含排队总期限 |

`IdentityLimits.DEFAULT` 为 16 个身份会话、4 个状态查询工作线程、32 个排队请求、1000 毫秒后台检查间隔、2000 毫秒未绑定 permit 有效期。可通过五参数验证器构造器调整；范围分别为 1–128、1–16、1–128、100–5000 毫秒、100–10000 毫秒。过载拒绝，不无限创建线程。每次业务边界检查仍即时查询，不把后台间隔作为撤销缓存 TTL。运行时和 actor 应复用，不能为每个模型步骤创建验证器。

自定义状态适配器属于受信 Java 代码；如果忽略中断，其工作线程可能继续占用，但数量有界。在线查询与业务副作用之间没有分布式原子事务：查询后发生的撤销不能回滚已提交操作；返回结果仍会再次检查并阻断交付。HTTP 取消不证明身份服务进程终止。

## 绑定实际治理会话

以下代码中的公钥、凭据来源和策略均由可信宿主配置；`bearer` 是 Web 安全边界提取的访问令牌，不能从 Agent 正文读取。示例省略宿主既有的密钥和 HTTP 认证配置。

```java
var identityPolicy = new IdentityPolicy(
        "https://issuer.example.invalid", "agent-api", Set.of("runtime", "review-ui"),
        Map.of("reader", Set.of("read"), "reviewer", Set.of("approval:decide")),
        60000, 10000, Set.of("mfa-level"), 1000);
var status = new HttpTokenStatus(
        URI.create("https://issuer.example.invalid/introspect"),
        hostIntrospectionAuthorization, 1000);
var arguments = ParameterRule.object(Map.of());
var budget = new BudgetLimits(5, 5, 60000);
var policy = StructuredPolicy.builder("identity-v1").budget(budget)
        .tool("read", new ToolRule(Set.of("read"), arguments, false)).build();
var scope = new DelegationScope("task", false, Map.of("read", arguments), Map.of());
try (var verifier = new JwtIdentityVerifier(identityPolicy,
             Map.of("reviewed-key", trustedRsaPublicKey), status, Clock.systemUTC());
     var control = AgentControl.builder(policy).audit(hostAuditSink).build()) {
    var actor = verifier.verify(new AccessToken(bearer));
    try (var permit = actor.permit("reader-agent", policy, scope);
         var session = control.openAuthorizedSession(permit,
                 new TaskLimits(budget, 10, 1, 0), null);
         var binding = session.bind()) {
        // 在此线程调用既有受控工具/模型；此 scope 仅允许 read 工具。
    }
}
```

permit 单次绑定、未绑定自动到期，运行时必须 ENFORCE，策略完整指纹一致、预算不能扩张。绑定后不能改租户/主体/权限或换会话 ID；撤销、到期、验证器关闭会停止受控交付。关闭后的终止审计仍保留原身份范围。`actor.context()` 只是身份快照；将它传给普通 `openSession()` 不具备此持续检查能力，应使用 permit 路径。

当前 permit 不授予隐式本机派生权限，`TaskLimits.maxDelegationDepth` 必须为 0。已有跨进程签名委托仍是另一授权路径，本阶段尚未完成两者的组合验证。也没有自动把认证身份接入 `JdbcAuditVault` 管理端或演示 Origin 配置。

## 验收与后续

专项测试覆盖真实签名、字段伪造、重复字段、服务账号冒充人工、陈旧 MFA、时钟回拨、并发晚到有效响应、忽略中断的状态查询、有界会话名额，以及实际 HTTP 内省到工具调用的撤销路径。详见 [结构化证据](evidence/authenticated-identity-governance.json)。

后续已补可选[持久审批与职责分离](durable-approvals.md)，绑定已认证申请人与独立审批人、实际有效参数、策略和副作用目标；批准只能消费一次，超时、取消及消费后进程退出不能凭旧请求重新执行。该能力需另行配置，不由本身份模块自动启用。企业 IdP 配置、密钥轮换运营、登录与 CSRF 边界、生产性能、数据库方言、供应链工具版本审核仍未完成，不能以本模块通过测试作为整体生产安全认证。
