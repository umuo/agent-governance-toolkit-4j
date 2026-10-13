# 异步与并发工具需求 v0.2

本阶段延续首期治理契约，支持 LangChain4j 1.20.0 的 CompletableFuture/CompletionStage 文本 AI Services、ChatModel.chatAsync、ToolExecutor.executeAsync，以及同步 AI Services 的可选并发工具。

## 行为与验收

- 异步入口立即返回 future，策略评估、审批等待和同步工具启动移至有界 SDK 工作池；不阻塞模型的 HTTP 完成线程。
- 每个 future 的检查与完成信号恢复创建时的 HostSession，线程池复用后清理绑定；不把未知任务猜测绑定到某个最近会话。
- 并发工具共享原子预算、撤销与终止状态。每个工具独立授权；一项拒绝后禁止新动作和结果交付，不承诺并发批次事务回滚。
- 取消返回的 future、宿主撤销、runtime/session 关闭和期限耗尽都使在途调用有界结束，并向源 future 传播取消；不能保证任意工具代码响应中断。
- 一个会话同时仅有一个顶层 Agent 调用，但其内部工具可以并发。顶层 future 完成前不得关闭会话；关闭会取消未完成调用。
- 异步模型、工具和最终输出均在释放前检查、改写并重验；保留工具 ID、原生 InvocationContext 和返回行为。
- 模型不提供原生异步能力时明确失败，不偷偷在 HTTP 工作线程执行同步模型。同步 @Tool 允许在工作池执行；CompletableFuture/CompletionStage<String> @Tool 组合其 future。
- Builder 配置的 Memory/RAG future 同样传播上下文，但本阶段仍不提供数据源或记忆 ACL；同步 Memory/RAG 可由有界工作池承接。
- 保持所有既有同步验收。新增异步真实 AI Services 工具循环、官方 provider 本机 HTTP、审批、改写、跨租户隔离、队列饱和、取消/关闭/过期、并发预算和错误处理器吞拒绝测试。

## 接口与默认值

新增 withSessionAsync/openDetachedSession 自动或跨线程管理异步生命周期、HostSession.bind() 临时绑定、AgentControl.contextualExecutor(Executor)、有界异步工作池配置和核心 runAsync/operationAsyncPrepared 接口。Builder 增加 executeToolsConcurrently 与可选宿主工具执行器。

默认工作池为 4 线程/128 排队任务；拒绝超出容量的操作。默认同步工具保持顺序，原生异步工具允许并发。外部执行器由宿主管理；runtime 关闭自己的线程池和期限定时器。

流式、Reactive Publisher、多模态、复杂返回、RAG/Memory 权限及跨 Agent 通信仍按后续路线实现，本阶段不将其标为已支持。
