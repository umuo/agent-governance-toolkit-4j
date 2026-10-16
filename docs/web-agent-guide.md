# Web Agent 实验台

`examples-live` 现在是 Spring Boot 服务，`examples-live/frontend` 是独立 Next.js 项目。保留真实 OpenAI 兼容请求、文件工具与可选 Bash，增加网页连续对话、Markdown、SSE 运行事件、动作审批、停止和清空会话。

## 入门案例：先从这里看

首页按文件夹列出案例，默认打开最简单的普通聊天。入门案例只用普通 JSON 请求，每次独立问答，不启用历史记忆、SSE、文件工具、Bash、审批或运行观察。页面“查看最简代码”读取 JAR 内实际打包的 Java 文件；护栏案例还可展开共用配置。

| 文件夹 | 案例 / 源文件 | 观察重点 |
| --- | --- | --- |
| 原生 LangChain4j | `PlainChatExample.java` | `AiServices.builder(...).chatModel(...).build()` |
| 原生 LangChain4j | `CalculatorExample.java` | 加一个纯计算 `@Tool`，观察工具循环 |
| 安全护栏入门 | `InputGuardExample.java` | 输入包含“导出全部客户”时，模型调用前拒绝 |
| 安全护栏入门 | `OutputMaskExample.java` | 最终答案的演示号码 `13800138000` 替换为 `138****8000` |
| 安全护栏入门 | `ToolPermissionExample.java` | 模型请求 `readInternalNote`，无 `notes.read` 权限时不执行工具 |
| 完整 Agent | 工作区实验台 | 原有文件、审批、流式、预算与审计演示 |

代码均在 `examples-live/src/main/java/io/agt/examples/live/web/simple/`。阅读顺序为 `PlainChatExample` → `CalculatorExample` → `SimpleExamplesConfiguration` → 三个护栏案例 → `SimpleExamplesController`。护栏案例通过 Starter 的 `GovernedAiServices.create(...)` 创建服务，自动管理会话；三者共享同一组规则与无权限演示身份。输入检查为字面匹配、脱敏只替换指定演示号码，不代表语义检测或通用隐私识别。工具案例使用固定便签文本，没有真实内部资料。模型是否选择调用工具由模型决定，页面推荐提问用于触发该路径。

简单案例只需要 `LLM_API_KEY`、`LLM_MODEL` 与可选 `LLM_BASE_URL`，不要求工作区配置。原始案例不使用 AGT 护栏。共用运行时的审计为显式空实现，便于聚焦最小接入；需要完整审计时使用工作区实验台。

前端使用非默认端口时，可通过 Spring 属性 `agt.examples.frontend-origin` 配置允许的完整来源（默认 `http://127.0.0.1:3000`，也接受本机 3000/8080 的常用 localhost 来源）。

接口为 `GET /api/examples`（目录）、`GET /api/examples/{id}/source`（实际源码）、`POST /api/examples/{id}/chat`（`{"message":"..."}`）。对话返回 `status, answer, code`；`status=blocked` 表示本次护栏拒绝，下次请求会使用新会话。未知 ID 返回 404，空白或超长输入返回 400；Provider 失败返回 502 且不回传原始异常。页面只保留本地展示记录，切换案例会清空该展示。

## ASI02 网页复现

左侧“ASI02 工具滥用”文件夹已提供十个可运行案例，每个独立定义业务工具、策略和状态快照，源码位于 `examples-live/src/main/java/io/agt/examples/live/web/asi02/`。

| 案例 | 观察实际效果 |
| --- | --- |
| 批量删除 | 客服无删除权限，防护路径的客户列表不变 |
| 跨租户读取 | 参数绑定与业务对象归属共同检查，内存文档表返回 not-found |
| 邮件滥发 | 收件人白名单拒绝发送，内存发件箱为空 |
| 高成本调用 | 单会话工具预算最多允许三次，按工具实际进入次数计算模拟积分 |
| 工具链外传 | 内部便签可读，但不允许向外部模拟接收器发送 |
| 高风险审批 | 有效收款人、金额先校验，再按页面选择的本次模拟决定批准或拒绝 |
| 工具结果注入 | 查询返回恶意指令，不改变后续发送动作的权限；不是 MCP 协议实现 |
| 故障熔断 | 本轮三次模拟服务失败后，连接器打开本轮熔断；不是分布式限流器 |
| 网络隔离 | 原生 Java 访问本机接收器，防护路径实际用沙箱固定 curl 与 DENY 网络策略 |
| 拦截过晚 | POST_TOOL_CALL 拒绝返回值，真实临时文件仍已修改；不伪装回滚 |

点击“运行真实 LLM 对照”后，后端依次为两条路径创建独立业务状态，使用同一条用户输入分别请求已配置的真实 LLM。没有离线模型开关，不生成预设工具调用，不在模型失败时回退。模型不提出工具调用时，显示“未触发工具调用”，不能据此判断攻击或防护成功；多次真实生成也可能不同，页面保留双方实际调用和状态供检查。

默认使用推荐提问，也可修改。原生路径不接入 AGT，防护路径复用 Starter 工厂与独立 HostSession；工具内部的对象授权、实验内熔断和隔离仍由各案例明确实现，不把它们归为 SDK 自动提供的能力。审批选项仅模拟当前实验宿主的批准/拒绝，模拟批准最多使用一次；真实审批回执仍由 SDK 绑定当次动作，不提供审批人认证系统。

`POST /api/examples/asi02/{id}/run` 请求为 `{"message":"...","approval":"deny"}`，approval 只允许 `deny` 或 `allow`，两条路径返回 `original` 与 `guarded`。每个结果包含 `status, code, answer, modelCalls, before, after, requestedCalls, audit`。证据来自实际工具状态和 SDK 审计，候选调用不等于已执行动作。返回状态区分 `done`、`blocked`、`error` 和 `unavailable`；Provider 错误不算护栏成功。

