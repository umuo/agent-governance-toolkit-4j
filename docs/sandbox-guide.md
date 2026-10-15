# 当前沙箱 SDK 接入

agt-sandbox 固定依赖 Maven Central `io.github.umuo:agent-sandbox-sdk:0.1.0-alpha.2`。它是可选的执行后端，继续复用 AGT 的可信会话、权限、参数改写、审批、预算和审计。核心固定命令接口仍只接受宿主审核的程序和模板；live 示例的显式 Bash 工具是独立的示例级脚本入口，不等于核心支持任意代码安全执行。

## 依赖与最小接入

```xml
<dependency>
    <groupId>io.agt</groupId>
    <artifactId>agt-sandbox</artifactId>
    <version>0.6.0-SNAPSHOT</version>
</dependency>
```

应用保留 agt-langchain4j，SDK alpha.2 作为传递依赖引入。AGT 自身尚需本机安装，单独 agt-core / agt-langchain4j 不引入 JNA。SDK 正式制品、源码与验收记录见 [当前验收](sandbox-verification.md)。

```java
// 所有路径、程序摘要、权限和参数模板均来自宿主，不能从提示词建立。
var policy = SandboxPolicy.builder(workspace.toRealPath())
        .readPolicy(ReadPolicy.DECLARED_ONLY)
        .network(NetworkPolicy.DENY)
        .timeout(Duration.ofSeconds(5))
        .maxOutputBytes(65536)
        .build();
try (var backend = SandboxCommandBackend.strict(SandboxClient.create(), policy)) {
    var command = new FixedCommand("report", executable, trustedSha256,
            workspace.toRealPath(),
            List.of(CommandArgument.choice("format", "summary", "detailed")),
            CommandLimits.DEFAULT, backend);
    // control 的策略须显式允许 report、command.parameters() 及对应权限/审批。
    var assistant = new LangChain4jGovernance(control).aiServices(Assistant.class)
            .chatModel(model).commandTool(command).build();
    try (var session = control.openSession(authenticatedIdentity)) {
        String answer = assistant.chat("生成摘要报告");
    }
}
```

类型来源：`io.agt.sandbox.SandboxCommandBackend`、`io.agt.core.execution.*`、`io.github.sandboxdemo.api.*`、`io.github.sandboxdemo.sdk.SandboxClient`。运行时/策略配置沿用 [固定命令指南](command-guide.md)。异步调用必须等待完成后再关闭 backend。

## 严格读取、环境与可信工作区

alpha.2 已在本机 macOS 15.5 arm64 验证 DECLARED_ONLY：允许固定 printf/sh 与显式声明 JDK runtime 的 Java 程序运行，拒绝未声明 canary 读取。实际 profile 不可用仍明确拒绝；没有 HOST / ALLOW / 普通进程回退。Windows/Linux 尚未在本轮原生验证。

HOST 只适合宿主明确接受宽泛读取的工具，不能当作机密读取隔离：

```java
var policy = SandboxPolicy.builder(workspace.toRealPath())
        .readPolicy(ReadPolicy.HOST).network(NetworkPolicy.DENY).build();
var backend = SandboxCommandBackend.allowHostReads(SandboxClient.create(), policy);
```

两种入口默认都使用 SDK **严格环境**：PATH 仅包含固定 executable 的父目录，不继承宿主 PATH，不传额外变量、stdin 或原文回调。工具目录不得与工作区/可写根相交。依赖其他系统工具时，宿主应显式提供严格工具目录，例如：

```java
var environment = SandboxEnvironment.strict(
        Path.of("/bin").toRealPath(), Path.of("/usr/bin").toRealPath());
var backend = SandboxCommandBackend.strict(
        SandboxClient.create(), policy, environment, 2);
```

四参数工厂只接受 strict 环境；不接受 legacy 环境作为无声降级。宿主提供的工具目录和依赖需审核并只读部署，PATH 固定不等于目录内容不可被其他宿主进程修改。加载器/运行时注入变量按 SDK 严格环境契约拒绝。

HOME / USERPROFILE / 私有临时目录由 SDK 为每次执行供应并在结束后清理；**当前工作目录仍为配置的工作区**。需要跨调用保存的文件使用工作区相对路径，不要放在 `~` 下。live Bash 显式供应 `/bin` 和 `/usr/bin`，同步采用这个契约。

SandboxPolicy 默认工作目录可写。只读工具须 `.readOnlyWorkingDirectory()`；适配器不会重建出隐式写权限。每个 backend 对应宿主绑定的工作区，SDK 不按 AGT tenantId 自动分配存储；不同租户的工作区/后端由宿主选择。

