# 文档片段与消息级来源

任务级信息流已能约束父子 Agent 和实例级数据写入。本功能再记录每个实际读取的 RAG 文本片段，以及每条实际写入/读取的记忆消息。来源记录是内容寻址的引用；它不代表内容可信，也不提供自动识别敏感信息或提示注入的能力。

## 标识与接入边界

复用 [LangChain4j RAG](https://docs.langchain4j.dev/tutorials/rag/) 的原生 `Content` / `TextSegment` 和[记忆接口](https://docs.langchain4j.dev/tutorials/chat-memory/)。框架的记忆接口不统一提供持久消息 ID，因此不从消息位置或文档自报 ID 推导授权。本轮直接复用现有数据操作、单调标签和 JDBC 事务，没有引入另一套向量库或消息存储。

`DataFragment` 有 DOCUMENT_SEGMENT / MEMORY_MESSAGE 两种类型。标识绑定完整宿主 `DataResource`、片段类型及内容摘要，使用独立的 AGT-FRAGMENT-V1 摘要域，防止与普通实例目录键混淆。摘要采用当前 SDK 的有界 JSON 投影，不声称兼容跨语言规范化标准；投影结构升级需要相应迁移。

- 文档摘要来自实际 `TextSegment.text()`；忽略文档元数据中的 ID、分类及信任声明。相同正文更换这些字段仍匹配同一标签。
- 消息摘要来自实际消息的角色、正文及受支持的结构投影；忽略 `attributes`。插入、裁剪或重排其他消息不改变该消息的摘要。
- 写入以准备/内容改写后的实际消息计算摘要；读取则记录原生结果的来源，即使交付正文已被策略改写，原来源和等级仍保留。
- 同一实例中的相同内容会合并成同一个片段身份，保留单调等级及多次写入历史。不同实例或租户使用不同目录键。

无版本的内容寻址模式不提供稳定逻辑文档版本号。显式版本检索与可信发布清单现已补充，见[逻辑文档版本](document-versions.md)。两种模式都不声称逐字节污点分析、逐次消息事件 ID，或证明某段内容没有注入。正文改变会形成新摘要，源站内容更新时必须由可信入库流程重新分类；不能依赖旧摘要自动判断新内容安全。来源未知的数据仍应配置保守的实例基线。

## LangChain4j 原生路径

在任务配置 `DataFlowPolicy` 后，现有 `guardRetriever`、`guardMemory` 和对应受控 Builder 路径自动投影片段来源，同步和异步均适用。没有信息流策略时继续沿用原行为；未包装的数据源不在该治理范围内。

持久化仍由 `.labelStore(labels)` 显式开启。`JdbcLabelStore` 复用既有版本、幂等、租户键和来源历史逻辑，提供 `readFragment`、`recordFragment`、`fragmentHistory`。自定义 `LabelStore` 需要实现这些方法，否则使用片段路径时以 `label_store_fragments_unsupported` 拒绝，不静默丢弃来源。

可信宿主可以在实际入库/分类时登记单个文档片段，再让原生检索器使用同一个数据分区：

```java
var labels = new JdbcLabelStore(dataSource, 2);
labels.initializeSchema();
var partition = new DataResource("knowledge", DataResource.Kind.RETRIEVAL,
        "demo-tenant", "demo-user", "registered-partition");
var fragment = LangChain4jFragments.content(actualContent);
labels.recordFragment(partition, fragment, DataLabel.CONFIDENTIAL,
        new LabelProvenance("ingestion-task", "classify-chunk", List.of("host-policy"),
                LabelProvenance.Kind.CLASSIFICATION));

// control 配置 labels；任务配置该 partition 的 DataFlowPolicy，宿主绑定实际检索器。
var retriever = new LangChain4jGovernance(control).guardRetriever(partition, actualRetriever);
```

`LangChain4jFragments.content(Content)` 和 `.message(ChatMessage)` 是与适配器一致的公开摘要入口，可用于目录查询和分类。实际分区、内容与身份必须来自宿主绑定的后端，不能把模型返回的 `tenantId` 或 `classification` 直接当作可信输入。

分类事件记录分类者及动作，写入事件记录写入任务、动作和已接触来源。标签先于实际写入落库，WRITE 代表保守的写入登记，不能单凭该事件断言外部写入已成功；底层失败或结果未知时不会删除标签。记忆 clear/set/窗口淘汰不删除历史分类，不提供模型可调用的降级接口。

## 手工数据操作

直接使用 `HostSession.dataOperationPrepared` 或 `dataOperationAsyncPrepared` 的应用，可在末尾传 `FragmentProjection<E,T>`。`written` 从实际准备值投影记忆写入摘要；`read` 从原生读取结果投影摘要。它们必须稳定、无副作用，不能从不可信字段直接接受等级。投影方向和类型不匹配时拒绝；读取引用不能替换成另一个片段。

旧的无投影重载仍提供实例级治理，无法知道自定义结果中的片段身份。宿主如果依赖片段目录中的细粒度分类，必须接入该投影或受控原生适配器；这里不声称能自动分析任意 Java 对象或绕过 SDK 的数据访问。

每次源接口调用仍只计一次数据配额，片段投影不增加模型/工具调用，也不为每个片段另计数据调用。当前单次最多投影 128 条内容/消息，每根任务最多记录 128 个不同片段，全部来源引用总数仍不超过 512；达到限制即拒绝交付，不截断后继续放行。应用需按真实上下文大小组织任务，不应通过删除已有来源绕开限制。

## 跨进程与持久恢复

远端仅传资源描述符、操作、片段类型/摘要和动作 ID，不发送文档、消息、查询或模型正文。`flow_validate_action` 把片段读取关联到本会话先前获准的数据动作，校验资源和操作一致；缺少准入、替换操作或使用旧的无动作接口均拒绝登记。旧端点不支持该操作时失败，不降级为丢弃来源。

根任务共享等级和片段来源，父节点的输出检查同样生效。JDBC 授权检查点包含片段绑定与已观察版本；恢复会校验策略、父实例、目录身份和来源引用，不能将片段替换到其他分区。细节见[持久授权指南](durable-authority.md)。

## 验证与限制

测试通过原生 LangChain4j 同步/异步检索和记忆路径验证：伪造元数据不降级、单独片段分类不误标同分区其他内容、写入改写绑定实际保存值、读取改写保留原来源、重开目录仍定位原生产任务。另验证片段目录并发首写、单调版本、命名空间及租户/分区隔离、容量拒绝和检查点来源重绑定拒绝。

独立两个工作 JVM 经 HTTP 调用持久授权服务：公开片段进入模拟模型一次，机密片段被拦截且原生模型调用为零；父节点输出受限，新注册表实例恢复相同来源。协议请求检查未发现合成文档正文、查询或模型输入。分类事件使用真实数据动作 ID，不再以通用 flow-check 充当片段因果引用。

实例级标签继续作为保守上界；已经被机密写入标记的整个记忆实例不会因为某条消息看起来公开而降低。摘要不是隐私匿名化，低熵正文可能被猜测，应与身份和策略元数据一起保护。此内容寻址阶段没有验证真实 Provider 账单、生产数据库兼容/高可用、加密保留或不可伪造审计。后续逻辑版本见[文档版本指南](document-versions.md)，外部取消见[回执指南](external-cancellation.md)；生产验证与 P1 仍在计划中。

本阶段全工程验证共 640 项，639 通过、1 项可选真实 Provider 测试跳过。参数、测试路径和边界见[结构化证据](evidence/fragment-provenance-governance.json)。
