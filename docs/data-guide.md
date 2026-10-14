# RAG 检索与记忆访问治理

v0.4 把授权落实到实际 ContentRetriever 和 ChatMemory。模型前的内容检查继续保留；数据访问权限先决定源能否被调用，数据后置检查再决定读取结果能否进入框架。

## 运行示例与策略

~~~bash
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar data all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar data rag-transform examples/policies/data-access.yaml
~~~

七个场景覆盖 RAG 允许、跨租户拒绝、查询/结果改写、记忆写入改写、跨所有者拒绝、只读身份写入拒绝和数据额度耗尽。禁止访问时 sourceCalls/memoryReads/memoryWrites 为零。数据预算示例已经完成一次授权访问，第二次拒绝，因此 dataCalls/sourceCalls 为 1。

文件策略在仓库 examples/policies/data-access.yaml。它沿用 agt.java/v1，并增加 resources、dataLimits 和 pre_data_access/post_data_access。只有模型与工具的旧策略仍只需原有八个点；构建数据装饰器时额外要求两个数据点。非法资源类型、权限、操作和未知字段启动失败。

~~~java
import io.agt.core.data.*;

var policy = StructuredPolicy.builder("support-data-v1")
        .resource("knowledge", new DataRule(
                DataResource.Kind.RETRIEVAL, DataRule.Scope.TENANT,
                Map.of(DataOperation.RETRIEVE, Set.of("knowledge:read"))))
        .resource("conversation", new DataRule(
                DataResource.Kind.MEMORY, DataRule.Scope.PRINCIPAL,
                Map.of(DataOperation.MEMORY_READ, Set.of("memory:read"),
                       DataOperation.MEMORY_ADD, Set.of("memory:write"))))
        .content(new ContentRule("data-redact",
                Set.of(InterventionPoint.PRE_DATA_ACCESS, InterventionPoint.POST_DATA_ACCESS),
                List.of(), Map.of("private", "masked")))
        .build();
~~~

操作与权限互不隐式继承。MEMORY_SET 表示替换记忆，可以调用原生默认实现中的 clear/add；授予 set 就授予替换语义。MEMORY_CLEAR 仍是另一项显式权限。DataRule 不自动发起数据审批；现有工具审批保持不变，可在宿主 PolicyEvaluator 中扩展数据审批要求。

EVALUATE_ONLY 只记录拒绝和改写裁决，实际数据访问可能继续，原值不会被策略改写；观察模式不能用作隔离保护。结构或资源绑定错误仍拒绝。

## 推荐接入

宿主先从实际隔离的数据源注册表取得描述符与 delegate。以下变量 tenantId/principalId 必须来自认证入口，persistentMemoryId 必须对应实际记忆实例，不能从提示词推断所有者。

~~~java
var knowledge = new DataResource("knowledge", DataResource.Kind.RETRIEVAL,
        tenantId, null, physicalPartitionId);
var conversation = new DataResource("conversation", DataResource.Kind.MEMORY,
        tenantId, principalId, persistentMemoryId);

var assistant = new LangChain4jGovernance(control)
        .aiServices(Assistant.class)
        .chatModel(model)
        .contentRetriever(knowledge, actualPartitionRetriever)
        .chatMemory(conversation, actualMemory)
        .build();

try (var session = control.openSession(authenticatedIdentity)) {
    String answer = assistant.chat("查询资料");
}
~~~

同样可以配置 CompletableFuture/CompletionStage 服务，以及独立的 TokenStream/streamingChatModel 服务。SDK 的单源 augmentor 直接组合受控检索器，使用原生 DefaultContentInjector 注入检查后的内容，避免默认 query/router 的异步 fallback 丢失线程身份。同步源明确不支持 async 时，在 SDK 有界 worker 上 fallback；每次受控源访问只计一次数据调用。

查询改写改变实际传给检索器的 Query，以及 Query metadata 中允许改写的消息正文/历史/系统文本；MemoryId、InvocationContext 和消息角色/调用 ID 保持原有绑定。内容投影不序列化任意 InvocationContext/InvocationParameters 对象，也不改写宿主注入参数；检索实现应使用受控 Query，不应自行从原方法参数重建查询。它不自动改变 Agent 原始输入或原始系统提示；需要对模型中的原输入脱敏时，另配 INPUT/PRE_MODEL_CALL/OUTPUT 内容规则。

框架保存的每次记忆读取、追加、替换和清空均再次检查。写入改写落到实际存储；读取改写只改变交付结果，不自动写回存储。读取被拒绝时只阻止传播；已经发生的读取、写入或清空不能撤销。

## 多实例记忆与 MemoryId

~~~java
interface Assistant {
    String chat(@MemoryId String memoryId, @UserMessage String message);
}

var assistant = adapter.aiServices(Assistant.class)
        .chatModel(model)
        .governedChatMemoryProvider(Map.of(
                aliceMemoryId, new MemoryBinding(aliceResource, aliceMemory),
                bobMemoryId, new MemoryBinding(bobResource, bobMemory)))
        .build();
~~~

注册表只接纳 String ID，键必须等于 DataResource.instanceId 和原生 ChatMemory.id()。SDK 在构建和访问前确认实际记忆 ID 未改变；未知 ID 拒绝。MemoryId 只是选择器：Alice 请求 Bob 的已注册 ID 时，仍必须通过资源所有者/租户校验，不能因为知道 ID 就获得权限。