## 执行前预检与计数

调用链：授权/参数重验/审批 → FixedCommand 检查模板及摘要 → backend.prepare 按有效 SDK 请求 `client.validate` → 工具预算提交 → SDK 执行句柄 → 结果与回收检查 → POST_TOOL_CALL → 模型/调用方。

SDK validate 只运行可信探针，不执行目标 argv、stdin 或回调。实际策略不支持、profile 失败、非法 DTO 或资源硬限额不支持，准备阶段拒绝且工具执行计数为 0。不拿基础 status.ready() 代替按策略预检。执行路径再次检查策略和会话；若在预算提交后发生容量拒绝、启动或结果故障，仍计为一次工具执行尝试，不能用此计数证明产生了副作用。SDK 内部探针不算第二个 AGT 工具调用；没有自动重试。

宿主 SandboxPolicy 的 `resourceLimits` 与 `requiredIsolation` 完整保留。请求 VM 或某项硬限制而后端不支持时明确拒绝，不能把重建请求的默认值当作降级。macOS 当前不提供 CPU/内存/PID/磁盘硬限制；没有请求某维度不等于其已受限。

原有 FixedCommand 六参数构造器仍为本机进程入口；七参数构造器必须提供非空 backend。不增加第二条工具执行或重复计数路径。

## 输出与有界执行

适配器强制 `onOutputLimit=TERMINATE`、`outputCallbackMode=RETAINED`，但不注册任何输出回调。每流上限取 SDK 策略与 CommandLimits 的较小值；stdout+stderr 的共享上限还要与宿主显式 combined 上限取较小值。SDK 因超限终止后返回稳定 sandbox_output_limit；终止不是原子操作，尤其 shell 子进程先被停止时仍可能短暂继续执行，不能承诺撤销或阻止所有已经进入执行阶段的副作用；适配器仍重验截断、聚合大小和严格 UTF-8，不能单独相信 successful()。合并 stdout/stderr 时加入的换行也计入最终额度，不声明两条流的原始交错顺序。

默认文本执行拒绝非零退出；已有 executeReport 保留结构化退出码与两条文本流的返回语义，仍须由工具后置治理后交付，不能把原始 report 直接公开。异常只映射 SDK 稳定 code，不读取 debugMessage/debugCause。

控制池与 SDK 工作池均有界、零队列、AbortPolicy，默认各最多 2 个守护线程，工厂容量参数允许 1–32。两池分开避免控制线程等待自己占满的 SDK 池。调用方按命令期限、会话撤销、Future 取消及关闭状态有界等待。

## 取消与独立回收状态

alpha.2 接入 `SandboxExecution.cancel/await/cleanup`，不会把 Future 或 completion 已完成当作回收确认。容量保持到 SDK 的 workerFinished；不合作的 SDK 任务可以继续占据有限后台容量，但调用方无需一直等待。

正常结果交付要求 workerFinished，管道、临时文件和宿主准备资源均 CONFIRMED / NOT_REQUIRED；FAILED / UNKNOWN 拒绝并隔离 backend。进程 FAILED 也拒绝。明确声明 macOS PROCESS_TREE_TERMINATION=BEST_EFFORT 的后端正常退出时，允许其进程 UNKNOWN，这是当前固定受信任工具的兼容边界，**不是进程树全部回收证明**；其他后端不能以未知进程状态通过该例外。

已进入 SDK 后的取消、SDK 超时及未知执行状态继续保守地永久隔离 backend；即使随后收到清理结果，也不自动解封。close 发出 SDK 取消并关闭两池，不等待或承诺所有 OS 后代结束。SDK 错误明确报告 CLEANUP_FAILED 时同样隔离。宿主不能仅重建对象来掩盖可能仍在运行的进程；需按 SDK 快照与外部资源核验决定新的执行环境。

审批与审计继续绑定 AGT 的动作、有效目标和策略版本。本次不扩展审计 schema 自动写入 SDK 执行 ID 或 profile 摘要；宿主必须将不可变沙箱配置/工具链版本纳入治理策略版本，变更权限后不能复用旧审批。

## 演示与验证

```bash
bash scripts/verify-java-matrix.sh
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar sandbox all
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar security asi05
```

七个原生演示保留：HOST、异步、权限拒绝、严格模式、外部写拒绝、输出超限、超时。当前 macOS 严格模式已成功，不再把旧 alpha.1 的不可用作为当前预期。SDK / AGT 契约测试与原生测试分开记录；live provider 外部模型测试未显式设置 AGT_LIVE_TEST=true 时跳过，不算通过。
