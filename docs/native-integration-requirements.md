# 原生 LangChain4j 接入：第一阶段

本文保留第一阶段的需求和验收记录。当前扩展能力见 [兼容性扩展](native-compatibility-requirements.md) 和 [接入指南](native-integration-guide.md)。

## 目标与范围

让已有文本 AI Service 保留接口、原生 Builder 配置和工具声明，只替换构建入口并提供宿主可信身份。
新增 `aiServices(Class, Consumer<AiServices<T>>)` 用于显式会话，新增
`create(Class, Supplier<TrustedContext>, Consumer<AiServices<T>>)` 自动管理会话。
原 Builder、装饰器和手动会话 API 保持兼容。

本阶段不扩大 POJO、多模态、Reactive、版本兼容范围或实时 token 的治理承诺。
文本接口、工具结果、严格缓冲流式限制继续适用；Memory/RAG 授权仍需显式 DataResource。

## 选型与 spike

检查已固定的 LangChain4j 1.20.0 二进制公开 API 和装配字节码：
`AiServices` 可继承并通过 `AiServiceContext` 复用原生配置；`ToolService` 提供工具注册及动态 Provider 入口；
`AiServiceContext.initChatMemories` 可在保留原生缓存生命周期时加入上下文桥。
采用这些扩展点及原生 `AiServices.builder(context).build()`，不复制 Agent 循环、不反射读取私有字段，
不注册全局 ServiceLoader 工厂。相比继续补齐平行 Builder，此方案减少配置转存和框架行为重写，
代价是仍需对固定版本执行兼容性测试。

官方参考：[AiServices 源码](https://github.com/langchain4j/langchain4j/blob/1.20.0/langchain4j/src/main/java/dev/langchain4j/service/AiServices.java)、
[Guardrails](https://docs.langchain4j.dev/tutorials/guardrails/)。
原生输入输出 Guardrails 可参与装配，但不能替代模型、工具和数据访问治理。

## 行为要求

- 配置回调接收原生 AiServices 类型；消息 Provider、请求 Transformer、原生 Guardrails 等配置保留。
- 静态及动态工具均经原有 guardTool，保留立即返回语义；改写、审批、预算、审计仍走同一内核。
- 原生监听器、工具前后事件回调及自动补偿尚无完整交付/副作用治理契约，本阶段配置时明确拒绝。
- 无现有会话时，每次调用从宿主 Supplier 获取可信身份并创建 detached 会话。
  现有会话复用其身份、预算及生命周期；已失败会话不能靠自动创建新会话绕过。
- 同步调用返回前关闭自有会话；异步返回值及流 completion 在关闭后完成；取消传播至原执行。
  流式文本回调仍在流交付时发生，completion 额外反映 shutdown 审计失败。
- 未启动的流在取消、期限到达或 runtime 关闭后回收会话；调用线程恢复原绑定。
- 缺失身份、身份解析失败和关闭失败显式失败，不暴露底层异常消息。

## 验收

验证原生配置实际进入请求；静态/动态/立即返回工具的授权、改写与审批；
同步异常、异步完成/取消、严格流式完成/取消/未启动过期的会话清理；
多线程身份隔离、原生记忆/RAG 上下文传播及旧 API 回归。
提供无外部模型的可运行迁移示例，并运行 Maven verify 和文档严格构建。

## 本轮验收

2026-10-16，macOS arm64，固定 LangChain4j 1.20.0：

| JDK | core | LangChain4j | sandbox | live 发现/通过/跳过 | 全部发现/通过/跳过 |
| --- | ---: | ---: | ---: | --- | --- |
| 17.0.11 | 74 | 145 | 48 | 42 / 41 / 1 | 309 / 308 / 1 |
| 21.0.4 | 74 | 145 | 48 | 42 / 41 / 1 | 309 / 308 / 1 |

两个 JDK 的 clean verify 均通过，包含格式检查。新增 29 项原生接入测试验证：

- 原生消息 Provider/Transformer、Guardrails 和请求变换的实际效果及模型检查顺序。
- 八种工具注册方式、动态刷新、立即返回、租户拒绝、审批和结果脱敏。
- 同步/异步/严格流的自有会话关闭、取消、未启动到期、shutdown 审计失败及身份解析失败。
- 现有会话不重置预算，多线程身份隔离，默认有界并发池与宿主执行器的上下文传播。
- 原生记忆 Provider、显式授权检索器和记忆的异步接入，以及未治理配置的构建期拒绝。

两个 JDK 均运行新 native 示例和六个旧同步场景。
native 输出为 `answer=masked-value, toolEffects=1, closedSessions=1`。
MkDocs 严格构建通过；浏览器确认新增指南可读、首页 Mermaid 正常渲染。
脚本语法检查及 git diff 空白检查通过。

唯一跳过项为需显式启用的真实收费 Provider 测试；本轮未请求外部模型。
没有增加 Sonar、性能、长期负载或其他操作系统的验收承诺。
