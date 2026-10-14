# v0.5 ASI05 验收与边界

本阶段新增固定受信任命令入口。沿用原有八类生命周期点、两个数据访问点和工具治理；没有引入任意代码解释器、JVM 或操作系统沙箱。

## 验证方式

`bash scripts/verify-java-matrix.sh` 在 JDK 17 与 21 分别 clean 编译、格式检查、测试、打包并运行全部演示。新增测试通过真正的 Java 子进程及实际启动标记验证，无外部 API key 或远程服务。

运行矩阵时向父进程加入非敏感合成环境标记，子进程确认宿主环境未继承；macOS JDK 注入的 `__CF_USER_TEXT_ENCODING` 与当前子进程的 `JAVA_MAIN_CLASS_<pid>` 单独容许。实际进程 PID 仅用于本机测试确认已退出，不进入审计或提交的证据。

## 新增行为证据

| 场景 | 测试 |
|---|---|
| 环境清理、argv 不经 shell 解释 | fixedProcessHasNoInheritedEnvironment；allowedMetacharactersRemainOneLiteralArgument |
| 未知/缺失参数、选项/路径/命令注入零启动 | unknownMissingAndInjectedArgumentsNeverStartProcess；malformedAndInjectedArgumentsHaveZeroProcessEffects |
| 摘要变化与无效注册失败 | changedExecutableIsRefusedBeforeStarting；invalidProfilesAndWrongDigestFailAtRegistration |
| 有限输出、非零退出、无效 UTF-8、超时 | boundedProcessFailuresUseSanitizedCodes |
| 关闭、撤销、中断清理主进程与释放容量 | terminationStopsWaitAndCapacityIsReleased |
| 会话期限阻止交付 | sessionDeadlineRefusesProcessOutput |
| 同步重载和异步均改写有效参数/结果，只计数一次 | allEntryPointsExecuteOnceAndRedactActualOutput |
| 权限、审批拒绝零启动；审批绑定有效参数 | permissionsAndMissingApprovalRefuseWithoutStarting；approvalDenialAndAllowanceBindToEffectiveArguments |
| 宽松策略及观察模式不能突破宿主模板 | permissivePolicyCannotBypassCommandTemplate；observationModeStillCannotRunUnregisteredArguments |
| 审批期间更改程序在计数提交前拒绝 | changingExecutableDuringApprovalIsDetectedBeforeExecutionCount |
| 实际 AI Services 收到脱敏结果 | realAiServiceReceivesOnlyRedactedProcessResult |
| 错误处理器吞掉拒绝后不能恢复模型 | frameworkErrorHandlerCannotResumeAfterCommandRefusal |
| 异步取消清理已启动主进程，保留执行计数 | asyncCancellationStopsOwnedProcessAndKeepsExecutionCount |
| 审计执行前失败零启动；执行后失败不交付且不撤销副作用 | auditFailureBeforeCommandPreventsProcessStart；postExecutionAuditFailureWithholdsOutputButCannotUndoProcessEffect |

## 验收记录

2026-10-14，macOS arm64、Maven 3.6.3、LangChain4j 1.20.0、Jackson 2.22.1。完整结果汇总见 [command-verification.json](evidence/command-verification.json)。

| 环境 | core | adapter（不含 HTTP/SSE） | HTTP / SSE | 合计 | 失败 / 错误 / 跳过 |
|---|---:|---:|---:|---:|---|
| JDK 17.0.11 | 72 | 111 | 2 / 1 | 186 | 0 / 0 / 0 |
| JDK 21.0.4 | 72 | 111 | 2 / 1 | 186 | 0 / 0 / 0 |

每个 JDK 运行 33 个演示（原有 26 个 + 命令 7 个）以及原有两份 YAML 策略示例。新增 32 项测试（core 13、adapter 19）。格式检查与 Maven verify 通过；MkDocs 严格构建成功，浏览器确认新增指南入口与代表性 Mermaid 图实际渲染。没有运行 Sonar 或新增性能基准，不给出进程治理延迟/吞吐承诺；旧版测量不能代表命令执行开销。

## ASI05 结论

ASI05 从一般工具许可加强到固定命令的实际执行约束：模型不能提供自由命令/脚本，摘要与模板校验在启动前拒绝，受限输出经过治理后交付，会话终止触发可验证的主进程清理。

已验证的是固定受信任程序入口，不是 RCE 全面防护。可执行文件摘要不包含 JAR/脚本/动态库或来源证明，也不能消除启动竞态；工作目录不是文件隔离，环境清理不是网络隔离。CPU/内存/磁盘限制、系统调用控制、不可信代码沙箱、同 JVM 任意工具内部执行拦截均未实现。

后代清理尽力而为，未验证脱离父子关系的逃逸进程能够可靠终止；守护读取线程可能因逃逸进程持有管道而延迟退出。进程已有副作用不能回滚。只在本机 JDK 17/21 验证，未声明 Windows/Linux、真实业务 CLI 或隔离运行时的兼容性。

原有 OWASP 缓解保持，数据权限边界见 [v0.4 数据验收](data-verification.md)，完整历史映射见 [首期映射](verification.md)，其中 ASI05 当前措施以本页为准。不将测试数量换算成 OWASP 覆盖率或认证。