`GET /api/examples/asi02` 返回十个目录项，`GET /api/examples/asi02/{id}/source` 展示实际打包源码。参数不允许上传策略、工具、可信身份或任意执行路径。实验数据只存在于内存、宿主创建的临时文件或本机 HTTP 接收器；临时资源在每条路径结束时关闭。

网络隔离演示当前校验 macOS 与固定 `/usr/bin/curl`。原生能力不可用时明确显示不可用，不能把跳过计为隔离通过。使用 HOST 读取策略仅为运行固定程序，不声称对宿主机密文件提供读取隔离。所有网页案例都需要真实模型配置，运行成本包含每条路径可能产生的多轮 LLM 请求。

## 运行

要求 JDK 17+、Maven、Node.js 22.18+（或兼容的更新版本）、npm。

1. 在 `examples-live/.env.local` 配置 `LLM_API_KEY`、`LLM_MODEL` 和可选的 `LLM_BASE_URL`。运行完整 Agent 时，再配置 `AGT_WORKSPACE` 和工作区外的 `AGT_AUDIT_DIR`。新克隆仓库先复制 `.env.example`；本地文件不进入 Git 或 JAR。
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

模型桥接器将真实流聚合为 `ChatResponse`，通过 Starter 的 `GovernedAiServices.create(...)` 注册原生模型、记忆和工具；输入、模型、工具与最终输出的治理由工厂统一装配。工具开始事件在授权后的执行器中发布，结果事件在受控 `afterToolExecution` 回调中发布。工具参数碎片本身不触发执行。

## Starter 装配与配置

`examples-live` 直接依赖 `agt-langchain4j-spring-boot-starter`。每轮策略、审计文件和浏览器审批都不同，因此 `WebAgentRuntimeFactory` 为每轮建立一个独立 Spring 上下文，注册宿主 `AgentControl`、`TrustedContextResolver` 并启用 Starter 自动配置，取得 `GovernedAiServices`。关闭该上下文会销毁本轮运行时。不同浏览器和不同轮次不会共享运行时或预算。

为支持停止操作及预算展示，Web 宿主仍显式开启 `HostSession`；Starter 工厂复用已有会话，不创建第二套预算。可信身份与权限由服务器根据会话和场景生成，浏览器输入不参与授权。CLI 继续使用基础 SDK，不启动 Spring 上下文。

独立上下文继承宿主 Spring Environment，原生服务的容量可在 `application.yml` 或通过 Spring 环境变量配置：

```yaml
agt:
  langchain4j:
    max-characters: 1048576
    max-events: 10000
    publisher-prefetch: 32
```

默认值、范围与覆盖方式见 [Spring Boot 接入](spring-boot-guide.md)。这些属性是 Starter 服务配置；本例使用同步服务返回最终答案，页面 `strict` / `preview` 仍由模型桥接器控制，`delivery` 和 Publisher 预取不切换页面模式。关闭 `agt.langchain4j.enabled` 后，本例不能创建受治理服务，任务会失败。

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

文件不存在、无效路径格式、非普通文件、文件大小超限、无效 UTF-8、编辑匹配失败和空搜索等已知文件错误作为结构化结果经过 `POST_TOOL_CALL` 检查后返回 Agent；不会将会话标记为失败或清空上下文，Agent 可以纠正参数继续调用工具。工作区根目录使用 `listFiles(".")`，`workspace` 表示根目录下的同名子目录。路径越界和符号链接访问仍会拒绝。

治理拒绝或工具抛出不可恢复异常时中止，不再继续调用模型或其他工具；页面保留首次原因和执行过程。已完成的副作用不会回滚。未知异常仅展示固定说明，完整异常留在服务端日志，可按 runId 查找。Bash 命令正常结束但返回非零状态时，stdout、stderr 与退出码作为结构化工具结果先经过 `POST_TOOL_CALL` 检查。放行后，网页使用 `bash_command_failed`，直接显示检查后的 stderr，另列 stdout 和退出码，将完整检查后的报告返回 Agent 继续决策；普通非零退出不标记会话失败，也不清空上下文。页面标注“工具失败 · 已返回 Agent”；不再用泛化的权限/网络说明替代真实错误。若策略脱敏则仅展示脱敏后的内容；若拒绝则不交付原始输出。后续 Bash 调用仍需新的动作审批，并继续受预算约束。沙箱启动、超时、超限、权限或输出检查拒绝等治理失败仍按原始原因码中止。

如果命令使用 `2>/dev/null` 或其他方式丢弃 stderr，页面只能提示“没有捕获到 stderr”，不能还原或编造进程未返回的内容。可移除重定向后重新执行；不要自动重跑具有副作用的命令。

### Bash HOST 读取开关

推荐保持 `AGT_BASH_HOST_READS=false`，业务文件限工作区，原生后端运行时资源只读，网络继续禁止。右侧运行边界显示实际配置；严格沙箱不可用时明确中止，不自动放开宿主读取。

`AGT_BASH_HOST_READS=true` 只扩大 Bash 的读取范围，仍受系统权限限制；写入范围、网络禁止、动作审批和运行限额均不变。`HOME` / `~` 指向每次 Bash 执行的 SDK 私有临时目录并在结束后回收；当前工作目录仍是网页会话工作区。独立文件工具依旧只接受工作区相对路径。

HOST 不提供宿主机密读取隔离，Bash 读出的内容可能返回给模型。完整能力对照、与宿主终端的差异、`ls -la` 验证和原因码排查见 [Bash 开关能力边界](live-agent-guide.md#agt_bash_host_reads)。配置修改后重启后端；页面的 Bash 模式来自后端实际加载的配置。
