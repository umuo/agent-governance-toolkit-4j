# 本地沙箱 SDK 接入调研与维护需求
本页保存接入前调研结论；当前 v0.6 已基于发布制品完成可选适配，见 [当前接入指南](sandbox-guide.md)。下文“未实现适配器”描述原调研阶段，不代表当前状态。

日期：2026-10-14。对象：`io.github.umuo:agent-sandbox-sdk:1.0.0-SNAPSHOT`，源码提交 `26b86716a70ea6180358f1c05b2fcb1203822720`。调研时工作区干净，未修改 sandbox-demo 仓库。目标系统为 Java AGT v0.5。

## 结论

可以作为 AGT 的可选进程隔离后端，值得复用。但当前不能直接把它标记为跨平台生产安全执行器。本机严格读取模式失败，取消和回收契约也不足以确认资源已经释放，应先完成下面的接入门槛。

SDK 与 AGT 职责互补：AGT 决定身份、权限、参数、改写、审批、预算与交付；SDK 执行操作系统文件/网络/进程限制。进入 SDK 不意味着跳过工具治理，最终输出检查也不替代启动前权限检查。

## 架构与兼容性

| 项目 | 观察与判断 |
|---|---|
| Java / 制品 | Java 8 编译基线，AGT Java 17 可引用；MIT 许可证；依赖 JNA/JNA-platform 5.19.1。源码兼容编译成功，尚未完成 Maven 消费与全版本依赖回归。 |
| SDK 入口 | SandboxClient.execute(SandboxRequest)，同步阻塞，SandboxRunner 可替换；现有线程中断路径可用于初步接入，但没有执行句柄或取消后的回收确认。 |
| macOS | sandbox-exec + Seatbelt；文件/网络权限由 profile 限制，后代回收依赖 Java 观察。实际 HOST 模式的写隔离和网络拒绝已验证，DECLARED_ONLY 本机失败。 |
| Linux | bubblewrap mount/PID/user 等 namespace，加 seccomp socket 控制；未在本机运行 Linux 后端，不能宣称已验证。 |
| Windows | 默认受限 Token + Job Object；默认只支持 HOST 读取、ALLOW 网络；可选专用账户后端支持网络拒绝，仍不能提供声明目录读取隔离。未运行 Windows 后端。 |
| 容量 / 输出 | 逐请求超时、stdin/stdout/stderr 大小限制；stdout/stderr 各有额度。无 SDK 全局有限队列；Linux/macOS 缺少内建 CPU/内存/PID/磁盘硬限额。 |
| 失败行为 | 缺少/拒绝后端抛异常，没有普通 ProcessBuilder 降级；能力描述与实际请求可执行性仍需分别检查。 |

跨平台策略不能静默变弱。要求 `DECLARED_ONLY + DENY` 的应用在 Windows 默认后端应明确拒绝；不能为了兼容改成 HOST/ALLOW。Linux/macOS 支持标记也不能代替实际策略预检。

