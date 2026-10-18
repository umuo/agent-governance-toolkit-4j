# Agent 执行链路与多 Agent 可视化

同一个 Agent 可以被调用多次。`agentId` 表示稳定身份，`runId` 表示一次调用，`traceId` 串联同一任务，`spanId` 标识 Agent、模型请求、派生和工具节点。父 Agent 的一次调用派生子调用时，`parentRunId` 记录执行归属，`parentSpanId` 记录直接触发动作。

本次实现包括独立 `agt-observability` 模块、核心执行上下文与审计关联，以及 `examples-live` 的“多 Agent 执行链路 → 父子 Agent 追踪”页面。现有不启用追踪的应用保持原有会话与审计行为。

## 复用方案与取舍

使用 [OpenTelemetry Java](https://github.com/open-telemetry/opentelemetry-java)（Apache-2.0）API/SDK 1.67.0：Java 17 工程实际编译及传播测试通过。标准 API 提供 Span、上下文、线程包装与 W3C Trace Context；SDK 管理标识生成和采样，宿主可以接入自己的 SpanProcessor/Exporter。没有自行生成一套 trace/span ID 协议，也未引入完整观测平台服务。

- `agt-core` 不依赖 OpenTelemetry，只接收不可变 `ExecutionContext`。
- `agt-observability` 仅依赖 OTel API，SDK 由宿主配置；未配置有效 SDK 时明确失败，不能返回全零假链路。
- 示例使用 OTel SDK 与本机同步快照存储；没有外发遥测数据，也不要求第三方账号。
- Agent 属性使用 `agt.*` 命名，避免承诺尚在演进的 GenAI 语义约定兼容性。

标准说明：[W3C Trace Context](https://www.w3.org/TR/trace-context/)、[OTel Java API](https://opentelemetry.io/docs/languages/java/api/)。

## 身份、实例、执行与动作

| 字段 | 含义 | 来源 |
| --- | --- | --- |
| `TrustedContext.agentId` | 稳定 Agent 身份 | 宿主认证/注册入口 |
| `agentInstanceId` | 动态实例标识，同一实例可执行多次 | 宿主传入或每次调用默认生成 |
| `runId` | 一次 Agent 调用 | AgentTracer 新建 UUID |
| `parentRunId` | 派生它的 Agent 调用 | 同线程/异步上下文或远端关联元数据 |
| `traceId` | 一条任务链 | OTel SDK / W3C 上游上下文 |
| `spanId` / `parentSpanId` | 节点及直接父节点 | OTel SDK |
| `sessionId` | 治理会话 | HostSession |
| `actionId` | 具体治理动作 | 现有运行时 |

`ExecutionContext` 不是权限凭据。Trace 派生关系本身不授予子 Agent 父级权限；当前演示通过宿主显式父子会话给研究子 Agent `read`、核验子 Agent `send`，权限分别收窄，调用总量由根任务共享预算约束。外部传入关联头不能作为认证、租户隔离、审批或权限依据。

## SDK 接入顺序

给应用添加 `io.agt:agt-observability:0.6.0-SNAPSHOT`，宿主配置 OpenTelemetry SDK，然后传给 `AgentTracer`。以下代码假定 `telemetry`、`policy`、`auditSink` 来自应用配置：

```java
var tracer = new AgentTracer(telemetry);
try (var control = AgentControl.builder(policy)
        .audit(tracer.correlate(auditSink)).build()) {
    tracer.invokeAgent("research-agent", () ->
        tracer.governed(control, trustedIdentity, session ->
            tracer.operation("tool", "lookup", () ->
                control.runTool("lookup", Map.of(), arguments -> "合成查询结果"))));
}
```

`invokeAgent` 创建 run 和 Agent span；`governed` 打开携带执行信息的独立 HostSession；`operation` 包裹模型或工具动作。工具应把 `control.runTool` 包在 Span 内，拒绝审计才能关联到该工具节点，而不是事后只记录业务方法执行。

`correlate` 给当前活动动作的审计附上精确 Span。没有活动追踪 scope 时，会话中仍保留最初的执行关联，便于异步/关闭阶段回溯。显式传入 `openSession(identity, execution)` 或 `openDetachedSession(identity, execution)` 也可在不使用桥接模块时关联宿主已有链路。

启用执行关联的审计 schema 为 3，保留数据操作字段并增加 `execution` 对象；旧版构造器保留，未启用时继续输出 schema 1/2。构造器源码兼容不等于已经发布二进制的 ABI 兼容，升级后应重新构建使用方。

## 并行与远端传播

```java
tracer.invokeAgent("coordinator", () -> {
    Future<String> child = executor.submit(tracer.wrap((Callable<String>) () ->
        tracer.invokeAgent("worker", () -> doWork())));
    return waitFor(child);
});
```

必须在提交任务之前调用 `wrap`，在线程池入口恢复 OTel Context；退出后自动恢复原线程上下文。子 Agent 在工作线程内使用自己的身份与治理会话。OTel Context 传播与 AGT SessionScope 绑定是两件事：同一治理会话跨线程调用还需遵循已有 `bind` / `contextualExecutor` 规则，不能仅调用 `tracer.wrap` 就假设权限与会话自动生效。

跨进程发送时 `tracer.inject(headers)` 写标准 `traceparent` / `tracestate`，另写 `agt-parent-run-id` 以关联 Agent 调用归属；接收方 `try (var scope = tracer.extract(headers).makeCurrent())` 后调用 `invokeAgent`。W3C 头负责 Span 父子关系，额外字段仅是关联元数据；不透传用户、权限或凭据。无效 W3C 上下文不会继承远端 run。关联头不是签名，认证入口应按信任边界决定接受或移除，不能据此相信消息来源。

单个 Span 只有一个父节点。多个上游汇合或跨 trace 的长任务需在宿主侧使用 OTel Span Links / 业务 workflow ID；当前桥接没有实现持久任务调度、跨进程取消、跨进程权限委托或分布式预算；显式本机委托与共享调用预算见 [安全补齐计划](security-hardening-plan.md)。传播辅助方法有回归测试，本机演示没有假称验证两台真实服务或消息中间件。

## 演示流程与页面

父级使用固定调度，派生两个并行真实 LLM 子 Agent，不宣称父级模型自主规划：

1. `research-agent` 请求 `lookupFacts`，读取合成业务事实。
2. `review-agent` 请求 `deliverDigest(external-demo)`，宿主规则仅允许 `internal-demo`，发送前拒绝。
3. 父级等待两个子任务结束后汇总；某个子任务被拒绝不妨碍另一个正常完成。

模型可能不调用工具，因此页面根据实际 Span、审计和副作用计数显示结果；模型服务故障显示 `error`，不会将所有 GovernanceException 都算成护栏成功。

页面包括执行树、可选择节点、run/实例/Span 标识、直接关联审计与相对时间线。节点详情展示 Agent 任务输入与输出、模型框架请求与响应、实际 HTTP 请求体与响应体，以及工具请求参数、校验后参数和执行结果；支持折叠和大窗口查看。工具被提前拒绝时标明 `executed: false`，不会产生实际执行参数或伪造返回结果。旧历史没有采集正文，需要重新运行才能查看。拒绝以颜色和文字共同标注。运行期间每秒查询状态；可切换历史，刷新页面可恢复查看。当前图为 Span 执行树，派生动作与子 Agent 在同一树中展示；不是任意 DAG 编辑器。

## API 与存储边界

| API | 用途 |
| --- | --- |
| `POST /api/agent-traces` | 启动固定演示，202 返回 traceId |
| `GET /api/agent-traces` | 查询历史摘要，不返回全部审计正文 |
| `GET /api/agent-traces/{traceId}` | 查询一条完整链路，包含节点正文与审计 |

继续使用现有前后端启动方式，Maven `-pl examples-live -am` 自动构建新模块；模型配置仍由后端读取。前端代理增加 `agent-traces` 路径。

`agt.examples.traces-directory` 为后端链路快照目录，默认 `.cache/agent-traces`，相对后端启动目录解析；不能为空路径参数，不接受网页指定文件位置。可通过 `--agt.examples.traces-directory=<受控目录>` 设置。目录最多 100 条链路，每条最多 200 个 Span、1000 条审计；满额或写入失败明确拒绝启动/写入，不静默丢弃历史。清理由宿主管理。

本机存储按单进程设计，多个后端实例不能共用同一目录。生产多实例应接入集中式链路/审计存储。

本机示例最多并发一个根任务和两个子 Agent，额外启动返回 429。仅监听回环地址，示例 API 允许任意 Origin，并支持跨域预检；这些 API 不是生产多租户查询接口，暴露给其他用户前需补宿主认证和数据访问过滤。

快照包含标识、时间、状态、原因码、计数及脱敏后的节点请求与响应正文。正文保留 JSON 结构；不采集请求头、凭据或原始 Provider 错误响应，常见密钥字段和 Bearer 文本会遮盖，但这不等于完整的业务隐私识别。单条正文上限 64 KiB，每条链路正文累计上限 512 KiB，每节点最多 20 条正文，快照文件上限 2 MB；超长正文明确标注截断，截断内容是文本前缀，不保证为完整 JSON。文件采用临时文件 + 原子替换，同步确认文件写入；不声称断电持久、不可篡改或跨文件事务。进程重启将未完成链路标成 `interrupted`，不自动重放副作用。目录损坏明确报错。

“全局可追溯”仍取决于所有边界接入传播、持久存储与可靠审计。OTel 采样/Exporter 不自动等于完整安全审计；示例 always-on 采样且同步保存本机链路快照，生产系统应接入自己的可靠审计存储与链路后端。

## 本轮验收

2026-10-17：完整 Maven 回归 479 项，478 通过、1 项可选 Provider 测试跳过；新增追踪相关测试 8 项，覆盖并行父子关系、线程上下文清理、不同 run、权限隔离、精确拒绝审计、W3C 传播及无效上下文、未配置 SDK、历史恢复、模型异常不冒充拦截。最终节点关联调整后专项回归与打包通过。前端 13 项、类型检查与生产构建通过。

浏览器运行真实模型：一个父级、两个实际并行子 Agent，共 10 个 Span、3 次模型请求、23 条审计；事实读取 1 次，外部投递工具以 `tool_arguments_denied` 拒绝，实际投递 0。后端进程重启后同一 traceId 的节点、审计与计数均恢复。页面实测运行态更新、分支显示、节点选择和拒绝审计。见 [结构化验收证据](evidence/agent-tracing-verification.json)。

## Origin

所有示例 API 均允许任意 Origin。统一 CORS 配置覆盖 `/api/**`，Next 代理转发跨域预检和 CORS 响应头，`agt.examples.frontend-origin` 不再使用。工具授权、审批、沙箱、会话绑定与运行限制保持生效。实测任意 HTTPS 来源、localhost 和 `null` Origin 预检均通过，跨来源启动返回 202，见 [来源放开验收](evidence/origin-access-verification.json)。

## 节点请求与响应验收

2026-10-17：完整 Java 回归 491 项，490 通过、1 项可选 Provider 测试跳过；前端 15 项测试及生产构建通过。真实模型演示生成 10 个 Span，3 次模型调用均有实际 HTTP 请求体与响应体。工具成功结果包含真实返回值；拒绝结果标记 `executed: false`，没有实际执行参数。浏览器验证正文展开、大窗口查看、Esc 关闭与旧历史未采集提示。见 [正文采集验收证据](evidence/trace-bodies-verification.json)。
