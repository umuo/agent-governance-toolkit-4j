# v0.4 RAG 与记忆访问治理需求

本阶段承接严格缓冲流式之后的路线，实现实际数据源的检索前授权与记忆读写治理，沿用 AgentControl、策略、可信会话和框架适配器。

## 需求与可信边界

- 增加 pre_data_access/post_data_access，保留原有八个 Agent 生命周期点。资源规则默认拒绝，检索、记忆读取、追加、替换、清空独立授权。
- DataResource 由宿主绑定实际数据源的别名、类型、租户、所有者与实例 ID。模型输入、@MemoryId、文档 metadata 只能参与数据选择，不能证明租户或所有权。
- 访问实际 delegate 前检查资源许可、租户、所有者和该操作要求的权限。缺失资源、跨租户、跨所有者或操作未授权时，源检索、记忆读取和写入次数都为零。
- 改写检索查询时实际源收到改写值；检索结果检查在进入注入器前完成。内容正文可改写，来源元数据只能检查，不能被策略更换或改写。
- 记忆追加/替换在写入前检查全部消息，写入改写落到真实存储；读取结果检查与改写在返回框架前完成。读取改写不自动改变持久化存储。clear 无文本正文，单独要求清空权限。
- set 权限允许原生替换语义，包括 delegate 默认实现中的 clear/add；该内部实现不是第二次外部操作，也不重复计数。
- resource 身份、操作和物理 ID 禁止改写；改写无法恢复形状时关闭会话。同步与原生异步/fallback 进入同一授权路径。
- 数据访问使用独立每会话额度与单次 item 数限制，不计为模型/工具；实际访问前失败释放预占，访问后失败仍计数。取消、撤销、期限、审计失败与既有终止语义一致。
- 审计复用 AuditSink/JSONL、策略版本、会话与动作 ID；新增点默认同样不记录查询、内容、存储 ID、租户或所有者原文。
- 支持高级 guardRetriever/guardMemory；推荐 Builder.contentRetriever 与绑定描述符的 chatMemory/governedChatMemoryProvider。
- 原有 opaque RetrievalAugmentor/ChatMemory 入口保留上下文桥兼容性，不能宣称已自动授权所有内部源。复杂多数据源路由要求每个叶检索器显式包装并传播上下文。
- 宿主必须把资源绑定到真实隔离分区。SDK 不实现数据库行权限、向量引擎过滤器、存储认证或反射/JVM 沙箱；共享未过滤索引不能仅靠给包装器填一个 tenantId 就获得隔离。
- DataRule 不自动给数据操作加人工审批；现有工具审批仍保持。若数据源开放写工具，需要工具许可与数据资源许可同时成立。
- EVALUATE_ONLY 只记录策略裁决，不实施授权阻断或改写；禁止把观察模式当成数据隔离保护。

## 源码 spike 与方案

固定 LangChain4j 1.20.0，核对本机 Maven 发布源码的 ContentRetriever、ChatMemory、Query metadata、DefaultRetrievalAugmentor 与 DefaultContentInjector。参考 [ContentRetriever](https://github.com/langchain4j/langchain4j/blob/1.20.0/langchain4j-core/src/main/java/dev/langchain4j/rag/content/retriever/ContentRetriever.java) 和 [ChatMemory](https://github.com/langchain4j/langchain4j/blob/1.20.0/langchain4j-core/src/main/java/dev/langchain4j/memory/ChatMemory.java)。网络源码页抓取失败时以本机固定版本发布源码为核对依据，不声称未找到开源实现。

复用原生接口、消息投影、DefaultContentInjector 和现有治理状态机。SDK 的单源 augmentor 直接组合受控检索器，避免默认 query/router 的异步 fallback 在默认执行器上丢失身份。没有复制上游源码，没有新增向量库、认证系统或策略表达式引擎。LangChain4j 依赖继续遵循 Apache-2.0 与现有 NOTICE。

## 验收

- 非授权的检索、记忆读写/清空不会调用实际源；提示词或 MemoryId 不能建立可信所有权。
- 查询改写、结果脱敏、持久化写入改写和读取改写分别验收原值不会继续交付。
- 输入消息/来源 metadata/身份字段的拒绝、改写形状、数据预算、数量上限及故障关闭有自动化测试。
- 原生异步、同步 fallback、关闭/取消/期限与并行多租户保持同一治理路径，计数不重复。
- 真实 AI Services 的同步、future 与严格缓冲流式 RAG 路径收到经过检查的注入内容；记忆 provider 的每个实际读写仍受控。
- YAML 与 Java 策略进入同一流程，非法资源/操作/权限配置启动失败；不改变仅使用 v0.3 模型和工具的旧策略要求。
- Java 17/21 回归，运行示例与中文文档通过验收。