平台机制的参考：[bubblewrap 官方安全边界](https://github.com/containers/bubblewrap#sandbox-security)、[Windows Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)。bubblewrap 的隔离取决于调用方构造的参数和策略，不能只凭使用该组件得出完整隔离结论。

## 本轮实际证据

在 macOS 15.5 arm64 / GraalVM CE 21.0.2 本机，将 SDK 当前全部 main 源码编译到临时输出（`--release 8`），JNA 依赖使用已有 all.jar；未使用旧 SDK class 代替当前源码。编译成功。另在 Zulu JDK 17.0.11 和 21.0.4 重跑四种读取/网络组合，得到相同结果：基础 ready，DECLARED_ONLY 两种模式失败，HOST 两种模式成功。运行固定 printf/sh/sleep/curl 探针，仅访问自建合成 canary、临时工作区和本机回环服务，无真实凭据或业务数据。

| 探针 | 结果 |
|---|---|
| SandboxClient.status().ready() | true |
| DECLARED_ONLY / ALLOW，运行固定 printf | 生成 profile 的预检失败：SandboxBackendUnavailableException，exit 134；目标命令未进入执行。 |
| DECLARED_ONLY / DENY，同上 | 同样失败；没有普通执行降级。 |
| HOST / ALLOW，同上 | 成功。 |
| HOST / DENY，同上 | 成功。 |
| HOST 模式写工作区 | 成功。 |
| HOST 模式写工作区外的合成 canary | 拒绝；原文件内容未改变。 |
| HOST 模式读取工作区外及 `../` 路径 | 成功，符合 HOST 语义，也证明不能把此模式当作读取隔离。 |
| localhost 网络 ALLOW 对照 / DENY | ALLOW 接入自建回环服务，DENY 未接入，目标命令失败。 |
| sleep 超过请求期限 | timedOut=true。 |
| 输出超过 8 字节 | stdout 截断；successful() 仍为 true。 |
| 超限原文回调 | 26 字节输出只保留 8 字节，但回调接收到全部 26 字节。 |

独立调用共享捕获器也复现最后两项；该独立测试不是 OS 隔离测试。HOST 沙箱执行另行验证了相同输出行为。DECLARED_ONLY 的失败原因尚未定位，不能把它描述为已确认的内核缺陷、SDK 沙箱逃逸或 Codex 全环境不可用：HOST profile 在同一环境成功。

已有 target 测试报告为 61 项，0 失败、0 错误、12 跳过；其中本机 macOS 后端测试 2 项均跳过。它们是读取的历史报告，未在本轮重新执行。CI 定义存在三平台任务，但没有核验其远端执行结果。当前原生测试证据不足以由“单元测试通过”推出平台安全。

## AGT 侧接入方案

建议新增可选 `agt-sandbox`，依赖 agt-core 与正式 SDK 制品；保持 agt-core 不依赖 JNA 或平台 SDK，agt-langchain4j 继续消费受控工具入口。

调用顺序：可信会话 → 工具授权/有效参数改写/动作审批 → 校验宿主 SandboxExecutionPlan 与实际后端能力 → 预算提交 → 沙箱执行 → 检查 timeout/exit/truncation/回收状态 → 严格解码 → 工具输出治理/审计 → 框架或调用方。

- 工作目录、readable/writable roots、网络权限、程序和环境都由宿主注册。模型参数只能映射到已批准的输入/模板；不得直接反序列化任意 SandboxRequest。
- 保留 v0.5 固定命令模板和程序完整性验证，以执行后端 SPI 替换其直接 ProcessBuilder 路径；不是把 FixedCommand 再套一层并执行两次。需在 AGT 侧增加准备阶段扩展，使 SDK 策略预检失败发生在实际执行计数前。
- 初期只执行已审核的固定工具。模型生成脚本、代码文件、包安装等作为下一阶段单独规格，不因存在 sandbox 字样自动开放。
- 不注册 stdout/stderr 原文监听器；只消费最终字节数组。timedOut、非零退出、任一截断、回收未确认均拒绝交付；不用 successful() 单独判断安全成功。严格 UTF-8 使用 REPORT，不能用 stdoutUtf8()/Auto 默认替换错误字节。
- 同步阻塞入口放进 AGT 已有有限工作池。当前可用线程中断做实验桥，但生产取消应连接 SDK 执行句柄并等待有界回收确认。策略拒绝后不能因为 future 已结束就认为沙箱已清理。
- SDK 异常转换为 AGT 稳定错误码，不把 getMessage()/cause、SandboxResult.toString() 写入产品响应或默认审计。
- 审批与审计同时绑定有效 argv、宿主沙箱配置版本/摘要、工具身份和策略版本；更换网络/目录权限或工具链时不能复用旧审批。
- 默认要求严格读取和网络拒绝；能力不足、状态无法确认时关闭此路径。没有宿主进程执行回退。

## 可直接交给维护 Agent 的需求

### SDK-01 / P0：修复并验证 macOS DECLARED_ONLY，增加按请求策略预检

基础 status probe 使用 allow-default，无法证明实际限制 profile 可用。本机 HOST 两种网络模式成功，而 DECLARED_ONLY 两种模式均 exit 134。

要求：增加无目标命令副作用的 `validate/probe(SandboxPolicy)` 或等价 API；使用实际生成的 profile、工作目录及必要 runtime roots。定位严格读取 profile 缺少的运行时权限并以最小授权修复，禁止通过放开全部 file-read* 或切换 HOST 掩盖问题。

验收：实际支持的 macOS/JDK 17/21 上，严格模式固定 printf/sh/JDK 程序可以运行；读取未声明合成 canary、写外部 canary、network DENY 连接回环对照服务均被阻止。若平台不支持，按策略预检应明确不可用，业务程序启动次数为零。发布报告含 OS/JDK/后端版本及执行/跳过数，不把跳过当作通过。

### SDK-02 / P0：有界取消与回收契约

当前只有阻塞 execute + InterruptedException。共享 ProcessExecutor 的超时后的 wait/read 清理各有额外 5 秒等待；awaitReader 超时会同步 close 正被另一线程读取的流，存在等待 read 锁的风险（源码发现，未复现无限阻塞）。macOS 后代枚举只是尽力；不能对所有后端一概承诺完整进程树回收。

要求：增加执行 ID、cancel/terminate、completion 和 cleanup/termination 结果，或等价取消令牌加独立回收确认。以绝对 deadline 管理启动/策略预检/执行/排空与有限清理宽限，避免管道关闭使等待无界。区分 caller cancellation、目标结束和资源已回收；无法证明清理时返回 UNKNOWN/FAILED，而非 SUCCESS。

验收：取消发生在启动前、运行中、输出阻塞、stdin 不消费、后台子进程持有输出管道时，调用方在规定宽限内得到稳定结果。Windows Job/Linux PID namespace 分别证明受监督后代结束；macOS 无法保证脱离后代时必须明确披露并返回相应状态。AGT 可拒绝继续执行与释放该租户执行容量。

### SDK-03 / P1：输出超限终止选项及成功语义

LimitedOutput 先通知回调再截断保留数据，并持续读到 EOF。successful() 只检查 timedOut 和 exitCode。这是现有语义，AGT 可以先禁用回调并检查 flags，不必为此阻塞适配代码设计。

要求：增加 `onOutputLimit=TERMINATE` 和可选 stdout+stderr 聚合字节额度；返回 OUTPUT_LIMIT 的稳定终止原因。保留已有截断模式兼容性，但不要让“完整成功”辅助方法忽略截断。清楚区分原文观察回调与受限/受控交付回调。

验收：无限输出及 stdout/stderr 同时输出触发有限终止、有限内存；终止状态明确，未交付已截断内容。既有回调兼容模式需明示能获得超过 retainedBytes 的原文。

### SDK-04 / P1：结构化能力与平台准入证据

FULL/PARTIAL 太粗，不能表达严格读取、写隔离、网络、进程回收、CPU/内存/PID/磁盘限制的差异。

要求：按能力维度暴露强制方式及保证级别，标注 kernel / best-effort / unsupported；给出 per-policy readiness 与后端/OS 版本。CI 增加 JDK 17/21 原生测试及失败/跳过统计，默认 Windows 能力不得被宣传成严格读取/网络阻断。

验收：同一严格策略在能力不足平台明确拒绝；报告与实际测试一致。FULL 不代表 VM 强隔离或所有资源都有硬限制。

### SDK-05 / P1：稳定错误码与默认日志脱敏

SandboxException 当前主要以自由文本表达，SandboxResult.toString() 打印 stdout/stderr 字节内容。即使以十进制数组打印，也可恢复原文，不是脱敏。

要求：增加稳定错误分类与阶段信息，默认 toString 仅输出长度/状态/耗时，详细内容通过显式调试 API 获取。异常默认消息不包含命令参数、输出、环境值或敏感路径。

验收：合成敏感标记不出现在正常错误、toString 或默认日志；AGT 可映射后台不可用、策略不支持、启动失败、超限、超时、取消、清理失败，无需匹配自由文本。

### SDK-06 / P1：严格环境与工作区供应接口

当前环境 allowlist 继承宿主 PATH 等变量，并允许显式环境覆盖（包括加载器/语言运行时选项）；临时目录清理是 best-effort。路径检查与目录真实内容不是同一安全保证，硬链接/外部重命名竞态已在 SDK 文档披露。

要求：提供不继承宿主 PATH 的严格环境 preset、宿主固定只读工具链路径、对加载器注入变量的显式准入规则；暴露 workspace/temp 清理结果和私有目录策略。不要要求模型提供可读写根。独立工作区、链接清洗、父目录可信性由 SDK 与宿主契约明确分工。

验收：父进程合成秘密、危险 loader 变量和可写目录工具查找不会意外进入严格 preset；并发任务不共享临时资源。清理失败可观察，不宣称已回收。

### SDK-07 / P1：资源限额与隔离级别

Linux/macOS 暂无内建 CPU/内存/PID/磁盘硬限制。不能把 timeout 和输出截断当作资源耗尽防护。

要求：为高风险代码执行提供可接入 cgroup/worker/VM 的资源约束后端，或显式要求宿主提供这些限制。请求某项硬限制但后端不支持时明确拒绝，不能忽略配置。固定受信任工具的实验阶段可以先由 AGT 有限队列控制并发，但不开放任意不可信代码。

验收：在具备硬限制的平台以受控资源消耗程序验证超限回收；不具备的平台拒绝请求。无需宣称三平台都使用同一实现。

## 接入门槛与下一步

可立即开展可选模块的接口设计和 fake-backend 适配测试；当前 HOST 模式可用于经过审核、无需读取机密隔离的本机实验，但必须显式标明读取不受限。

面向严格读取隔离的本机真实接入，先完成 SDK-01。面向可取消的长任务，先完成 SDK-02 的回收契约与原生验收。SDK-03/05 当前可由 AGT 适配层补偿部分风险，仍建议维护 SDK 改善默认行为。面向模型生成代码或多租户不可信程序，再要求 SDK-04/06/07 与平台隔离威胁模型通过验收。

本轮仅调研和临时探针，未添加正式 SDK 依赖、未实现 AGT 沙箱适配器、未更改 sandbox-demo 源码，也未声称达到 VM 级隔离或 OWASP ASI05 完整覆盖。
