# 模型请求终止与可信回执

受治理的 LangChain4j 同步、异步和流式模型现在自动登记 `MODEL_REQUEST`，原生 AI Service 也沿用这条路径。不需要先启用 Token 计费。登记和开始准入失败时，不进入原生模型调用；准备阶段已经预占的用量会释放。流式收集器与模型包装器共享同一条登记。

## 默认语义

| 实际观察 | 执行账本 | 用量账本 |
| --- | --- | --- |
| 未进入原生入口就终止 | NOT_STARTED，释放活跃名额 | 未 dispatch 的预占释放 |
| 收到有效完整 ChatResponse / onCompleteResponse | 逻辑模型请求 CONFIRMED，释放名额 | 按单独的可信计量契约结算 |
| 取消 Future、关闭流或连接 | 请求取消，等待终止证据 | 已 dispatch 且用量未知的预占保留 |
| 取消后没有可信证据 | 确认期限后 UNKNOWN，保留名额并冻结根任务 | 不凭取消推定退款 |
| 可信宿主确认同一请求停止 | CONFIRMED，释放名额；已有根 fault 不清除 | 仍须独立核对用量 |

正常完整响应只证明 `MODEL_REQUEST` 这个逻辑请求已经结束，不证明 Provider 所有后台工作、机器资源都已清理，也不证明最终账单。原生异常默认不能证明请求从未发送或服务器已停止，因此保留 UNKNOWN。

[LangChain4j 的流式取消](https://docs.langchain4j.dev/tutorials/response-streaming/)提供传输取消入口；连接断开不能独立证明远端生成停止。SDK 会调用已有取消入口，但不会把 `StreamingHandle.cancel()`、本地 Future 完成、HTTP 404 或模型返回的“已停止”文字当作远端证明。

流式取消后仍到达的真实完整终止回调可补齐执行证据，但不会重新向调用方交付响应。正常错误保留原始脱敏错误码，例如 `stream_failed`；外部执行是否未知通过独立账本查询，不用它覆盖先前错误。

## 宿主提供逐请求停止契约

可在 `AiServiceOptions` 的第五个参数传入 `ModelTerminationContract`，第四个参数仍是可选 `ModelUsageMeter`。原三参数、四参数构造方式保持兼容。

```java
var options = new AiServiceOptions(
        StreamDelivery.REALTIME,
        StreamingLimits.DEFAULT,
        32,
        usageMeter,             // 可为 null；不启用用量计费
        terminationContract);   // 可为 null；仅观察原生终止，不推定取消确认
var governance = new LangChain4jGovernance(control, options);
var guarded = governance.guardModel(providerModel);
```

`terminationContract.bind(actualRequest)` 接收治理改写和用量约束后的实际请求，返回本次调用专属 `Call`。它与 `completed` / `failed` 分类必须是快速本地操作，不能在准备阶段发出生成请求。外部工作准入失败时，bind 可能已经发生；不能在这里占用必须等待网络取消才能释放的资源。

`Call.requestCancellation()` 返回异步证据。可信宿主需将这个 Call 与自己实际发送的 Provider 请求身份绑定，使用固定可信端点及宿主凭据发送停止请求，并核实同一任务已退出。请求 ID、取消端点、身份和确认依据不能来自不可信正文。该接口不会给任意官方客户端自动添加任务 ID 或厂商停止 API；需要宿主控制模型传输实现，或使用 Provider 支持的真实请求句柄。

尤其要处理“停止先于创建”：若取消请求先到达 Provider，后到的生成请求必须被墓碑记录或同等机制阻止。查询不到 ID 不足以证明以后不会启动。只有此约束成立，才能报告 NOT_STARTED 或完整停止确认。

默认 `completed(response)` 对有效完整响应返回 CONFIRMED，默认 `failed(error)` 返回 UNKNOWN。宿主可根据可靠 Provider 契约覆盖分类；不能仅根据客户端异常类型报告停止。证明只有 worker 退出时可返回 WORKER_ONLY，不能夸大为完整清理；取消后的部分证明仍按 UNKNOWN 处理。

Spring Boot 项目可以提供唯一的 `ModelUsageMeter` Bean 与 `ModelTerminationContract` Bean，默认 `AiServiceOptions` 自动接入。两者都可缺省；自定义 `AiServiceOptions` Bean 优先，多个同类型 Bean 必须由宿主明确选择（例如 `@Primary`）。不通过 YAML 配置任意取消 URL 或凭据。

## 跨进程与恢复

模型登记复用 [外部执行账本](external-cancellation.md)，签名委托接收节点自动使用同一个权威活跃上限、回执所有权和分页快照。启用 [JdbcGrantRegistry](durable-authority.md) 时，模型记录随根事务持久保存；节点死亡只会形成待取消/未知，不能当作 Provider 已停止。

正常关闭运行时会给已排队回执最多两秒的发布机会；进程强杀或网络故障仍可能丢失最后确认。当前没有持久本机回执 outbox。执行退出与取消发布交错时，终止回执先补齐取消事实，避免将已取消请求错误展示为普通完成。

执行元数据包含 opaque ID、MODEL_REQUEST、状态和证据，不附带模型请求/响应正文或 Provider 凭据。调试 Trace 正文的采集与访问策略是另一条治理边界。

## 验证范围

- 本机真实 HTTP 模拟服务：关闭客户端 Future 后服务端生成循环继续；未发送停止 API，不制造确认。
- 受认证的模拟停止 API：回执等待实际生成循环退出，且墓碑阻止延迟创建；收到回执前保持未确认。
- 同步调用撤销：模拟服务实际停止，但已 dispatch 的 150 Token 预占仍因用量未知保留，根任务保持冻结。
- 独立 SDK JVM + LangChain4j 官方客户端 + HTTP + H2 JDBC：正常同步/异步响应写入 MODEL_REQUEST，节点正常退出后新注册表读取完整确认；强杀 SDK 后保留未知名额、阻止兄弟派发并保留用量预占。
- 原生流式迟到回调、异步响应分类与交付顺序、外部名额耗尽时不发送模型请求，以及 Spring 真实上下文装配。

测试使用合成任务与固定本机计价，没有验证厂商生产停止 API、真实账单、生产 IAM、高可用数据库或所有 Provider 的重试与后台任务行为。证据及完整回归结果见[模型终止验收记录](evidence/model-termination-governance.json)。
