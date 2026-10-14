# ASI05 固定命令工具

适用于宿主审核过的报告、格式检查或信息查询程序。模型只能选择宿主公开的有限选项；程序路径、工作目录、argv 模板和摘要均来自可信配置。

## 接入

```java
// 部署端提供真实绝对路径与已验证的发布摘要；不要从模型参数建立这些值。
var command = new FixedCommand(
        "report",
        Path.of(System.getenv("AGT_REPORT_EXECUTABLE")),
        System.getenv("AGT_REPORT_SHA256"),
        Path.of(System.getenv("AGT_REPORT_DIRECTORY")),
        List.of(CommandArgument.literal("--format"),
                CommandArgument.choice("format", "summary", "detailed")),
        CommandLimits.DEFAULT);

var policy = StructuredPolicy.builder("report-policy-v1")
        .tool("report", new ToolRule(Set.of("command:report"), command.parameters(), true))
        .build();

var assistant = new LangChain4jGovernance(control)
        .aiServices(Assistant.class)
        .chatModel(model)
        .commandTool(command)
        .build();
```

这里的 `control` 必须加载该策略、提供 AuditSink，并连接真实 ApprovalResolver；宿主认证后创建可信会话，再调用 assistant。`true` 要求每次执行审批；缺少审批器即拒绝。沿用 [接入指南](user-guide.md) 的 AgentControl 与审批配置。以上配置针对真实程序的 `--format` 参数，选项的业务安全性由宿主审核。

高级入口为 `governance.guardCommand(command)`，返回受控 ToolExecutor；可在同一会话下调用同步重载或 executeAsync。Builder 已自动包装 commandTool，勿再次嵌套受控执行器。原始 FixedCommand 的 prepare/execute 是低层宿主接口，直接调用不自动实施工具策略或审计。

本轮没有新增 YAML 命令配置。命令模板属于宿主可信配置，权限、审批、预算和内容规则仍可用现有 YAML；tools 下必须显式列出同名工具及匹配的闭合枚举参数。Java 方式可用 command.parameters() 复用模板，避免两处手写枚举不一致；即使 YAML 规则较宽松，模板仍拒绝未列出的有效参数。

## 实际执行行为

模型参数 `{"format":"summary"}` 对应两个独立 argv：`--format`、`summary`。不拼接为 shell 命令；未知字段和任意其他字符串拒绝。内容改写后重新校验策略和模板，审批针对有效参数；程序及模板在注册后固定。代码发布更换程序或参数模板时重新创建对象并更新策略版本，不复用旧审批。

执行顺序为：工具额度预占 → 策略/改写/审批 → 模板与摘要检查 → 提交工具执行计数 → 执行器再次检查与容量准入 → 启动进程 → 检查完整输出 → 审计 → 交付。准备失败计数为零；进入执行器后的容量拒绝、二次摘要失败、启动失败和实际进程失败仍保留一次工具执行尝试计数。无隐式重试，外部重试需重新进入治理入口。

stdout/stderr 合并后按字节限制，既有 JSON 投影与整体载荷限制仍适用；非零退出、无效 UTF-8 或超限不交付部分输出。内容改写实际替换交付给模型和调用方的文本。父进程环境被清空，程序不能依赖 PATH/HOME 或继承的密钥；JDK/操作系统可能添加少量自身需要的环境变量。本机 macOS 测试接受 JDK 注入的 `__CF_USER_TEXT_ENCODING` 与当前子进程的 `JAVA_MAIN_CLASS_<pid>`，不意味着继承宿主环境。固定工作目录不限制文件访问范围。

默认限制为 5 秒、64KiB 输出、单个命令实例最多两个执行器同时进入。异步使用治理运行时有界工作池；多个 FixedCommand 实例各有独立容量。会话关闭/撤销/期限以及取消中断会触发尽力清理，无强制停止所有后代的保证。

`EVALUATE_ONLY` 不实施权限拒绝、审批或内容改写；固定模板、摘要、UTF-8 和资源限制仍作为执行器自身约束生效。生产权限隔离应使用 ENFORCE。

## 错误码

| 代码 | 含义 |
|---|---|
| command_binding_invalid / command_binding_changed | 路径无效、符号链接或绑定路径变化 |
| command_integrity_denied | 可执行文件摘要不匹配或无法验证 |
| command_arguments_denied | 有效参数缺失、未知、类型错误或不在枚举中 |
| command_capacity_exceeded | 该命令实例没有可用执行容量 |
| command_start_failed | 无法启动进程或准备 stdin |
| command_timeout / command_cancelled | 超时或工作线程中断 |
| command_output_limit / command_output_failed | 输出超限、读取失败或无效 UTF-8 |
| command_exit_denied | 非零退出码，原始输出不会返回 |

策略、审批、预算、会话和审计错误沿用原有代码。会话终止与进程异常同时发生时，以先观察到的拒绝为准；安全拒绝后框架错误处理器不能恢复调用。

## 离线演示

```bash
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 verify
java -jar examples/target/agt-examples-0.5.0-SNAPSHOT.jar command all
```

七个场景：允许、权限拒绝、参数注入拒绝、审批允许、审批拒绝、输出脱敏、超时。程序打印 result、实际启动标记和工具计数；拒绝场景的 started 为 false。演示使用本机 JDK 和固定示例类，临时工作目录及启动标记在结束后删除。即时审批与自动计算本机摘要只用于演示。

## ASI05 覆盖边界

新增了可验证的命令模板、执行完整性检查、环境控制、有界输出与生命周期清理。不能据此宣称 RCE 完全覆盖：任意宿主工具、解释器代码、动态库/脚本供应链、文件/网络隔离和系统调用沙箱仍未实现。详见 [需求与选型](command-requirements.md) 和 [验收报告](command-verification.md)。
