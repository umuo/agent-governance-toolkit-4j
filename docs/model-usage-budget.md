# 根任务 Token 与费用预算

本功能按宿主配置的模型价格表和输入/输出上界，在实际模型调用前原子预占额度。多个子 Agent 共用账本；已执行消耗不会因关闭子会话而清零。默认不启用，不自动猜测 Provider 价格或 Tokenizer。

## 配置

以下价格是演示数值，不是任何真实服务的报价。价格单位为所选货币的每百万输入/输出 Token；货币在同一根任务内固定，金额使用 BigDecimal 运算，不使用浮点数。

```java
var usage = new UsageLimits(10000, new BigDecimal("0.10"), "USD",
        Map.of("my-model", new UsageLimits.ModelRate(
                2000, 200,
                new BigDecimal("1.00"), new BigDecimal("2.00"),
                Set.of("my-model-verified-version"))));
var limits = new TaskLimits(
        new BudgetLimits(20, 50, 120000), 100, 5, 2, null, usage);
var options = new AiServiceOptions(
        StreamDelivery.STRICT, StreamingLimits.DEFAULT, 32, verifiedMeter);
var governance = new LangChain4jGovernance(control, options);
try (var root = control.openTaskSession(authenticatedIdentity, limits);
     var binding = root.bind()) {
    var assistant = governance.create(Assistant.class, () -> authenticatedIdentity,
            builder -> builder.chatModel(provider));
    assistant.chat("本轮任务");
    var receipt = root.usageSnapshot();
}
```

类型位于 `io.agt.core.usage` 与 `io.agt.langchain4j`。`TaskLimits` 的旧四/五参数构造器、`AiServiceOptions` 的旧三参数构造器继续兼容。每个调用仍受模型调用次数、会话生命周期、权限和数据流约束。

`UsageLimits.maxTokens` 是根任务累计实际已结算 Token 与未结算预占上界之和的许可上限；`maxCost` 对应已结算配置价格费用与未结算预占费用之和。两种上限都可设为 0，表示不允许相应消费，不等于关闭预算。模型表中的 maxInputTokens/maxOutputTokens 是单次调用上限。返回模型名必须匹配请求模型或宿主审核过的 servedAliases；响应不能自行选择更便宜的价格表。

## 计量契约

`verifiedMeter` 是宿主提供的 `ModelUsageMeter`。SDK 将策略改写后、默认参数合并后的完整 ChatRequest 交给它，要求：

1. 限制最终发给 Provider 的输出 Token，上限使用 bind 的 outputTokenLimit；兼顾实际服务的默认参数合并、专有字段及自定义参数。
2. 返回覆盖完整请求的输入 Token 上界，包括角色包装、工具定义、RAG、媒体与协议开销，不能仅估算用户字符串或按字符随意换算。
3. Provider 必须遵守这些上界，并提供一致的 input/output/total 用量和可验证的返回模型名；底层隐藏的计费重试必须关闭，每次主动重试重新取得预占。

没有可靠 tokenizer 时，可按经过验证的 Provider 最大输入窗口整窗预占，代价是更保守；不能用未经验证的平均字符数当成安全上界。`ModelUsageMeter.standardParameters(inputUpperBound)` 仅处理标准 DefaultChatRequestParameters，遇到 Provider 专有参数明确拒绝，避免丢失实际输出限制。

专有 Provider 示例：本机官方 OpenAI 客户端测试把旧默认 max_completion_tokens=999 显式改为 50，并检查实际 HTTP 正文。仅把某字段设为 null 不够：客户端可能在第二次合并时补回默认值。某些模型使用 max_tokens，另一些使用 max_completion_tokens；宿主必须针对真实模型选择和验证。SDK 不把所有兼容接口当成完全相同的协议。

```java
ModelUsageMeter meter = (request, outputLimit) -> {
    var params = OpenAiChatRequestParameters.builder()
            .overrideWith(request.parameters())
            .maxCompletionTokens(outputLimit)
            .build();
    return new ModelUsageMeter.Bound(
            request.toBuilder().parameters(params).build(),
            verifiedFullRequestInputUpperBound(request));
};
```

这段示例只适用于宿主已验证使用 max_completion_tokens 且没有冲突 max_tokens/customParameters 的端点。标准 meter 不替代这些 Provider 契约测试。

同步、异步、原生 AI Service 和流式模型共用同一预占与结算机制。计量器不能修改已检查的消息、工具清单或模型路由。启用预算但未提供 meter、未知模型、请求上界超限时，在实际 Provider 调用前拒绝。通用 HostSession 模型操作也必须使用携带当前会话凭据的 MeteredModelInput；不能绕过适配器直接执行未计量的模型操作。未适配计量的 moderation 等路径因此拒绝，不会静默漏记。

