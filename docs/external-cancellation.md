# 外部执行取消与确认回执

会话取消会停止受控结果交付，但不能凭 Future 的状态断言服务器任务、进程或沙箱已停止。本功能为根任务增加外部执行登记和回执，后续已接入跨服务权威状态及可选 JDBC 持久化，区分请求取消、实际确认与未知状态；固定命令和沙箱后端已接入。

现成机制检查：[JDK CompletableFuture](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html) 的取消不使用线程中断控制底层处理；[Temporal 的取消等待语义](https://docs.temporal.io/develop/typescript/workflows/cancellation)也区分发出请求和等待 Activity 确认。本轮复用现有会话级联、JDK 有界执行器、直接进程 onExit 和已接入沙箱 SDK 的 cleanup 结果，没有为取消回执引入工作流平台。

## 登记与状态

可信宿主在发出操作前调用 `session.registerExternalExecution(kind, cancellation)`，得到带唯一 ID 的 `ExternalExecution`。`kind` 为有界机器标识，不能包含凭据、完整 URL、提示词或命令正文。登记不授予工具、文件、网络或模型权限，真正执行仍须经过原来的受控入口。

```java
var stopped = new CompletableFuture<TerminationEvidence>();
var execution = session.registerExternalExecution("REMOTE_JOB", () -> {
    requestStopOnTrustedBackend(); // 必须快速返回；此调用只是请求取消。
    return stopped;               // 可信后台确认结束后再完成。
});
execution.started();             // 必须在实际外部执行前调用；准备中已取消则拒绝。
startTrustedBackendJob();

// 后端核实执行及约定资源已经结束；不能用 Future.isCancelled() 替代。
stopped.complete(TerminationEvidence.CONFIRMED);
// 正常完成而非取消时，宿主同样必须提交实际证据：
// execution.finished(TerminationEvidence.CONFIRMED);
```

上例中的后端函数由宿主实现。只有实际确认后才能提交 CONFIRMED，模型返回的“已停止”不能作为回执。调用 `.close()` 仅请求取消，不阻塞等待或伪造确认。

| 状态 | 含义 |
| --- | --- |
| PREPARED | 已登记，尚未开始；取消后禁止开始，确认 NOT_STARTED |
| RUNNING | 宿主已进入外部执行路径 |
| CANCEL_REQUESTED | 取消请求已排队/发出，尚未取得可靠结束证据；继续占用名额 |
| COMPLETED | 正常执行结束；还须看 evidence，不能把 WORKER_ONLY 当成完整资源清理 |
| CANCEL_CONFIRMED | 取消后的已确认停止，或可证明从未开始 |
| UNKNOWN | 回执异常、取消确认超时，或只有不充分的清理证据；继续占用名额并冻结根任务 |

`TerminationEvidence` 包括 CONFIRMED、NOT_STARTED、WORKER_ONLY、UNKNOWN。正常完成的 WORKER_ONLY 会明确标记未确认全部资源；取消后收到 WORKER_ONLY 则进入 UNKNOWN。对已经发出的外部操作，取消回调的 Future 被取消不算确认。

## 父子任务、容量与查询

本机显式父子会话共享根任务登记表；签名接收会话使用同一在线权威账本，登记和开始必须先确认，记录 taskId、executionId、sessionId、kind、状态和证据，不采集任务正文。父级撤销、关闭、到期、失败会对各子孙登记请求取消；某个子节点撤销时，其他分支可以继续，除非发生根任务级未知执行故障。

`session.externalExecutionSnapshot()` 在会话关闭后仍可读取，返回全部登记项及 active、pendingCancellation、unknown、allTerminationConfirmed、fault。`allTerminationConfirmed` 仅针对已登记任务和其明确的证据范围，不证明 SDK 之外的任意进程、线程或远端工作均已停止。

每根任务最多保留 100000 项记录、128 个未结束执行；UNKNOWN 不释放名额。取消回调使用每运行时 2 个线程、128 个队列位置的独立有界池，不能拖住 Provider 完成线程。回调应迅速返回 stage；池拒绝、回调异常或等待约 2 秒仍无证据时，记录 `external_execution_unconfirmed` 并冻结根任务。计时依赖 JVM 调度，不是硬实时保证。

迟到的可靠回执可以补正未知项，释放该执行名额；根任务 fault 不自动解除，不恢复已终止会话。运行时关闭时，仍待确认的登记显式变为未知，不因关闭定时器而丢失状态。不同根任务分别隔离；这些限制不是账户级或整个服务器的任务总量限制。

## 固定命令与沙箱

默认本机 `FixedCommand` 登记为 DIRECT_PROCESS：确认范围是直接子进程退出及输出读取线程结束。会话撤销/关闭或调用线程中断后，测试使用真实 Java 子进程验证 PID 已退出才确认；不会把收到中断或发送 destroy 当成退出。它对不可观察后代仍是尽力处理，不承诺进程组/cgroup 隔离。直接进程或读取线程尚未确认结束时继续占住命令实例的并发名额；启动结果未知时隔离该命令实例，不因调用方已返回而复用容量。会话已到期时保留 session_expired 原因，不能因为取消导致非零退出而误报 command_exit_denied。

`SandboxCommandBackend` 登记为 SANDBOX_TASK，复用 SDK `SandboxExecution.cleanup()`：

- 排队时被取消、没有进入 SDK 启动，登记 NOT_STARTED。
- SDK worker 尚未真正结束时，即使调用方已经收到取消结果，也继续等待回执，保持原后端执行名额。
- cleanup 全部确认后才提供完整确认；只有 worker 结束、进程等资源为 UNKNOWN 时提供 WORKER_ONLY。
- 取消后的不完整清理进入 UNKNOWN，根任务冻结；原后端的粘滞隔离策略继续生效，不能因回执稍后确认就自动复用。

macOS 某些正常退出仍仅具尽力回收保证；本功能将这种正常结果记为 COMPLETED / WORKER_ONLY，`allTerminationConfirmed` 为 false，不抬高原来的平台保证。沙箱故障模拟测试验证的是 SDK/治理契约，不能当作内核隔离或容器逃逸测试。

回执不会退款模型未知用量，也不会回滚已发生的业务副作用。外部确认停止不等于 Provider 账单为零、写入未发生或补偿已完成。

## 跨服务权威账本与持久化

使用 agt-delegation 签名委托时，接收 SDK 自动接入 ExternalExecutionLedger。`work_register` / `work_start` 在真正外部启动前同步取得权威确认；不同节点共用每根 128 个活跃名额和 100000 条记录。登记或开始回执未知时不能直接派发，也不能重放实际工作。取消回调仍在节点执行，终止元数据经独立有界队列发布，不阻塞 Provider 的完成线程。节点先记录本机证据，发送端/接收端对外快照则读取权威视图，因此本机已经结束不等于权威端已收到确认。

服务端校验私有租约、绑定会话和记录所属授权。兄弟 Agent 即使知道 executionId，也不能替别人登记确认。源任务、父级撤销或节点租约到期只会对运行项登记 CANCEL_REQUESTED；等到确认期限仍没有可信证据才变为 UNKNOWN。准备中任务没有获得开始准入，可以登记 NOT_STARTED。节点离线、Future 完成、租约失效均不能被算作外部进程已退出。

可选 [JdbcGrantRegistry](durable-authority.md) 在根事务中保存记录、取消期限、授权所有权、证据、粘滞 fault 和状态版本。新的注册表实例读取相同状态；拒绝也保留到期/未知变化。恢复纯元数据，不启动工作、不重放取消回调，不自动恢复已过期授权。默认 InMemoryGrantRegistry 仍不持久化；需要重启保留的应用必须显式配置 JDBC。

源任务没有执行中受控操作、活跃/未知外部工作时，可以导出已经结束的外部历史，保持原 executionId、sessionId、类型和证据。上一阶段的 `external_execution_delegation_unsupported` 全历史限制已解除；尚未结束的任务改以 `external_execution_inflight_denied` 拒绝交接。WORKER_ONLY 历史会保留部分确认，不能在换节点后变成完整确认。

### 结束后的补报与查询

`work_finish`、`work_cancel` 和只读状态允许在原委托 Token/租约到期后使用，但宿主传输身份必须仍已认证，私有租约和旧会话绑定必须匹配，而且只能作用于该授权已经登记的执行。它们不能注册、开始、续租或授予新权限。可靠迟到证据可更新 UNKNOWN 为已确认并释放名额；已有根 fault 不清除，后续运行仍被拒绝。错误节点、会话或未知 executionId 一律拒绝。

`session.externalExecutionSnapshot()` 在签名源/接收侧返回同一权威视图；宿主私有 `registry.inspect` 也包含 external 统计，继续要求同租户、同 principal 和 `governance:inspect`。SDK 使用 128 条一页的元数据协议读取完整快照，校验总数、页位置、版本、重复记录和汇总计数；并发变化返回 `external_execution_snapshot_changed`，请在状态稳定后重查，不会截断后声称全部确认。分页诊断不持有任务锁，不应阻止会话心跳或终止。

注册、开始以外的元数据发布失败会终止对应节点会话并留下不确定状态；权威端继续按租约和确认期限保留名额。当前没有持久本机回执 outbox，节点丢失且最后证据未送达时，需可信宿主核对后补报；不能因执行节点已经退出就猜测外部结果。收到可信 CONFIRMED 也不等于独立的远端内核证明，前提仍是 SDK 宿主及后端证据源可信。

### 验证范围

独立两个 SDK JVM 经本机 HTTP 状态服务访问 JDBC 权威账本，实际启动固定 Java 子进程。正常取消后验证真实 PID 退出及 CANCEL_CONFIRMED；强制杀掉 SDK JVM 时，该子进程仍活着，账本保持 UNKNOWN / active=1，兄弟节点被阻止派发。回执丢失用例验证在委托到期后补报实际终止，既更新确认也保留根冻结。

另覆盖跨实例并发登记的全局 128 上限、源历史交接、所有权拒绝、纯状态恢复、257 条完整分页、类型强制转换拒绝和并发快照版本重检。测试宿主最后清理自建子进程，这种清理不被冒充成 SDK 已收到确认。执行回执 RPC 只传 opaque ID、类型、状态、期限、证据与必要鉴权字段，不发送命令、路径、PID、查询或模型正文。私有租约字段不能进入公开链路正文，宿主的通用 HTTP 日志仍须保护这些凭据。

这仍是实验级接入：仅验证 H2、本机 HTTP 测试传输与当前平台的直接进程范围，没有验证数据库故障切换、生产 mTLS/IAM、所有平台的进程树或不可伪造审计。JDBC 每次操作保存完整根检查点，大图、高吞吐及分页长查询的生产容量仍须验收。

## 后续

受治理模型现在自动登记 MODEL_REQUEST，原生取消不被当作远端停止证明；宿主可选接入逐请求终止契约，详见[模型终止指南](model-termination.md)。仍未验证所有厂商生产停止 API。生产 IAM、高可用、逻辑文档版本与后续 P1 继续按[补齐计划](security-hardening-plan.md)推进。

本机回执初版验证 649 项，648 通过、1 项可选真实 Provider 测试跳过，见[初版证据](evidence/external-cancellation-governance.json)。

跨服务持久回执最终全工程验证 662 项，661 通过、1 项可选真实 Provider 测试跳过。具体参数、真实进程场景和边界见[分布式回执证据](evidence/distributed-external-cancellation-governance.json)。
