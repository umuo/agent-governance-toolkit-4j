# Agent Governance Toolkit for Java

纯 Java 的 Agent 运行时治理 SDK，首个适配器支持 **LangChain4j 1.20.0**。使用方式参考微软 AGT：加载策略、创建可信会话、通过 `guardModel` / `guardTool` 或受控 AI Services Builder 接入治理。

当前 v0.6 支持同步/异步文本调用、严格缓冲流式、非流式的可选并发工具、RAG/记忆访问授权和八类生命周期干预点（另有两个数据访问点）、工具授权与参数校验、内容检查和改写、动作绑定审批、会话预算、撤销及结构化审计。新增固定命令工具：程序摘要、宿主 argv 模板、枚举参数、环境清理与有界进程执行。可选 agt-sandbox 已接入 Maven Central 沙箱 SDK 0.1.0-alpha.1，显式宿主策略和输出/取消补偿见沙箱指南。功能以治理运行时为中心，不受 LangChain4j Guardrails 的能力边界限制。

## 构建与演示

需要 Maven 3.6.3+、JDK 17 或 21。首次构建访问 Maven Central；测试只连接本机模拟端点，无外部 API key。

```bash
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 verify
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar async all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar stream all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar data all
# 实际加载 YAML 策略
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar allowed examples/policies/default.yaml
```

`all` 包含允许、跨租户拒绝、参数和结果改写、审批允许、审批拒绝、预算耗尽六个场景。被拒绝场景必须输出 `toolEffects: 0`；改写场景输出 `masked:modified`。

## 最小接入

将 SDK 安装到本机默认 Maven 仓库（供其他工程引用；制品尚未发布到 Maven Central）：

```bash
mvn -B -ntp -s .mvn/settings.xml install
```

再在应用中依赖：

```xml
<dependency>
  <groupId>io.agt</groupId>
  <artifactId>agt-langchain4j</artifactId>
  <version>0.6.0-SNAPSHOT</version>
</dependency>
```

模型由宿主配置，例如官方 OpenAI 客户端或本机模型。不要给原始模型注册可能收到未经检查输出的 listener。

```java
import io.agt.core.AgentControl;
import io.agt.core.TrustedContext;
import io.agt.core.audit.JsonlAuditSink;
import io.agt.langchain4j.LangChain4jGovernance;
import java.nio.file.Path;
import java.util.Set;

interface Assistant {
    String chat(String message);
}

// model、tools 为宿主创建的 ChatModel 与 @Tool 对象。
// 每次运行使用新的审计文件；真实权限必须来自宿主认证结果。
try (var audit = new JsonlAuditSink(Path.of("audit-new.jsonl"));
     var control = AgentControl.fromPath(Path.of("policy.yaml")).audit(audit).build()) {
    var assistant = new LangChain4jGovernance(control)
            .aiServices(Assistant.class)
            .chatModel(model)
            .tools(tools)
            .build();
    var identity = new TrustedContext("support-agent", tenantId, principalId, permissions);
    try (var session = control.openSession(identity)) {
        String answer = assistant.chat("查询当前租户资料");
    }
}
```

策略样例见 [default.yaml](examples/policies/default.yaml)。会话在当前线程自动绑定；同一个 runtime 与 assistant 可服务多个线程，每个顶层调用需创建自己的会话。异步调用等待 future 完成后再关闭会话；退出作用域会取消未完成任务。宿主负责从认证结果取得身份，SDK 不验证 token。

异步接口可声明 `CompletableFuture<String> chat(String message)`，服务入口可直接返回：

```java
return control.withSessionAsync(identity, () -> assistant.chat(message));
```

SDK 在 future 完成后关闭该会话；具体取消、并发与执行器约束见异步指南。

## 文档

- [需求规格与首期边界](docs/requirements.md)
- [SDK 使用与配置](docs/user-guide.md)
- [架构、扩展契约与选型](docs/architecture.md)
- [异步与并发工具指南](docs/async-guide.md)
- [流式使用指南](docs/streaming-guide.md)
- [RAG 与记忆访问权限](docs/data-guide.md)
- [当前验收与边界](docs/sandbox-verification.md)
- [本地沙箱 SDK 接入调研与维护需求](docs/sandbox-integration-review.md)
- [首期历史验收与 OWASP 映射](docs/verification.md)

```bash
python3 -m venv .cache/docs-venv
.cache/docs-venv/bin/pip install -r requirements-docs.txt
.cache/docs-venv/bin/mkdocs serve
.cache/docs-venv/bin/mkdocs build --strict
```

- [固定命令工具与 ASI05 边界](docs/command-guide.md)
- [当前沙箱 SDK 接入指南](docs/sandbox-guide.md)
- [固定命令需求与选型](docs/command-requirements.md)

## 支持边界

支持同步/异步 `ChatModel`、`@Tool`、显式 `ToolExecutor`、动态 `ToolProvider` 及立即返回工具。AI Services 支持 String 参数（另保留 `@MemoryId` 和 `InvocationParameters`）、String / AiMessage 及对应 CompletableFuture/CompletionStage 返回；工具支持单段文本结果和 JSON attributes。同步默认顺序执行，可显式开启并发；异步工具共用原会话预算。

严格缓冲流式交付一个通过最终检查的文本块，详见流式指南；实时逐 token、Reactive Publisher、多模态、复杂返回类型和原始模型 listener 明确拒绝。RAG / ChatMemory 需显式绑定 DataResource，才具备检索前和记忆读写授权；原有 opaque augmentor/raw memory 入口只有上下文与模型边界检查。数据权限依赖宿主对实际隔离分区的绑定，不提供数据库行 ACL 或存储事务。

SDK 保护通过受控构建器或装饰器接入的路径；原始模型、业务工具方法和直接 HTTP / SQL / 文件调用仍由宿主控制。工具执行后的拒绝不能撤销副作用。会话期限阻止新动作和结果交付，不保证安全终止任意已运行的 Java 代码。未做生产环境、长期负载或跨平台验收。

## 参考与许可证

设计参考 [microsoft/agent-governance-toolkit](https://github.com/microsoft/agent-governance-toolkit) 的适配器契约与治理语义，以及 [umuo/langchain4j-runtime-security](https://github.com/umuo/langchain4j-runtime-security) 的严格参数校验、可信上下文和故障关闭思路。本项目为独立实现，不是微软官方 Java SDK，不兼容原版 ACS manifest 或跨语言身份协议。

本项目使用 [MIT License](LICENSE)。参考归属见 [NOTICE](NOTICE)。

### ASI02 / ASI05 独立场景教学

[阅读路径、场景清单与预期结果](docs/security-demos.md)。`io.agt.examples.asi02` 与 `io.agt.examples.asi05` 分包，每个场景有独立 main 和中文注释。

```bash
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar security asi02
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar security asi05
```
