# 原生兼容性扩展

本轮继续补齐固定 LangChain4j 1.20.0 的接入差异，不通过跳过治理来获得兼容。
第一阶段入口及旧 API 保持可用。

## 验收范围

| 能力 | 接入与验收要求 |
| --- | --- |
| 普通参数 | 数值、布尔、枚举、数组、集合、Map、record/POJO；输入快照及改写后按声明类型恢复，非法形状在调用前拒绝 |
| 结构化输出 | 同步及异步 POJO、集合、Result<T>；检查全部可披露内容，保留受保护计量元数据，不能从 Result 的附加字段取回未检查原文 |
| 默认方法 | 保留原生默认方法执行和内部服务调用，共用预算，最终结果仍受 INPUT/OUTPUT 治理 |
| 工具对象结果 | JSON 对象和多模态结果进入 POST_TOOL_CALL；文本、对象和多模态视图的改写一致 |
| 多模态 | 图片/音频/视频/PDF 的描述、URL 和载荷有界投影；保持内容类型、数量和编码形状；不声称文字规则能理解媒体语义或隔离远程下载 |
| 流式 | 用户已选择新原生入口默认实时，保留显式严格模式；增量属于未经最终检查的预览，完整模型响应和工具执行仍经治理 |
| Reactive | JDK Flow.Publisher 与框架已有适配器路径；遵循订阅、背压、取消及会话完成，不在 subscribe 前启动外部调用 |
| 事件/工具回调 | 接收经过自身披露检查的类型正确副本，工具 before 在授权/审批后、after 在结果检查后执行；异常故障关闭 |
| Moderation | 实际审核调用复用模型预算、检查和上下文，不额外访问未检查内容或绕过审计 |
| 补偿 | 复用原生注解发现与执行顺序，补偿作为显式授权动作；失败/取消会话不自动获得扩大权限，不承诺事务回滚 |

## 实现依据

复用已固定版本的原生 AI Services 类型解析、JSON 输出解析、Flow Publisher 和工具循环。
普通值使用 Jackson 的声明类型映射及严格有界 JSON 快照；框架消息、Result 等采用专用投影恢复。
媒体检查只作用于实际提交的元数据/载荷，外部 URL 不由 SDK 自动抓取。
回调与补偿扩展在已有框架注册点组装，不使用全局替换 AiServicesFactory 或私有字段反射。

官方机制参考：[AI Services](https://docs.langchain4j.dev/tutorials/ai-services/)、
[异步与 Reactive](https://docs.langchain4j.dev/tutorials/non-blocking/)、
[工具补偿](https://docs.langchain4j.dev/tutorials/tools/)、
[事件](https://docs.langchain4j.dev/tutorials/observability/)。
公开在线文档可能比 1.20.0 新，实际实现与验收以本地固定版本 API 和真实装配为准。

## 验证方式

对每类能力验证允许、拒绝和改写落实到实际调用/交付；覆盖取消、审计故障、类型混淆、载荷超限、
Reactive 非法 demand 和未订阅资源清理。保留原有严格模式回归，不用“支持实时”弱化严格模式断言。
最后在 JDK 17/21 运行全量验证，更新使用指南和当前兼容性矩阵。

## 本机验收记录（兼容扩展阶段）

以下记录对应 `11fd857`；后续验收见 [边界与资源优化](native-optimization-requirements.md)。

本阶段新增 32 项兼容性测试，使用真实 AiServices 装配和确定性本地模型；可选 Reactor
仅在测试范围引入 `langchain4j-reactor:1.20.0-beta30`，生产制品不强制依赖 Reactor。

| 环境 | 全量 clean verify | 测试结果 |
| --- | --- | --- |
| Azul JDK 17.0.11 | 通过 | 341 项：340 通过，1 项按条件跳过 |
| Azul JDK 21.0.4 | 通过 | 341 项：340 通过，1 项按条件跳过 |

跳过项为真实付费 Provider 验收；本轮未调用外部付费模型。官方 OpenAI Provider 的本地 HTTP/SSE
回环测试、原生沙箱测试均执行。两套 JDK 分别运行 native、同步、异步、严格流式、数据、命令、沙箱、
security 离线示例，共 16 组，全部通过。MkDocs 严格构建通过，已在浏览器检查接入页面及 Mermaid 渲染。

本轮主要测试位于 `StructuredCompatibilityTest`、`NativeStreamingCompatibilityTest`、
`NativeReactorCompatibilityTest`、`NativeCallbackCompatibilityTest` 和 `NativeCompensationTest`。
保留第一阶段 29 项 `NativeIntegrationTest` 以及旧 Builder 的严格流式回归。