## 取消与故障

| 情况 | 处理 |
| --- | --- |
| 余额不足 | 发送前拒绝，未发生 Provider 调用 |
| 准备失败或发送前取消 | 释放未发送预占 |
| 收到合法用量 | 按实际值结算并退还差额，凭据不能再次使用 |
| 发送后取消/异常且没有可信用量 | 保留预占，标记 uncertain，冻结根任务并通知子孙停止 |
| 缺少用量、计数矛盾、返回模型不匹配 | 保留预占并冻结根任务，不当作零费用 |
| 实际用量超过声明上界 | 记录实际超界消耗，冻结根任务，禁止继续；不能撤销已经发生的 Provider 消耗 |

`snapshot` 区分 settledTokens/settledCost、heldTokens/heldCost、inFlightCalls、uncertainCalls 和 fault。held 包含仍在途与结果未知的预占。未知额度不自动退回，没有模型可调用的清零或解冻接口。普通 Token 统计仍保留，但不能代替预算账本。

预算拒绝被链路标为 blocked；Provider 用量未知或违反上界被标为 error，不冒充防护成功。实时预览可早于最终用量确认，但发送前已预占完整上界；最终用量异常仍会停止任务。内容和数据等级检查继续独立生效。

## 验证与部署边界

核心验证位于 agt-core 模块的 `UsageBudgetTest`、`ModelUsageSessionTest`，覆盖并发原子预占、精确费用、未知用量、单次凭据、父子共享和发送前后取消。agt-langchain4j 的 `ModelUsageAdapterTest` 覆盖同步、异步、原生和流式路径，`ModelUsageLocalHttpTest` 验证两种预算均在第三次 HTTP 请求前拒绝，实际端点只收到两次。

```bash
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 \
  -pl agt-langchain4j -am -Dtest=ModelUsageLocalHttpTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

这些是本机模拟 Provider 的可重复测试，不是生产服务实际账单验证。安全性依赖宿主上界、Provider 限制和完整价格表；缓存折扣、媒体、请求费或其他计费项需由宿主保守计入。跨服务/跨进程账本需要显式使用 agt-delegation 的单一在线权威服务，见[远端预算交接](remote-delegation.md)。可选 [JDBC 授权后端](durable-authority.md)现已保存每任务账本并验证新 JVM 读取，不自动恢复失效任务执行；账户月度额度或真实账单核对仍不提供。独立根任务的额度分别由宿主发放，不能把每任务配额当成账户总额限制。

方案检查复用了本项目的预算预占、生命周期取消和官方 LangChain4j TokenUsage。[LiteLLM 的预算预占](https://docs.litellm.ai/docs/proxy/users)可作为部署集中式模型网关的选项，但不替代 JVM 中显式父子任务的配额归属；本轮没有新增代理服务依赖。

## 本阶段验收

2026-10-17：完整相关模块 Maven 回归 550 项，549 通过、1 项可选 Provider 测试跳过；补充预算与并发信息流组合测试后核心 115 项全部通过。官方客户端本机 HTTP 对照覆盖同步/异步 × Token/费用两类预算；四种组合的端点都只收到两次，第三次在发送前拒绝，Provider 默认的 999 输出上限在实际正文中被明确改为 50。组合测试确认：预占后、提交前受到信息流拒绝时，没有 Provider 执行、退回全部未发送预占，也不把根任务误记为未知消费。见 [结构化验收证据](evidence/model-usage-governance.json)。

## 跨进程预算补充

通过 `authority.register(source)` 将已结算本机消费及完整价格配置交给单一在线服务；接收端的 `UsageLedger` 与已有 ModelUsageMeter/适配器共用原预占和结算协议。交接需要没有未决预占，接收端不能用独立本机 UsageLimits 覆盖权威余额。多接收进程、父子授权共享同一账本；已 dispatch 的进程崩溃或祖先失联会保留额度并冻结全根任务。发送端用 `root.usageSnapshot()`，接收端用当前会话快照核对结果。

新增 `RemoteUsageTest`、`RemoteUsageLeaseTest` 验证并发与租约；`ModelUsageRemoteHttpTest` 启动两个独立 JVM，使用官方客户端通过 HTTP 请求模拟 Provider。同步与异步各一次后，第三次 Token/费用超额请求未到达 Provider；实际输出字段为 50，已结算合计 100 Token、固定测试费用 0.00012 USD。杀掉已发出请求的 JVM 后，150 Token 预占保留、uncertain=1，另一个 JVM 的后续模型调用被拒绝。此费用来自固定测试价格，不是实测账单。

本次最终全工程回归 588 项，587 通过、1 项可选 Provider 测试跳过，见[分布式用量验收证据](evidence/distributed-usage-governance.json)。
