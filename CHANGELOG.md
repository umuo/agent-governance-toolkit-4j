# 更新记录

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
