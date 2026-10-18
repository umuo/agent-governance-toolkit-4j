# 逻辑文档版本与检索一致性

内容摘要只能说明“读到了哪些字”，不能说明这段文字来自文档的哪次修订。本功能为可信入库过程增加逻辑文档 ID、递增发布版本和完整片段清单，并在受控检索与后续派发前验证它们。版本与分类是宿主声明，不代表文档没有提示注入。

## 为什么复用 JDBC 目录

本轮检查了 [LangChain RecordManager](https://reference.langchain.com/python/langchain-core/indexing/base/RecordManager) 与 [Qdrant 条件更新](https://qdrant.tech/documentation/manage-data/points/)。前者通过 hash、写入时间和 source ID 管理索引同步；后者可按条件更新数据。这些能力适合宿主组织入库，不直接替代本 SDK 的授权动作绑定和上下文派发检查。LangChain 使用 [MIT 许可](https://github.com/langchain-ai/langchain/blob/master/LICENSE)，这里检查的是它的 Python 索引接口；当前工程已使用 Java/LangChain4j；接入整套 Python 索引管理会增加部署成本。Qdrant 使用 [Apache-2.0 许可](https://github.com/qdrant/qdrant/blob/master/LICENSE)，选用它作为统一实现则要求额外服务或向量库迁移。本轮只核对官方接口与许可，没有部署这两个候选服务；它们仍可用于应用自己的索引管理。

因此保留应用现有检索器和向量库，复用 `agt-provenance` 的 JDBC 数据源与现有片段治理路径，新增版本目录。没有引入新的索引服务、解析器或数据库驱动。版本管理与标签写入按职责分在独立实现中，公开入口统一通过 `JdbcLabelStore` 提供。

## 可信入库发布

`initializeSchema()` 新增 `agt_document_head` / `agt_document_version` 两张表。升级应用需先由宿主执行迁移；读取失败不会自动建表或降级。

```java
var labels = new JdbcLabelStore(dataSource, 2); // SQL 语句超时，单位秒
labels.initializeSchema();
var resource = new DataResource("docs", DataResource.Kind.RETRIEVAL,
        "demo-tenant", "demo-user", "registered-partition");

var published = labels.publishDocument(
        resource,
        "employee-policy",           // 宿主管理的逻辑文档 ID
        0,                           // 预期当前版本；首次发布为 0
        Map.of("section-1", DataFragment.document(actualText).digest()),
        DataLabel.CONFIDENTIAL,       // 可信入库分类，不能读取文档自报等级
        new LabelProvenance("ingestion-task", "publish-001",
                List.of("host-policy"), LabelProvenance.Kind.CLASSIFICATION));
// published.version() == 1；应用将此版本及实际片段身份绑定到自己的后端。
```

每次发布完整清单，目录在单一事务中锁定文档头、保存历史并推进版本。并发发布者必须提供相同真实预期版本；只有一个成功，其他收到 `document_publication_conflict`。调用方用同一 task/action 重试相同发布，会拿到原始结果，不再递增版本；改内容、分类或预期版本后复用该动作会被拒绝。即使已存在后续版本，幂等重试也不把头指针倒退。

文档整体分类随版本单调上升，不能通过更新或撤下再发布降级。用空清单发布下一版本表示撤下；旧历史仍可由 `documentHistory(resource, documentId, version)` 查询。当前版本通过 `currentDocument` 查询。目录按完整资源描述符、租户、principal、分区及逻辑文档 ID 隔离；只支持 RETRIEVAL 资源。

清单最多 2048 个不同片段 ID，保存片段摘要、分类和来源，不保存正文。超过限制直接拒绝，不能截断后发布。ID 遵循既有治理标识符规范（1–128 个允许字符）。单次检索仍最多 128 个片段，每根任务最多观察 128 个不同片段、512 个来源引用，沿用原有限额。一个文档可分批检索，不意味着任务可无限积累上下文。

## 绑定实际检索结果

宿主通过 `DocumentResolver` 为实际原生 `Content` 提供不可变的 `DocumentSegment(documentId, version, segmentId)`。SDK 自己计算实际文字摘要，再核对当前发布清单。解析器必须稳定、快速、无副作用；不能直接信任正文或未经验证的 metadata 中的 ID/版本。

```java
// verifiedBindings 是宿主对实际后端结果建立的不可变绑定，例如可信数据库行和发布回执。
DocumentResolver resolver = content -> {
    DocumentSegment reference = verifiedBindings.get(content);
    if (reference == null) throw new GovernanceException("document_binding_missing");
    return reference;
};
var governance = new LangChain4jGovernance(control);
var retriever = governance.guardRetriever(resource, actualRetriever, resolver);
// 原生 Builder 也支持 .contentRetriever(resource, actualRetriever, resolver)。
```

`control` 必须配置 `.labelStore(labels)`，任务必须为该资源配置 `DataFlowPolicy`。显式版本检索器在没有信息流策略时以 `document_flow_required` 拒绝，不调用后端；缺少版本目录时以 `document_catalog_required` / `document_catalog_unsupported` 拒绝，不静默忽略版本。旧的两参数检索包装继续提供内容寻址模式，不能据此声称已启用逻辑版本校验。

版本引用参与 AGT-FRAGMENT-V2 身份摘要；同样正文在不同文档/版本/片段位置具有不同来源引用。原有无版本引用仍保留 AGT-FRAGMENT-V1 键。`LangChain4jFragments.content(content, reference)` 是与适配器一致的公开摘要入口。底层内容自带的 ID、等级不会自动变成可信版本身份。

增加版本引用也不能洗掉同一内容已有的机密等级：运行时会合并文档等级、版本片段等级与原内容级分类，并保守维护内容级下界。后续内容被重新分类时，版本路径在下一次检查前重新读取等级。版本片段分类历史引用清单摘要及内容级来源，保留因果线索。同次检索中相同正文属于不同文档时，内容级分类使用独立派生事件，并关联原始 data-action；不会把正常的等级合并误判成同一事件被篡改。

## 更新、并发和故障

| 情况 | 行为 |
| --- | --- |
| 未发布的文档 | `document_version_unknown` |
| 检索返回旧发布版本 | `document_version_stale` |
| 当前版本下的片段不存在或实际正文摘要不符 | `document_segment_mismatch_denied` |
| 检索进行中发布了新版本 | 返回检查拒绝旧结果，调用方不收到该检索结果 |
| 已经读入上下文后文档更新 | 后续模型、工具、数据、输出授权及派发检查拒绝旧上下文 |
| 模型准备阶段文档更新 | 实际原生派发前重检，未调用模型，准备登记安全结束 |
| 数据库不可用或历史/摘要损坏 | 拒绝，不能按公开数据继续 |

这是“只允许当前已发布版本”的策略，没有默认允许历史版本的开关。版本更新后应重新建立任务并检索，SDK 不擅自替换已有上下文。正常关闭仍完成取消和退出审计，不因上下文已经过期再次阻断清理。

所有检查针对检查发生时可见的目录状态，不在整个模型远程执行期间锁住文档。已经派发的模型请求或已经交付的文字无法回滚。目录发布也不与向量库写入组成分布式事务：宿主应先准备不可变片段并建立可信绑定，再发布清单；索引中暂留的旧片段会被拒绝，可能影响可用性。SDK 不自动删除旧向量或生成嵌入。

## 跨进程与边界

版本引用沿既有 action-bound 片段协议传给权威信息流服务，只增加逻辑 ID、版本和片段位置，不增加正文。远端模型/工具返回检查也只传干预点及必要身份，不传响应正文。SDK 与权威服务需要协调升级；旧端点不识别版本引用或返回检查时会拒绝，不能通过剥离字段、跳过检查来兼容。JDBC 授权检查点保存版本绑定，恢复后校验同一目录、资源和来源键；新节点不能通过恢复旧检查点绕过当前版本检查。宿主需保护这些来源标识，它们不是匿名数据。

该接入继续依赖可信宿主、真实后端绑定与数据库访问控制。清单摘要能发现意外损坏，不能防止有数据库管理权限的人重写全部历史。当前验证范围是 H2 与本机认证 HTTP 测试传输；没有声称验证生产数据库迁移、数据库备份回滚、高可用、外部向量库同步或不可伪造审计。

验收路径和完整回归结果见[逻辑文档版本证据](evidence/document-version-governance.json)。其余生产接入与 P1 仍按[补齐计划](security-hardening-plan.md)继续推进。
