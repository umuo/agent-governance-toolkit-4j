# Web Agent 实验台

`examples-live` 现在是 Spring Boot 服务，`examples-live/frontend` 是独立 Next.js 项目。保留真实 OpenAI 兼容请求、文件工具与可选 Bash，增加网页连续对话、Markdown、SSE 运行事件、动作审批、停止和清空会话。

## 运行

要求 JDK 17+、Maven、Node.js 22.18+（或兼容的更新版本）、npm。

1. 在 `examples-live/.env.local` 配置 `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`、`AGT_WORKSPACE` 和工作区外的 `AGT_AUDIT_DIR`。新克隆仓库先复制 `.env.example`；本地文件不进入 Git 或 JAR。
2. 在仓库根目录构建并启动后端：

```bash
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 -pl examples-live -am verify
java -jar examples-live/target/agt-examples-live-0.6.0-SNAPSHOT.jar
```

3. 在另一个终端启动前端：

```bash
cd examples-live/frontend
npm ci
npm run dev
```

打开 [本地实验台](http://127.0.0.1:3000)。后端默认是 `127.0.0.1:8080`。API key 只在 Java 服务中使用，浏览器和 Next.js 不持有密钥。即使尚未配置模型也能启动服务查看页面，发起任务会得到可诊断的配置错误。

前端服务端可设置 `AGT_API_URL`（默认 `http://127.0.0.1:8080`，模板在 `frontend/.env.example`），通过 Next 路由原样转发 SSE、Cookie 和请求取消信号，不缓冲整个回复。修改后端端口时在后端配置中设置 `AGT_SERVER_PORT`，同时修改 `AGT_API_URL`。

生产构建方式为 `npm run build` 后 `npm start`。本次交付用于本机开发：Java 和 Next 均默认绑定回环地址。需要对外部署时须先接入真实身份认证、租户资源绑定、速率限制和 HTTPS；目前 HttpOnly/SameSite Cookie 只隔离本地浏览器会话，不代表用户认证。

原终端模式保留：

```bash
java -jar examples-live/target/agt-examples-live-0.6.0-SNAPSHOT.jar --cli
```

## 页面操作

- 输入任务后发送，支持 Enter 发送和 Shift+Enter 换行；回答支持标题、代码块、表格、列表、链接和 GFM，不执行模型提供的原始 HTML。
- 工具卡展示接收参数、执行中、完成或失败。参数和结果可展开查看，执行耗时来自真实调用。
- 选择“写入审批”后，写入/编辑动作等待浏览器批准或拒绝。完整参数在审批卡中展示；默认 30 秒过期，重复审批、过期审批和其他会话的审批都被拒绝。
- “停止”撤销当前 AGT 会话、请求取消模型流并拒绝待审批动作；下一次动作受撤销状态拦截。已发生的文件或脚本副作用不会回滚，也不保证终止宿主 JVM 中任意运行的 Java 代码。
- “新对话”清空服务端上下文并创建新的会话工作区，历史工作区文件保留在本地以便检查。
- 修改治理策略会清空上下文；单条消息对应一次新的 AGT 会话预算。页面显示当前回合预算和最近 150 条运行事件，完整治理记录按运行 ID 写入 JSONL。

## 两种流式语义

AGT 当前的严格治理契约要求完整内容检查，不能把原始 token 交付伪装成已经检查的输出。页面默认选择完整过程实时渲染，明确标注过程内容未检查；API 未指定 mode 时仍默认 strict。

| 模式 | 文本 | 工具参数 | 工具执行及结果 |
| --- | --- | --- | --- |
| 严格检查后交付 | 完整答案通过 OUTPUT 后，以 SSE 小块交付；不是模型原始 token 实时交付 | 聚合完整参数后，经 PRE_TOOL_CALL 授权/校验/审批才展示执行动作 | 受 AGT 控制；POST_TOOL_CALL 通过后才展示有效结果 |
| 完整过程实时渲染（页面默认） | 真实 Provider token 增量立即显示，标注未检查；各轮正文和 reasoning 按顺序保留；最终检查通过后为匹配的最终正文标注已检查 | 真实 function-call JSON 片段累积为参数卡，明确未检查 | 只执行完整且授权的工具调用，检查后的工具结果才展示 |

完整过程实时渲染属于实验模式：预览内容可能被后续检查拒绝，页面在失败/取消后仍保留过程内容，并标注未检查；历史展示不代表治理放行。因此它不具备严格模式的交付保证。页面只展示模型接口实际提供的 reasoning_content / PartialThinking，不构造或推断未返回的内部推理。严格模式不发布 reasoning 增量，不用字符串定时切分冒充模型 token。

模型桥接器将真实流聚合为 `ChatResponse` 供 `guardModel` 完整检查；`HostSession.run` 包围用户输入和最终输出，工具使用 `guardTool`。事件装饰器放在正确检查边界之外，只把授权后的参数和检查后的工具结果交给前端。工具参数碎片本身不触发执行。

## API

所有路径以 `/api/agent` 开头，浏览器通过 Next 同源代理调用。服务端使用 HttpSession 绑定运行和审批，客户端不能提供 agent/principal/tenant 权限或工作区路径。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/config` | 是否完成配置、模型名称和 Bash 模式，不含密钥 |
| GET | `/scenarios` | 由服务端提供的 ASI01–ASI10 目录及可运行状态 |
| POST | `/chat` | SSE 对话，JSON 为 `message, scenario, mode` |
| POST | `/approvals/{id}` | JSON 为 `{"approved":true}` 或 false，动作归属和期限由服务端校验 |
| POST | `/cancel` | 停止当前 Cookie 会话的运行 |
| DELETE | `/session` | 清空非运行中的会话；运行期间返回 409 |

`scenario` 为 `agent`、`read-only`、`approval`、`budget`，`mode` 为 `strict` 或 `preview`。未知值、空消息或超过 16000 字符的消息返回 400。同一会话不允许并发运行（409），工作线程和等候队列均有上限，达到容量返回 429。

SSE 的 `event` 是类型名，`data` 为 `{"type":"...","data":{...}}`。每条事件有递增 ID，不支持断线自动重放，避免自动重试造成重复副作用。

| 事件 | 含义 |
| --- | --- |
| `run_start` | 运行 ID、策略、输出模式、隔离工作区名称 |
| `model_start` / `model_complete` | 模型回合编号和是否请求工具，用于保留各轮过程 |
| `reasoning_delta` | 仅实时模式，接口实际返回的 reasoning 片段，无返回则无该事件 |
| `text_delta` | 仅实时预览模式，真实 token 文本片段 |
| `tool_delta` | 仅实时预览模式，按模型回合和工具 index 累积参数 |
| `tool_start` | 授权/审批通过后实际开始执行 |
| `tool_result` / `tool_error` | 检查后的结果或工具失败 |
| `approval_required` | 审批 ID、完整动作参数、绝对过期时间 |
| `governance` | 干预点、决策、原因和审批结果 |
| `answer_start` / `answer_delta` | 保留过程记录，接收已检查的最终答案；相同正文不重复渲染 |
| `budget` | 模型、工具、token 等真实预算快照 |
| `done` / `error` | 运行结束或失败；错误含运行 ID，服务端打印完整堆栈 |

## OWASP 场景扩展

目录名称参考 [OWASP 官方 Agentic Top 10 2026](https://genai.owasp.org/2025/12/09/owasp-top-10-for-agentic-applications-the-benchmark-for-agentic-security-in-the-age-of-autonomous-ai/)。当前可从页面运行 ASI02 的只读权限拒绝，以及 ASI05 的 Bash 审批/沙箱案例（需宿主启用 Bash）。另提供独立写入审批和预算策略。

其余 ASI 项目明确标记“待扩展”，不是已完成的漏洞复现或安全覆盖。后续新增场景时在服务端场景目录注册提示词、策略配置、合成测试资源及验收结果；客户端只选择场景，不能自行下发权限或执行策略。建议将目录逐步抽成独立 ScenarioRegistry，而非将攻击逻辑塞入 UI。

Bash 默认关闭。严格读取模式在部分 macOS 环境不可用时拒绝；`AGT_BASH_HOST_READS=true` 是明确允许宿主读取的模式，不提供机密文件读取隔离。页面显示这一配置，保持与 [真实 LLM 指南](live-agent-guide.md) 中的边界一致。

## 验证

后端测试使用本机 OpenAI SSE 协议夹具及真实 Spring HTTP，不请求收费模型；验证流式 JSON 参数聚合、严格/预览区别、文件副作用、会话隔离、审批归属/重放拒绝和工具结果检查顺序。前端协议测试覆盖 UTF-8 分片、CRLF、多事件帧和异常事件。

```bash
cd examples-live/frontend
npm test
npm run typecheck
npm run build
```

外部模型仍使用用户自己的 `.env.local`。不要把本机协议夹具结果视为对每个实际 Provider 的兼容验收。

### ASI05 批准后仍失败

审批只允许这个动作进入执行阶段，不会放宽沙箱。`approvalOutcome=approved` 后出现 `sandbox_unavailable` 表示原生后端无法启动所配置的沙箱；`sandbox_exit_denied` 表示命令非零退出；`sandbox_timeout` 表示超时。这些均不是审批未生效。

macOS 上当前 SDK 的严格读取模式可能不可用。保留 `AGT_BASH_HOST_READS=false` 时会故障关闭；不要为了消除报错自动切换权限。若宿主明确接受脚本可读取宿主文件的边界，才可自行配置 HOST 读取并重启。Web 工具注册保留反射包装内的原始 GovernanceException，终端记录完整原始异常，页面显示具体沙箱原因码。


实时过程按到达顺序展示，跨模型回合不覆盖历史，正文继续使用 Markdown。Reasoning 可折叠，默认展开。查看历史时上滚会暂停自动跟随，回到底部恢复。右侧 EVENT TIMELINE 保留治理和执行事件，连续同类 token 片段合并为一行（详情最多保留最近 6000 字符），避免 token 刷屏挤掉治理记录；主对话过程保留完整接收内容，受后端单次模型流大小上限约束。

### 工具失败原因

网页工具卡片自动展开错误，显示稳定错误代码、中文原因与处理建议。`workspace_path_denied` 表示文件工具拒绝绝对路径或越界路径，`file_not_found` 表示文件或父目录不存在，`file_access_denied` 表示操作系统拒绝访问。人工审批不会扩大文件工具的工作区范围。

治理拒绝或工具抛出不可恢复异常时中止，不再继续调用模型或其他工具；页面保留首次原因和执行过程。已完成的副作用不会回滚。未知异常仅展示固定说明，完整异常留在服务端日志，可按 runId 查找。Bash 命令正常结束但返回非零状态时，stdout、stderr 与退出码作为结构化工具结果先经过 `POST_TOOL_CALL` 检查。放行后，网页使用 `bash_command_failed`，直接显示检查后的 stderr，另列 stdout 和退出码，将完整检查后的报告返回 Agent 继续决策；普通非零退出不标记会话失败，也不清空上下文。页面标注“命令失败 · 已返回 Agent”；不再用泛化的权限/网络说明替代真实错误。若策略脱敏则仅展示脱敏后的内容；若拒绝则不交付原始输出。后续 Bash 调用仍需新的动作审批，并继续受预算约束。沙箱启动、超时、超限、权限或输出检查拒绝等治理失败仍按原始原因码中止。

如果命令使用 `2>/dev/null` 或其他方式丢弃 stderr，页面只能提示“没有捕获到 stderr”，不能还原或编造进程未返回的内容。可移除重定向后重新执行；不要自动重跑具有副作用的命令。

### Bash HOST 读取开关

推荐保持 `AGT_BASH_HOST_READS=false`，业务文件限工作区，原生后端运行时资源只读，网络继续禁止。右侧运行边界显示实际配置；严格沙箱不可用时明确中止，不自动放开宿主读取。

`AGT_BASH_HOST_READS=true` 只扩大 Bash 的读取范围，仍受系统权限限制；写入范围、网络禁止、动作审批和运行限额均不变。`HOME` / `~` 指向网页会话的工作区。独立文件工具依旧只接受工作区相对路径。

HOST 不提供宿主机密读取隔离，Bash 读出的内容可能返回给模型。完整能力对照、与宿主终端的差异、`ls -la` 验证和原因码排查见 [Bash 开关能力边界](live-agent-guide.md#agt_bash_host_reads)。配置修改后重启后端；页面的 Bash 模式来自后端实际加载的配置。
