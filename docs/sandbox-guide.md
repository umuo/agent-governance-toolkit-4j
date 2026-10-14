# 当前沙箱 SDK 接入

v0.6 新增可选 `agt-sandbox`，固定使用 Maven Central 的 `io.github.umuo:agent-sandbox-sdk:0.1.0-alpha.1`。它执行宿主审核过的固定命令，沿用 AGT 的会话、权限、审批、参数改写、预算和审计。没有开放任意模型生成脚本或安装包执行。

## 依赖与最小接入

应用保留 agt-langchain4j，并增加：

```xml
<dependency>
  <groupId>io.agt</groupId>
  <artifactId>agt-sandbox</artifactId>
  <version>0.6.0-SNAPSHOT</version>
</dependency>
```

SDK alpha.1 作为传递依赖固定引入；应用无需复制 SDK JAR 或使用本机 sandbox-demo 源码。AGT 制品当前仍需本机 `mvn install`，尚未发布 AGT 自身到 Central。agt-core 和单独的 agt-langchain4j 不引入 JNA。

以下变量由宿主可信配置提供，不能来自模型：

```java
var sandboxPolicy = SandboxPolicy.builder(workspace.toRealPath())
        .readPolicy(ReadPolicy.DECLARED_ONLY)
        .network(NetworkPolicy.DENY)
        .timeout(Duration.ofSeconds(5))
        .maxOutputBytes(65536)
        .build();

try (var backend = SandboxCommandBackend.strict(SandboxClient.create(), sandboxPolicy)) {
    var command = new FixedCommand(
            "report", executable, trustedSha256, workspace.toRealPath(),
            List.of(CommandArgument.literal("--format"),
                    CommandArgument.choice("format", "summary", "detailed")),
            CommandLimits.DEFAULT, backend);

    // control 的策略必须显式允许 report、command.parameters() 及对应权限/审批。
    var assistant = new LangChain4jGovernance(control)
            .aiServices(Assistant.class).chatModel(model).commandTool(command).build();
    try (var session = control.openSession(authenticatedIdentity)) {
        String result = assistant.chat("生成摘要报告");
    }
}
```

类型来源：`io.agt.sandbox.SandboxCommandBackend`、`io.agt.core.execution.*`、`io.github.sandboxdemo.api.*`、`io.github.sandboxdemo.sdk.SandboxClient`。治理运行时和策略配置沿用 [固定命令指南](command-guide.md)。backend 应覆盖所有使用它的调用生命周期；异步必须等待调用完成后再关闭。

`strict` 要求 DECLARED_ONLY，网络仍按明确提供的 SandboxPolicy 执行，示例选择 DENY。SDK 能力不足或实际 profile 被拒绝时，没有 HOST/ALLOW/普通 ProcessBuilder 回退。传入 null 后端的七参数 FixedCommand 构造器拒绝；既有六参数构造器仍是明确的本机进程入口，不自动变成沙箱。

## 当下可用的 HOST 模式

alpha.1 在本机 macOS 15.5 的严格读取 profile 被拒绝。对不需要读取机密隔离、经审核的固定工具，宿主可以**明确选择**：

```java
var policy = SandboxPolicy.builder(workspace.toRealPath())
        .readPolicy(ReadPolicy.HOST).network(NetworkPolicy.DENY).build();
var backend = SandboxCommandBackend.allowHostReads(SandboxClient.create(), policy);
```

HOST 允许读取宿主账户可访问的文件；保留写隔离和配置的网络权限，但不是机密读取隔离。此选择必须在宿主配置中体现，不能在 strict 失败后自动尝试。Windows 默认 SDK 不支持 DECLARED_ONLY 或网络 DENY，相同策略会明确拒绝；当前未做 Windows/Linux 原生验证。

