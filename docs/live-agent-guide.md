# 真实 LLM 通用 Agent

网页模式现在是默认启动入口，见 [Web Agent 实验台](web-agent-guide.md)。下列命令使用 `--cli` 进入终端模式。

`examples-live` 是独立 Maven 模块，Java 包为 `io.agt.examples.live`。运行入口不包含预设模型回答或 Mock ChatModel：由配置的 OpenAI 兼容服务决定何时调用工具，LangChain4j 将真实工具结果回传，直到最终回答。现有 `examples` 继续提供确定性的离线教学场景。

## 目标与选型

验收目标：真实 HTTP 模型请求、多轮文件操作与结果回传、宿主工作区绑定、ASI02 工具权限/参数/动作审批、ASI05 原生沙箱、有限会话预算、JSONL 审计。一次启动持续对话，每个用户回合可多轮调用工具。最近 60 条消息保留在进程内存中；每回合使用新的 AGT 会话预算。目前不提供重启后的持久记忆、逐 token 流式输出或 Windows Bash。

复用项目已锁定的 LangChain4j 1.20.0，而不是再实现一套工具调用协议。官方 [AI Services](https://docs.langchain4j.dev/tutorials/ai-services/) 和 [Tools](https://docs.langchain4j.dev/tutorials/tools/) 提供模型—工具—模型循环；项目受控构建器提供生命周期治理。使用现有 `agt-sandbox` 执行 Bash，不使用无隔离的 ProcessBuilder。自行实现的部分是工作区文件工具、宿主配置及案例装配，没有引入新的 Agent 框架。

## 构建与启动

在仓库根目录运行，JDK 17+、Maven 3.6.3+：

```bash
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 -pl examples-live -am verify
# .env.local 已为本地开发创建；新克隆仓库时从模板复制：
# cp examples-live/.env.example examples-live/.env.local
# 编辑 examples-live/.env.local，填写 LLM_API_KEY 和 LLM_MODEL。
java -jar examples-live/target/agt-examples-live-0.6.0-SNAPSHOT.jar --cli
```

启动后输入任务，例如 `创建 hello.txt，内容为 hello，再编辑成 hello agent 并读取确认`，然后继续追问。无命令行参数时默认 `agent` 场景，也可以传 `approval`、`read-only` 或 `budget`。仍可在场景参数后附带首条任务，任务完成后继续对话。

- `/clear`：清空当前上下文。
- `/exit` 或 EOF（macOS/Linux 下 Ctrl+D）：退出。
- 治理拒绝或调用失败：清空不完整的工具历史，输出原因，继续接受下一条输入。

终端按真实顺序显示 `[模型]`、`[工具 →]` 参数、`[工具 ←]` 结果与耗时、`[审批]`、`[AGT]` 和每轮预算。执行轨迹的参数/结果摘要最多展示 1200 字符并清理终端控制字符；审批展示完整 JSON 参数，便于核对整个动作；完整工具结果仍由 AGT 检查后回传模型。这些是实际事件，不展示模型隐藏推理。工具内容只显示到本地终端，JSONL 审计仍保存摘要。

### 本地私密配置

`examples-live/.env.local` 被 Git 忽略，`.env.example` 是可提交模板，不含真实密钥。`.env.local` 不进入 JAR。查找顺序：显式 `AGT_ENV_FILE` → 当前目录 `.env.local` → 当前目录 `examples-live/.env.local`。不自动加载 `.env.example`。系统环境变量覆盖文件值，包括显式空值。

支持 `KEY=value`、可选 `export`、单/双引号和整行 `#` 注释；不执行 shell、不做 `$VAR`/`~` 展开、不解析行尾注释。文件内 `AGT_WORKSPACE` 和 `AGT_AUDIT_DIR` 的相对路径基于配置文件目录；系统环境变量中的相对路径基于启动目录。其他目录启动时可以指定：

```bash
AGT_ENV_FILE=/path/to/examples-live/.env.local java -jar /path/to/agt-examples-live-0.6.0-SNAPSHOT.jar --cli
```

API key、模型名称、工作区必须显式配置。本地兼容服务如果要求非空 key，可以使用该服务约定的占位值。模型必须支持 OpenAI Chat Completions 的 function calling；没有自动 Provider 切换。工作区不存在时自动创建，审计目录必须在工作区外。

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `AGT_ENV_FILE` | 自动查找 | 可选，显式指定本地配置文件；指定后文件必须存在 |
| `LLM_BASE_URL` | `https://api.openai.com/v1` | OpenAI 兼容 API 根地址，包含 `/v1` |
| `LLM_API_KEY` | 必填 | 仅用于模型请求，不打印配置或 HTTP 原文 |
| `LLM_MODEL` | 必填 | 服务提供的工具调用模型 |
| `AGT_WORKSPACE` | 必填 | 文件及 Bash 工具绑定的目录 |
| `AGT_AUDIT_DIR` | `.cache/live-audit` | 每次运行创建新的 JSONL 文件；不能位于工作区内 |
| `AGT_ENABLE_BASH` | `false` | `true` 注册 Bash；其他值仅接受 `false` |
| `AGT_BASH_HOST_READS` | `false` | 仅改变 Bash 的读取范围；`true` 使用 HOST 读取，详见下方能力边界。修改后重启后端 |

单次 HTTP 超时 60 秒、自动重试 0 次。普通场景最多 20 次模型、40 次工具调用、300 秒会话；Bash 单次最多 10 秒、32768 字节输出。治理期限会阻止后续动作和结果交付，不承诺立即中断已经发出的同步 HTTP 请求。

## 工具

| 工具 | 参数 | 行为 |
| --- | --- | --- |
| `readFile` | `path` | 读取 UTF-8 普通文件，最多 32768 字节 |
| `writeFile` | `path, content` | 创建或覆盖文件；父目录必须存在 |
| `editFile` | `path, oldText, newText` | 精确替换唯一匹配，空匹配、无匹配、多匹配拒绝 |
| `listFiles` | `path` | 列出目录和文件，可使用 `.` |
| `searchFile` | `path, query` | UTF-8 文件内按字面量搜索，返回路径、行号、内容 |
| `Bash` | `command` | 可选的真实 Bash 脚本，必须通过人工审批和原生沙箱，读取模式由宿主配置 |

文件路径仅接受工作区相对路径，拒绝越界、绝对路径及符号链接。搜索不跟随链接、深度最多 8、访问最多 2000 个条目、最多 100 条结果；单文件大小受限，非 UTF-8 文件跳过，达到结果上限返回标记。文件工具在 JVM 中运行，要求工作区由宿主独占，不能抵御另一个进程并发替换目录造成的路径竞态，也不能隔离宿主预先放入的硬链接。应使用新建专用工作区，不放置密钥和不相关文件。

## `AGT_BASH_HOST_READS` 的能力边界

推荐保持 `AGT_BASH_HOST_READS=false`：以工作区为业务文件边界，只读开放原生后端声明的系统运行时，写入限工作区与 SDK 私有临时目录。严格后端不可用时拒绝执行，不能自动改成 HOST。

这个开关只改变 **Bash 子进程的文件读取范围**，不负责启用工具，也不代表获得宿主终端的全部能力。默认 `false`；要使用 Bash，还必须设置 `AGT_ENABLE_BASH=true`，当前场景具有 shell 执行权限，并逐次通过动作审批。`.env.local` 修改后需重启后端；系统环境变量优先于文件值。

| 能力 | `false`：DECLARED_ONLY | `true`：HOST |
| --- | --- | --- |
| 文件读取 | 限工作区、声明的 `/bin`、`/usr/bin` 及原生后端允许的运行时资源 | 扩大为宿主文件读取，仍受运行后端的系统用户权限、系统隐私控制等限制 |
| 绝对路径读取 | 仅允许路径落在沙箱可读范围内 | 可以尝试读取工作区外的绝对路径；文件必须存在且系统允许读取 |
| 创建、覆盖、删除文件 | 限工作区与 SDK 私有临时目录等沙箱授权位置 | 同左；不允许任意写入宿主目录 |
| 网络 | 禁止，包括访问本机网络服务 | 同左；不能因此使用 curl 下载或访问外部服务 |
| 工具审批与 AGT 权限 | 每次 Bash 调用仍需审批和权限检查 | 同左；批准只允许尝试执行，不能扩大沙箱权限 |
| 运行限额 | 单次 10 秒、最多 32768 字节输出，另受会话预算限制 | 同左 |
| `readFile`、`writeFile`、`editFile`、`listFiles`、`searchFile` | 始终只接受工作区相对路径，拒绝越界和符号链接 | 同左；这些是独立的 JVM 文件工具，不受此开关影响 |

**HOST 模式不提供宿主机密文件的读取隔离。** 如果运行后端的系统用户能够读取某个密钥或配置文件，获批的 Bash 脚本也可能读取它。禁止 Bash 网络访问并不阻止工具结果经应用返回给 LLM 或显示在页面上；不能依赖网络禁止来保护已被读取的秘密。

当前 SDK 的 macOS 严格策略还允许 `/System`、`/usr`、`/bin`、`/sbin` 等运行时根以及若干系统资源读取；这并非精确到每个二进制的最小白名单，尤其不能把 `/usr/local` 当作受保护的机密存放区。因此这里的“workspace 边界”指工作区以外的业务目录受限，不代表只允许读取 workspace 中的每一个字节。严格 profile 的兼容性和更细的运行时白名单需由原生后端支持，不能用 HOST 读取替代。

### Bash 与宿主终端的差异

- 当前目录是本轮工作区；`pwd` 显示工作区路径。网页会话使用各自的工作区，不能假设等于仓库目录。
- SDK alpha.2 的严格环境将 `HOME` 和 `USERPROFILE` 指向每次执行的私有临时目录，执行后回收；`~` 也不指向宿主用户目录。当前工作目录仍是配置的工作区，持久文件应使用工作区相对路径。开启 HOST 不会恢复宿主 `HOME`。
- Bash 以 `/bin/bash --noprofile --norc -c` 执行，不加载宿主登录 shell 的 profile、rc、alias 或 shell function。
- 子进程使用 SDK 清理后的环境，只保留 PATH 等允许的变量；不自动继承 `LLM_API_KEY` 等私密配置。命令是否能通过名称找到仍受 PATH 影响；可用 `/bin/ls` 这类明确路径排查。
- HOST 不会安装缺失的软件，不保证每个二进制或依赖的系统服务都能在沙箱中运行，也不绕过操作系统权限。原生后端不可用时拒绝执行，不回退到无沙箱进程。
- 工作区内的修改和删除是真实副作用；后续工具失败、预算耗尽或输出被拒绝都不会回滚。

### 最小验证与失败定位

启用 Bash 后，可以在页面提交以下命令并批准；它们只读取工作区或创建测试文件：

```bash
pwd
printf 'HOME=%s\n' "$HOME"
ls -la
/bin/ls -la
printf 'sandbox-ok\n' > sandbox-check.txt
/bin/cat sandbox-check.txt
```

本机 macOS 验收已在 HOST 模式下通过真实 `BashTool` 验证 `ls -la`、工作区写入/读回、HOME 指向并回收 SDK 私有临时目录和越界写入拒绝；这不代表所有宿主环境均有相同兼容性。若 `ls -la` 失败，不能仅凭失败就判定 HOST 不支持 ls：需要对照该轮具体错误代码和服务端日志。`ls` 失败但 `/bin/ls` 成功时，优先检查 PATH。

| 原因码 | 含义与排查 |
| --- | --- |
| `sandbox_unavailable` | 原生沙箱无法启动；检查后端能力与运行环境。当前 SDK 的严格读取在部分 macOS 环境不可用，HOST 是显式选择，不能自动降级 |
| `sandbox_policy_unsupported` | 原生后端不支持当前隔离策略 |
| `bash_command_failed` | 网页直接展示经过工具结果检查的真实 stderr，并分别展示 stdout、数字退出码；stderr 被脚本丢弃时明确提示未捕获，不编造原因。报告返回 Agent 继续决策，不中止本轮；后续 Bash 仍需逐次审批。非零退出本身不是权限拒绝 |
| `sandbox_exit_denied` | 固定命令后端默认接口在非零退出时的拒绝码；Bash Agent 使用结构化报告接口，将进程输出先送入 POST_TOOL_CALL 检查再展示 |
| `sandbox_timeout` / `sandbox_output_limit` | 超时或输出超限；缩短任务或减少输出 |
| `workspace_path_denied` | 独立文件工具拒绝绝对路径或越界；调整 HOST 不会改变它的规则 |
| `session_failed` | 本轮已因先前错误终止；查找首次错误，不要把它当作新的文件权限问题 |

## AGT 场景

以下命令共享同一真实模型入口。LLM 是否选择相应工具存在随机性；通过审计和文件结果确认实际发生的动作，不能仅凭最终自然语言判断治理生效。

```bash
JAR=examples-live/target/agt-examples-live-0.6.0-SNAPSHOT.jar
# ASI02：请求写入，fs.write 权限缺失，写入前拒绝
java -jar "$JAR" --cli read-only '调用 writeFile 创建 denied.txt，内容是 denied'
# ASI02：写入/编辑必须人工审批，输入 yes 才允许
java -jar "$JAR" --cli approval '创建 approved.txt，内容是 approved'
# 预算：只允许一次模型调用和一次工具调用；写入后再次请求模型会被拒绝
java -jar "$JAR" --cli budget '创建 budget.txt 后读取确认'
# ASI05：显式启用 Bash，仍需在真实终端审批
AGT_ENABLE_BASH=true java -jar "$JAR" --cli agent \
  '使用 Bash 执行 printf sandbox-ok > shell.txt，然后 readFile 验证'
```

`read-only` 权限固定为 `fs.read`，即使启用了 Bash，也没有 shell 执行权限。`approval` 只对写入/编辑加审批；Bash 在所有场景都需要审批。审批复用同一个 stdin 读取队列，终端和 IDE 标准输入均可使用；输入必须恰好为 `yes` 才批准。EOF 时拒绝，默认审批超时 30 秒；拒绝或过期后清空上下文，仍可继续输入新任务。审批显示实际工具参数，包含待写内容和脚本；只在可信终端运行。

ASI05 的 Bash 使用 `ReadPolicy.DECLARED_ONLY`、`NetworkPolicy.DENY` 和工作区写权限，清理子进程环境，不向脚本传递 LLM key。`AGT_BASH_HOST_READS=true` 时使用宿主明确授权的 HOST 读取模式，脚本可以读取工作区外文件，不提供机密读取隔离；这是显式配置，不是自动降级。`/bin/bash` 由宿主指定；运行时库读取由 SDK 原生后端处理。脚本可使用内建命令和宿主声明只读的 `/bin`、`/usr/bin` 系统工具；其他安装位置的可执行文件可能因严格读取策略不可用。原生后端不支持或执行失败会拒绝，不自动改成 HOST 读取或普通进程。当前 SDK 在不同 OS/运行环境下的严格读取能力有差异，具体边界见 [沙箱指南](sandbox-guide.md)。

通用脚本不是 `FixedCommand`：`guardCommand` 只接受固定模板/枚举参数，不能拿它包装任意脚本。本案例把 Bash 当作普通受控工具，再显式调用 `SandboxCommandBackend`；脚本授权依赖动作审批，副作用隔离依赖原生沙箱。允许的脚本仍能修改/删除工作区内容，已完成的副作用不会因后续预算或输出拒绝而回滚。

本次 macOS 验收中，严格读取返回 `sandbox_unavailable`；显式 HOST 读取模式可执行 Bash，并阻止工作区外写入。需要在该环境运行 Bash 案例时：

```bash
AGT_ENABLE_BASH=true AGT_BASH_HOST_READS=true java -jar "$JAR" --cli agent \
  '使用 Bash 执行 printf sandbox-ok > shell.txt，然后 readFile 验证'
```

## 代码与审计阅读路径

1. `config/AgentConfig` 读取模型和宿主配置，不把身份/权限作为 LLM 参数。
2. `config/AgentPolicy` 为每个工具声明权限、闭合参数规则和审批要求，未注册工具默认拒绝。
3. `GeneralAgentDemo` 创建官方 OpenAI 客户端、审计、AgentControl、可信会话。
4. `assistant()` 通过受控 AI Services 注册真实工具，工具循环的每次模型/工具调用均经过 AGT。
5. `tools/WorkspaceTools` 与 `tools/BashTool` 执行真实副作用。

内存上下文通过受控构建器接入，每轮读取/写入仍进行模型及会话边界检查；这里没有单独配置 DataResource 数据权限。

本地示例身份为宿主固定的 `local/console-user`；服务化时从认证结果创建 TrustedContext，并按用户/租户绑定独立工作区。模型、文件内容或工具参数都不能自行提升权限。System prompt 提醒模型将文件作为不可信数据，但它本身不是强制策略。

模型、工具及审批读取异常会在终端输出 `[异常]`、原始异常消息、完整堆栈、`Caused by` 和 suppressed 异常，不受工具摘要的 1200 字符限制。模型诊断在 AGT 将底层异常转换为 `model_failed` 之前记录，所以 HTTP 状态和服务端错误消息不会只剩一个治理原因码。不会开启 HTTP 请求头日志；JSONL 审计仍只保存治理事件。若需要留存终端诊断，可在启动命令后使用 `2>&1 | tee .cache/live-runtime.log`，日志包含服务端错误和工具内容，应保存在本地。

运行打印审计文件位置与预算。审计包含生命周期干预点、策略版本、动作摘要、决策和原因码，不记录原始文件内容。工具拒绝有时在 AI Services 最终体现为 `session_failed`，原始 `permission_denied` 或 `approval_denied` 在对应审计记录中；预算原因码为 `model_budget_exceeded` / `tool_budget_exceeded`。

## 验收

默认测试包括 `.env.local` 解析与覆盖、连续对话上下文保留、清空及拒绝后继续。模型测试只访问本机 HTTP 协议夹具，不收费，验证完整工具 schema、模型—写文件—模型—读文件—模型循环、只读和审批拒绝无副作用、预算不撤销已写文件、路径与编辑边界。原生 Bash 测试验证执行和越界写保护，严格后端不可用时验证故障关闭。

真正的外部模型验收必须配置自己的端点和凭据，再显式启用：

```bash
AGT_LIVE_TEST=true mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 \
  -pl examples-live -am -Dtest=LiveProviderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

该验收会产生模型费用，在 JUnit 临时目录中要求模型依次写入、编辑、读取，然后断言真实文件内容与工具次数。默认跳过，不把本机协议测试冒充外部 LLM 运行结果。
