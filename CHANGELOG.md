# 更新记录

## 未发布 — 原生配置与兼容性扩展

- 新增原生 AiServices 配置回调和自动会话 create 入口，保留旧 Builder 和显式会话。
- 在框架工具注册和记忆初始化扩展点装配治理，保留动态 Provider、立即返回及原生消息/Guardrails 配置。
- 自动会话覆盖同步、异步、流式/Reactive 完成、取消及未启动过期；现有会话沿用身份和预算。
- 新原生入口支持普通参数、POJO/集合/Result、默认方法、多模态载荷、Flow 与可选 Reactor。
- 默认实时增量交付，保留显式 STRICT，完整响应和工具继续治理；旧 Builder 严格契约不变。
- 增加受控 AI Service listener、工具回调、同步 Moderation 与独立授权的原生工具补偿。
- 增加离线迁移示例、配置限制说明和行为回归，类型及版本支持范围保持不变。

## 0.6.0-SNAPSHOT — 2026-10-14

- 新增可选 agt-sandbox，引用 Central 的 agent-sandbox-sdk 0.1.0-alpha.1；core 不引入 JNA。
- FixedCommand 增加后端 SPI，复用原有 LangChain4j commandTool/guardCommand 路径，传入空后端明确拒绝。
- strict 与显式 allowHostReads 注册不可变宿主策略，不做普通进程或权限模式回退。
- 关闭 SDK 原文回调，严格检查两条输出的截断、聚合大小、退出、超时与 UTF-8。
- 有限守护工作池、零队列、有界调用方等待；取消/超时或未知执行异常后拒绝复用后端，不宣称 OS 回收已确认。
- 新增 Central SDK 接入契约、本机 macOS 原生测试和七个离线沙箱演示；现有 SDK 限制继续明确披露。

## 0.5.0-SNAPSHOT — 2026-10-14

- 增加 FixedCommand、CommandArgument、CommandLimits，以及 guardCommand / Builder.commandTool。
- 宿主固定程序、可信摘要、工作目录和 argv；模型只能选择有限枚举参数。准备阶段校验在改写/审批后、执行计数前完成。
- 清空继承环境、关闭 stdin、有界合并输出、严格 UTF-8、有限执行时间与容量；会话终止与中断尽力清理主进程和可观察后代。
- 同步重载与异步工具继续使用现有策略、预算、审批和审计；新增真实子进程/AI Services 测试及七个离线演示。
- 固定受信任程序入口不是操作系统沙箱，不提供任意模型生成代码的安全运行保证。

## 0.4.0-SNAPSHOT — 2026-10-13

- 增加可信 DataResource、按数据操作授权的 DataRule、独立 DataLimits 与两个数据访问干预点。
- 新增 guardRetriever/guardMemory、单源 contentRetriever 与显式绑定的记忆 provider，MemoryId 不建立所有权。
- 查询、查询上下文、检索结果与记忆消息检查/改写落实到实际 delegate 和持久化值；受保护身份与来源字段不能变更。
- 原生异步与同步 fallback 沿用会话/取消/审计语义，数据操作不计为模型或工具。
- 数据审计 schemaVersion 2 增加操作类型和资源摘要；继续默认无敏感原文。
- 新增七个数据演示、文件策略和实际框架的数据访问验收；原始 opaque augmentor/memory 路径不宣称自动 ACL。

## 0.3.0-SNAPSHOT — 2026-10-13

- 增加 guardStreamingModel、StreamingLimits 与原生 TokenStream 的受控 Builder。
- 抑制原始增量、提前工具事件与原文旁路；完整模型响应通过检查后进入顺序工具循环。
- 最终输出通过检查和审计后交付一个有效文本块与完成响应；改写同时落实到两类回调。
- 增加 BufferedTokenStream.completion/cancel 和交付 promise 的会话终止关联，覆盖关闭、期限、撤销与晚到结果。
- 增加六个流式演示和官方 OpenAI 客户端本机 SSE 工具闭环测试。
- 修复会话终止的预算预占竞态：先原子释放所有未执行预占，再通知失败 future；已进入执行的调用继续计数。
- 流式并发/异步注解工具、实时逐 token、Reactive 和原文事件回调明确拒绝。

## 0.2.0-SNAPSHOT — 2026-10-13

- 增加原生异步模型、工具和文本 AI Services，以及显式同步并发工具。
- 增加 withSessionAsync、detached 会话、临时绑定、上下文执行器、有界工作池和自动期限终止。
- 顶层取消、撤销、关闭和过期终止在途结果，传播源 future 取消；并发工具共用预算。
- Memory/RAG 增加 future 上下文桥与同步 fallback，不新增数据访问 ACL。
- 失败审计独立排队，成功交付继续等待审计确认；避免取消与慢审计阻塞期限定时器。
- 增加异步/并发离线演示与真实 LangChain4j、官方 provider 本机 HTTP 验收。

## 0.1.0-SNAPSHOT — 2026-10-13

- 首期同步治理核心、LangChain4j 适配器、结构化策略、改写、审批、预算与 JSONL 审计。
- 六个离线场景与首期 Java 17/21 验收。首期历史证据见 docs/verification.md。

### v0.6 安全场景教学补充

- 新增 ASI02 / ASI05 分包的 25 个独立中文场景类，包含完整 AI Services 循环、真实回环 HTTP、H2 内存 SQL 与原生隔离边界。
- 审批工作线程超时与调用方超时统一返回 approval_timeout；宿主 stage 异常保持 approval_failed，增加确定性回归。

### 2026-10-15 LangChain4j 包结构整理

- 根包保留四个既有公开类型，模型、工具、流式、记忆和 RAG 实现迁入对应 internal 子包，具体包装类仍为包级可见。
- 拆分消息与检索投影、共享限额及异步辅助职责；公开缓冲流门面委托内部状态机，保留原有方法与类型路径。
- 固定命令采用不可执行注册描述跨包装配，新增直接执行拒绝及名称错绑回归。

### 2026-10-15 SDK alpha.2 接入

- 正式依赖升级至 agent-sandbox-sdk 0.1.0-alpha.2；按有效请求预检后再提交工具预算，保留资源硬限额与所需隔离机制。
- 使用 SDK 取消句柄和独立回收快照，保留后端隔离与有限容量，拒绝管道/临时资源的未知或失败状态。
- 默认严格工具 PATH，关闭原文回调，stdout/stderr 聚合超限采用 TERMINATE；同步更新 live Bash 的私有 HOME 契约。
- 新增严格读取允许/拒绝、固定 JDK 程序、输出提前终止及 SDK 稳定错误/回收故障测试；macOS 进程树回收仍为 BEST_EFFORT。