SandboxPolicy builder 默认授予工作目录读写。只读工具应显式 `.readOnlyWorkingDirectory()`，再声明必要的 readable roots；SDK 私有临时目录仍可写。模型不能指定 roots、程序、环境、网络权限或原始 SandboxRequest。每个 backend 对应宿主绑定的工作区；SDK 不自动根据 AGT tenantId 建立存储隔离，宿主须为不同租户选择正确的独立工作区/后端。

## 资源、输出与取消

后端独立容量默认 2，可通过工厂第三参数设置为 1–32；无等待队列。命令限额和 SDK 策略取较小的超时及输出额度，stdout+stderr 按聚合字节数再校验。CommandLimits 的时间/输出/容量语义见固定命令指南。

适配器不注册任何 SDK 原文回调，不传入 stdin 或自定义环境。SDK 自身仍继承受限系统环境 allowlist（包含 PATH 等），不是原本机入口的全空环境；固定 executable 与 allowPathSearch=false 阻止外层工具路径搜索，不能保证目标程序内部不使用 PATH。工具链及目录仍须宿主审核。

拒绝 timedOut、非零退出、stdout/stderr 任一截断、聚合超限、无效 UTF-8；不单独信任 successful()，不用 Auto 字符集回退。成功文本按 stdout 后 stderr 合并，中间必要时加换行，**不声明两条流的原始交错顺序**。该文本进入原有 post_tool_call 检查/改写后再交付；默认审计不记录原始输出、SDK 异常或 SandboxResult.toString()。

当前 SDK 缺少取消句柄/回收确认。适配器按命令期限与会话状态有界轮询，终止时中断 SDK 工作线程；直到 SDK 调用返回并结束处理才释放后端容量。SDK 不响应中断时调用方仍可收到拒绝，后台任务仍可能运行。

已进入 SDK 后的取消、SDK 超时或未知 SDK 执行异常，使此 backend 永久进入 `sandbox_cleanup_unconfirmed`，拒绝新任务；即使工作线程随后退出也不自动解封。close 拒绝新调用并中断已有任务，不证明 OS 后代已全部结束。宿主必须核验外部资源清理后才能决定是否创建新的后端/工作区，不能用重建对象来掩盖仍运行的进程。窗口内已开始的其他任务仍按自身有限期限等待，后端隔离后不交付成功结果。

不会宣称提供 CPU、内存、磁盘或进程数硬限额，不保证终止已脱离的 macOS 后代，不撤销已有副作用。这些改进在 sandbox-demo 独立维护，本次接入不等待或假定其已完成。

## 准备与计数

在策略改写/审批后，FixedCommand 检查模板及程序摘要，后端检查能力、工作目录与 SDK 请求 DTO 约束；失败计数为零。实际 SDK profile 的原生预检属于当前 SDK execute 内部，适配器不拿基础 status().ready() 当作全部策略证明。

进入后端执行后，容量拒绝、原生 profile 不可用、启动或结果失败均保留一次工具执行尝试计数。例如 strict profile 被拒绝时可能是 toolCalls=1、目标 workspaceWrite=false。SDK 内部 benign profile probe 不作为第二个 AGT 工具调用计数；没有自动重试。审计的 failed_after_execution 指 delegate 已进入，并不证明目标程序产生了副作用。

宿主需把工具/沙箱配置发布版本纳入固定的治理策略版本。此版本绑定不可变注册配置；本次未扩展审计 schema 自动记录 SDK profile 摘要，宿主若修改 OS 权限或工具链必须新建配置/策略版本，不能复用旧审批。

## 演示与验证

```bash
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 verify
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar sandbox all
```

七个本机 macOS 场景：显式 HOST 允许、异步允许、权限拒绝、strict 关闭、工作区外写拒绝、输出超限、超时。示例仅使用合成工作区，自动计算本机程序摘要只用于演示。其他 OS 明确显示 native demo skipped，不能计作原生验收通过。实际结果见 [当前沙箱验收](sandbox-verification.md)。
