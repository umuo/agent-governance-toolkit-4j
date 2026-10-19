# 流式增量护栏

`StreamDelivery.GUARDED` 为原生 LangChain4j 服务增加正文的局部字面检查。在 `ENFORCE` 下，被选中的有限词表规则命中时，该次命中的所有字符不会外发，受控流失败并尝试取消 provider。完整的模型和服务输出检查仍然执行；已交付的安全前缀无法撤回，因此这不是整段语义安全保证。

网页运行和模式对比见 [流式护栏网页案例](streaming-web-cases.md)。

## 配置与规则绑定

使用现有 `StructuredPolicy` 的 `ContentRule`，将字面拒绝规则绑定到 `POST_MODEL_CALL`，再通过 `localRules` 显式选入局部检测。其他字段及其他规则仍由完整治理执行。自定义 `PolicyEvaluator` 不会被自动推断为增量检测器，当前不支持在 GUARDED 中使用。

~~~java
var rule = new ContentRule(
        "local-deny", Set.of(InterventionPoint.POST_MODEL_CALL),
        List.of("ABCDEF"), Map.of());
var policy = StructuredPolicy.builder("stream-policy-v1").content(rule).build();
var control = AgentControl.builder(policy).audit(auditSink).build();
var options = new AiServiceOptions(
        StreamDelivery.GUARDED, StreamingLimits.DEFAULT, 32,
        ChunkBufferOptions.defaults(Set.of("local-deny")));
var assistant = new LangChain4jGovernance(control, options).create(
        Assistant.class, trustedContextSupplier,
        builder -> builder.streamingChatModel(streamingModel));
~~~

相关类型：`io.agt.core.policy.ContentRule`、`io.agt.core.policy.StructuredPolicy`、`io.agt.core.streaming.ChunkBufferOptions`、`io.agt.langchain4j.AiServiceOptions`。`Assistant` 返回 `TokenStream` 或文本 `Flow.Publisher<String>` / 可选 `Flux<String>`。会话身份仍由宿主认证上下文提供；资源关闭沿用既有宿主生命周期。

| 入口 | GUARDED 支持情况 |
| --- | --- |
| 原生 `aiServices(type, configure)` / `create(type, identity, configure)` | 通过带第四参数的 AiServiceOptions 显式启用 |
| 原生 TokenStream、文本 Flow.Publisher、可选 Flux | 共用每轮模型正文门；Publisher 保留现有预取和队列上限 |
| 模型 handler / 模型事件 Publisher | 内部 StreamingAdapters 装配共用同一门；公共低层模型装饰器仍为 STRICT |
| 旧 `.aiServices(type)` Builder | 保留 STRICT，不读取原生服务的 GUARDED 配置 |
| 非正文事件 | provider thinking、partial tool call、raw event 不预览；权威完整工具调用仅在完整模型检查后释放，其他完整回调沿用现有投影治理 |

原生默认仍为 REALTIME；三参数 `AiServiceOptions` 构造器保持原行为。选择 GUARDED 却没有显式局部配置会提前失败。`LangChain4jGovernance(control, StreamDelivery.GUARDED)` 因缺少规则配置同样失败，改用完整 Options。

## 缓冲、计时与容量

Core 的 `ChunkBufferEngine` 不依赖 LangChain4j、Spring 或 Reactor。达到字符阈值、边界字符、最大等待时间或完成事件时检查。有限窗口使用 JDK `String.indexOf`，大小写敏感，语义与 `String.contains` 一致；不做归一化、空格折叠或语义模型调用。

第一版有意选择有界窗口重扫，无新增匹配器依赖。每流只保存未交付尾部与微批，最多保留最大词长减一的未决尾部，另为代理对边界保留最多一个字符。禁词 `ABCDEF` 分为 `普通文本ABC` 和 `DEF后续` 时，超时也不会强行释放 `ABC`。标点只触发检查，不清空匹配上下文。一个 provider 大片段会逐段处理，不先复制为完整历史。

| 配置 | 默认 / 上限 |
| --- | --- |
| chunkCharacters | 20，至少 1，不能超过缓冲上限 |
| boundaries | `，。！？\n`，最多 256 UTF-16 单元；可为空 |
| maxWait | 50 ms，范围 1 ms–60 s，从首个待检查字符开始 |
| maxBufferedCharacters | 4096，范围 2–65536，不大于 StreamingLimits.maxCharacters |
| localRules | 必须显式非空；最多 256 个规则 ID |
| 词表 | 总共最多 256 个词、65536 UTF-16 单元；单词最多 2048 单元 |
| 缓冲容量关系 | 最大词长 + chunkCharacters 不得超过 maxBufferedCharacters |

计时只是检测调度目标，不是任意字符的交付延迟上限。每流最多一个有效计时任务；生产接入通过现有有界 worker 池处理计时检查与回调，不在会话期限调度线程上执行应用回调。旧计时任务即使已开始运行，也不能触发新一代检查。拒绝、关闭、取消和过期会丢弃尾部、取消任务并释放缓冲；迟到 handle 仍尝试取消。

`StreamingLimits` 继续累计计量 provider 事件和最终响应，重扫不重复计量。GUARDED 将原生事件/文本队列也限制到服务预取窗口。慢消费者导致队列溢出时终止并尝试取消；SDK 额度超限报 `stream_capacity_exceeded`，原生框架异常沿用稳定错误码的安全映射，不增加无限队列。

## 完成与冲突

每轮工具循环有独立缓冲器及偏移量，不跨轮匹配。权威最终正文用 UTF-16 增量摘要及长度核对，不额外保存完整历史；不一致报 `stream_text_mismatch`。正常完成时，先通过 `post_model_call`；最终无工具轮次还执行一次完整正文投影的 `output` 检查，再释放尾部。服务层原有的完整/聚合 `output` 检查仍执行。因此 `output` 审计数量可能多于 REALTIME，模型和工具预算不会重复计数。

第一阶段没有流式改写。StructuredPolicy 中任何绑定 `POST_MODEL_CALL` 或 `OUTPUT` 的 replacements 配置都会在装配时失败，提示使用 STRICT。可能改写或重试回答的原生输出护栏同样提前拒绝。输入护栏仍可使用。`EVALUATE_ONLY` 记录局部 DENY 后继续原文；容量、取消、审计和协议故障仍终止，不宣称已拦截。

局部拒绝通过 `GovernanceException("stream_content_denied")` 进入现有错误路径。SSE 或 WebSocket 错误帧由应用映射。无法取得 provider handle 或底层不支持取消时，只保证受控流终止和晚到内容不再交付，不能保证网络生成或计费立即停止。

局部审计复用 `AuditRecord`：`executionOutcome=stream_local`、`warnings` 包含阶段和逻辑起止偏移，`actionId` 与本轮完整模型审计一致。记录策略版本、模式、规则 ID、裁决与检查耗时，不保存正文和词表内容。EVALUATE_ONLY 的重叠窗口不会重复记录同一次命中。审计未成功写入时，对应文本不能放行；终止耗时沿用会话执行失败审计。

Spring 配置见 [Starter](spring-boot-guide.md)，需求与验证范围见 [增量护栏需求](streaming-guardrails-requirements.md) 和 [实现验收](streaming-guardrails-verification.md)。
