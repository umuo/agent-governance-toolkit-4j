# 使用原生 LangChain4j 配置

已有 Agent 可以保留业务接口、`@Tool` 对象和原生配置，仅替换构建入口，并提供宿主可信身份。
本入口针对 LangChain4j 1.20.0；原有受控 Builder 和独立装饰器继续可用。

## 从原生构建迁移

原来的代码：

```java
Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(model)
        .systemMessageProvider(this::systemMessage)
        .tools(tools)
        .maxToolCallingRoundTrips(10)
        .build();
```

新增治理后，配置回调中的 `builder` 类型仍然是 `AiServices<Assistant>`：

```java
var governance = new LangChain4jGovernance(control);
Assistant assistant = governance.create(
        Assistant.class,
        this::authenticatedIdentity,
        builder -> builder
                .chatModel(model)
                .systemMessageProvider(this::systemMessage)
                .tools(tools)
                .maxToolCallingRoundTrips(10));

String answer = assistant.chat("查询当前租户资料");
```

`control` 仍通过 `AgentControl.builder(policy).audit(audit).build()` 创建；策略需包含完整生命周期点，
工具仍需明确登记权限及参数规则。模型和工具由宿主创建。
回调只负责配置，最终构建由 AGT 完成；不要在回调中调用 `.build()`，也不要保存 Builder 在回调结束后继续修改。

`authenticatedIdentity()` 返回 `TrustedContext`，必须从服务端认证结果取得 agent、tenant、principal 和权限。
身份 Supplier 在调用方线程、每次无现有会话的调用开始时执行，异步任务沿用其快照。
不从 prompt、`@MemoryId`、工具参数或模型输出推导权限。
身份为空返回 `missing_trusted_context`；解析抛出异常返回 `identity_resolution_failed`，不透传异常消息。

## 会话生命周期

| 调用方式 | 会话处理 |
| --- | --- |
| 同步文本 / POJO / 集合 / `Result<T>` | 调用时创建，完成或异常后关闭；关闭成功后才返回结果 |
| CompletableFuture / CompletionStage | 创建 detached 会话，立即恢复调用线程绑定；结果或异常发生时关闭，包装 future 完成前确认关闭 |
| TokenStream | 调用时创建，启动后复用；交付完成、取消或期限结束时关闭，`completion()` 反映关闭结果 |
| Flow.Publisher / Flux / Mono | 每次方法调用对应单次订阅；收到首个有效 demand 才调用模型，取消或终止后关闭自有会话；未订阅仍受期限约束 |
| 已有同一 control 的会话 | 复用现有身份与预算，不调用身份 Supplier，也不关闭宿主会话 |

一个顶层调用是一份预算。连续两次独立调用创建两份会话；要让多个调用共享预算，用显式会话包围它们。
失败的现有会话不会被自动替换；需要由宿主结束失败会话，再按业务规则发起新的顶层调用。
本入口不会为任意宿主线程池自动传播会话，受控异步路径和工具执行器会传播。

异步取消直接调用返回 future 的 `cancel(true)`。流式保留原生接口形式：

```java
TokenStream tokens = assistant.chat("查询资料");
tokens.onPartialResponse(System.out::print)
        .onCompleteResponse(response -> System.out.println("已交付"))
        .onError(error -> System.err.println(error.getMessage()))
        .start();

var buffered = LangChain4jGovernance.buffered(tokens);
buffered.completion().join();
// 停止时：buffered.cancel();
```

这里的 assistant 接口返回 `TokenStream` 并配置 `streamingChatModel`。
新原生入口**默认实时**：`onPartialResponse`、thinking、工具参数片段和 raw event 是未经最终检查的预览。
宿主界面应标记“生成中，尚未完成检查”。最终 `onCompleteResponse` / `completion()` 仍经过 OUTPUT，
完整模型响应先经过 POST_MODEL_CALL，工具授权、审批和参数校验不因实时交付而跳过。
最终脱敏或拒绝不能撤回已交付增量；需要交付前保证时显式选择严格模式：

```java
var governance = new LangChain4jGovernance(control, StreamDelivery.STRICT);
```

严格模式缓冲每轮模型输出，最终检查通过后交付有效文本；不转发原始工具参数片段和 raw event。
`LangChain4jGovernance.buffered(tokens).deliveryMode()` 可读取契约。
旧 Builder 和独立 `guardStreamingModel` 继续默认严格模式。
流式文本回调在交付时执行，随后关闭会话；shutdown 审计失败会使 `completion()` 失败，不能撤回已交付的回调内容。
不启动的流仍占用会话，直到取消、到期或 control 关闭；不用时应调用 `cancel()`。

## 显式管理会话

已有会话管理代码时，可以仅替换配置入口：

```java
Assistant assistant = governance.aiServices(Assistant.class,
        builder -> builder.chatModel(model).tools(tools));

try (var session = control.openSession(authenticatedIdentity())) {
    String answer = assistant.chat("查询资料");
}
```

这个重载直接返回服务，不需要 `.build()`。原来 `aiServices(Assistant.class).chatModel(...).build()` 仍可使用。