一个 runtime/assistant 可以并行服务多个用户，每次调用用自己的可信会话。宿主应提供不可变且实际隔离的存储 handle，避免在授权与实际访问之间自行改变 delegate 的存储位置。ChatMemory.id() 应是纯元数据 getter，宿主注册 handle 时不能预先加载未经授权的内容。SDK 不提供 JVM 沙箱或存储事务。

## 高级装饰器

~~~java
var retriever = adapter.guardRetriever(knowledge, actualPartitionRetriever);
var memory = adapter.guardMemory(conversation, actualMemory);
try (var session = control.openSession(authenticatedIdentity)) {
    var contents = retriever.retrieve(Query.from("查询"));
    memory.add(UserMessage.from("需要保存的文本"));
    var history = memory.messages();
    // memory.set / clear 需要分别授权；addAsync/setAsync/messagesAsync 同样受控。
}
~~~

高级装饰器覆盖实际源的前后检查；若需要 input/output 的完整 Agent 闭环，仍用受控 Builder。原有 chatMemory(raw)、chatMemoryProvider(raw) 和 retrievalAugmentor(opaque) 继续提供上下文桥，没有自动数据 ACL；不能把任意 opaque augmentor 声称为全部叶检索器已治理。接入明确的数据组件后，冲突配置或覆盖为原始组件会报 data_component_conflict。

复杂多源、rerank、路由管道可由宿主组合，但每个叶检索器都需显式包装，并在调度时传播会话。SDK 拒绝已知原生 ListeningContentRetriever 和装饰器上的 addListener/addListeners，避免检查前原文回调；自定义 delegate 内部的日志、监听器与 I/O 无法自动发现，宿主必须遵守相同约束。

## 检查、容量与审计

~~~mermaid
flowchart TD
    A[宿主绑定实际数据分区 / 存储实例] --> B[可信会话 / 独立数据额度预占]
    B --> C[pre_data_access / 资源许可 / 租户 / 所有者 / 操作权限]
    C --> D{授权与形状恢复通过}
    D -->|否| E[源调用为零 / 释放未执行预占 / 终止会话]
    D -->|是| F[实际查询或记忆读写 / 使用有效值]
    F --> G[post_data_access / 内容检查 / 结果改写 / 审计确认]
    G --> H[注入模型 / 返回记忆结果]
    G --> I[写后拒绝只阻止传播 / 不撤销副作用]
~~~

| 配置/行为 | 默认与边界 |
|---|---|
| dataLimits.maxCalls | 每会话 1,000；范围 1..1,000,000 |
| dataLimits.maxItems | 单次文档/消息数量 1,000；范围 1..10,000 |
| 设置位置 | YAML dataLimits、StructuredPolicy.Builder.dataLimits、AgentControl.Builder.dataLimits |
| 数据次数 | BudgetSnapshot.dataCalls；模型和工具次数分别计数 |
| JSON/文本限制 | 沿用全局严格 JSON、Unicode、深度和 payload 限制 |
| 异步等待 | 沿用会话期限、撤销/关闭、取消和有界 worker |
| 数据审计 | schemaVersion 2，增加 dataOperation/resourceIdentity；其他事件继续 schemaVersion 1 |

授权或形状恢复失败释放预占；进入实际 delegate 后失败仍计数。数据次数表示受控接口调用，不拆分 delegate 内部的 SQL、embedding、HTTP 重试，也不提供 token/费用上限。源自身的缓存与返回对象在进入 SDK 前可能已分配，maxItems 不等于整个存储或 provider 的堆内存上限。

查询、检索结果、消息和 metadata 进入有界投影。文档正文可改写；文档来源 metadata/ContentMetadata 只能检查，不能被策略更换或改写。TextSegment.metadata 的 String/Integer/Long/Float/Double/UUID 字段以及 ContentMetadata 的有界 JSON 数值/字符串结构保留；任意 Java 对象等未支持类型明确拒绝。role、工具调用 ID、资源身份、操作和存储 ID 都禁止改写。

数据审计继续关联会话、动作 ID、策略版本、裁决、执行结果与耗时。dataOperation 是操作枚举，resourceIdentity 是绑定描述符的摘要，执行记录与前后决策共用它们。默认不记录资源 ID、租户、所有者、查询或结果原文；摘要仅用于关联，不承诺匿名化、防篡改或跨语言哈希一致。必需审计失败会终止路径，区分访问前和访问后故障。

## 已验证边界

DataResource 是宿主对真实分区的绑定声明。共享未过滤索引不能仅填入当前 tenantId 就获得隔离；宿主必须落实数据库/向量存储权限或可信过滤，并保证 delegate 确实使用该分区。返回文档 metadata 不是可信身份来源，不能拿它来给调用者补授权限。

DataOperation 约束接口语义，不分析 delegate 内部 SQL 或副作用；原始引用、未包装的源、工具实现及任意 Java 代码仍不受自动治理。字面规则无法识别所有投毒内容；本阶段没有新增认证系统、存储事务、跨 Agent 协议或全局费用治理。

使用示例与测试证据见 [当前验收](data-verification.md)，规格见 [数据需求](data-requirements.md)。