## 原生配置与治理覆盖

支持原生消息 Provider/Transformer、请求 Transformer、输入输出 Guardrails、记忆、检索、工具注册、
工具错误处理和工具循环次数等配置。请求变换发生在实际模型检查之前；输入输出 Guardrails 继续参与原生流程，
模型和工具仍由 AGT 装饰。原生重试/reprompt 产生的模型调用也受共享预算约束。

`tools(Object...)`、`tools(Collection<Object>)`、工具 Map、`AiServiceTool` 列表、
`toolProvider` 和多个 `toolProviders` 均在注册点包装，保留立即返回语义。
动态 Provider 的 `isDynamic()` 和每次刷新后的工具检查保留。
普通同步调用默认顺序执行；`executeToolsConcurrently()` 使用 AGT 的有界工作池，
`executeToolsConcurrently(executor)` 使用携带会话上下文的宿主执行器。
新原生入口允许同步/异步工具及并发配置，也可在同一接口同时声明同步和流式方法。旧 Builder 的严格流式限制保留。

Memory/检索的普通配置只添加会话上下文桥，不自动提供数据权限隔离。
需要 ACL 时先通过 `governance.guardMemory(resource, memory)` 或
`governance.guardRetriever(resource, retriever)` 显式绑定 DataResource，再交给原生配置。
Memory Provider 必须依据宿主身份解析实际分区；任意 MemoryId 不是所有权证明，资源 instanceId 必须匹配记忆 id。
详见 [数据访问指南](data-guide.md)。

当前原生入口的能力矩阵：

| 能力 | 支持方式与边界 |
| --- | --- |
| 普通参数和返回 | 数值、布尔、枚举、record/POJO、数组、集合、Map，使用原生 JSON codec；声明类型必须可解析，拒绝丢字段、数值截断及隐式强转 |
| 异步结果 | CompletableFuture / CompletionStage；完成原生解析后按泛型类型检查及恢复 |
| `Result<T>` | 同时检查 content、来源、工具记录、中间响应及最终响应；计量和身份元数据受保护 |
| default 方法 | 保留原生调用行为，内部服务调用共用会话预算，外层结果也检查 |
| 多模态 | 图片/音频/视频/PDF 的实际元数据、URL 和有界载荷；保持内容类型/数量/MIME 等形状；文字规则不理解媒体语义，不主动下载 URL |
| Flow.Publisher | String 与 AiServiceStreamingEvent；有界缓存、背压、取消、最终检查；每次方法调用只能订阅一次，重试应重新调用服务方法 |
| Reactor | 宿主安装原生 `langchain4j-reactor:1.20.0-beta30` 后支持 Flux；Mono 使用原生异步模型路径和完整结果交付，不强制增加运行时 Reactor 依赖 |
| AI Service listener | register/unregister 保留；正文交付前独立执行 OUTPUT 检查和审计；Guardrail 观察事件只含数据副本，记忆只读、模型重试句柄禁用；错误事件只交付稳定错误及清理后的上下文 |
| 工具 before/after | before 位于授权、参数检查、审批之后，after 接收 POST_TOOL_CALL 后的副本；两者再经独立披露检查 |
| moderationModel | `@Moderate` 同步方法的真实审核在主模型调用前执行，共用模型预算及检查；原生异步/Reactive 的 Moderate 限制保留 |
| 自动补偿 | `compensateOnToolErrors(true)` 保留原生 `@CompensateFor` 发现；可恢复工具错误触发逆序补偿，每项按 `compensate_<工具名>` 独立授权 |
| 原始模型 listener | 仍拒绝，它可能在装饰器检查前收到 provider 原文；使用受控 AI Service listener |
| 配置回调内 build | 仍拒绝 `native_builder_build_managed`，最终构建统一由 AGT 完成 |

补偿参数包含 `arguments`、`receipt`（工具对象结果）和 `text`（工具文本结果），策略必须声明这些字段的规则、
所需权限与审批。补偿目标不得通过参数改写变更；内部保留原始业务回执，不使用对模型脱敏后的值回滚。
成功补偿的记忆结果标记为 `compensated_due_to_tool_error`。补偿计入工具预算；
取消、撤销、到期、审计故障或 POST_TOOL_CALL 拒绝导致的失败会话不会自动恢复执行补偿。
这不提供存储事务或“任何异常都已回滚”的保证。

保留 `@MemoryId` 和 `InvocationParameters`；版本仍固定为 1.20.0。
固定命令继续使用旧 Builder 的 `commandTool` 或单独 `guardCommand` 入口。

## 离线运行验证

```bash
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 verify
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar native
```

示例使用真实 AiServices 和确定性本机模型，无需 API Key。预期输出：

```text
answer=masked-value, toolEffects=1, closedSessions=1
```

源码在 `examples/src/main/java/io/agt/examples/NativeIntegrationDemo.java`。
行为测试位于 `agt-langchain4j/src/test/java/io/agt/langchain4j/NativeIntegrationTest.java`；
规格及选型见 [原生接入需求](native-integration-requirements.md) 与 [兼容性扩展](native-compatibility-requirements.md)。
