# ASI02 / ASI05：逐场景阅读与运行

这组示例让你观察两个不同的边界：**AGT 决定一个动作能否进入工具；工具进入后，业务实现和隔离后端决定它能访问什么。** 默认拒绝、权限和参数校验不能自动补齐 SQL 行级授权，也不能把宿主 JVM 变成沙箱。

示例沿用当前 Java 17、LangChain4j 1.20.0 与 Central sandbox SDK 0.1.0-alpha.1。新增的 H2 2.3.232 只在 examples 的运行时使用，用来执行真实内存 SQL；不接入宿主数据库，不进入 agt-core、agt-langchain4j 或 agt-sandbox 的运行时依赖。HTTP 只访问自建回环服务，文件只使用临时目录与合成 canary；无需模型 API Key。

## 先跑，再跟代码

```bash
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 package

# 跑整组或其中一个场景，场景名就是类名
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar security asi02
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar security asi05
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar security asi02 ArgumentRewriteDemo

# 每个类都有 main，可以独立启动，也可在 IDE 直接运行
java -cp examples/target/agt-examples-0.6.0-SNAPSHOT.jar \
  io.agt.examples.asi05.WorkspaceWriteIsolationDemo
```

建议按下面表格的阅读顺序，在场景类的 `main`、`ToolScenario.run` 中的工具体入口、`HostSession` 的策略检查处下断点。先读 `ControlledAiServiceDemo`，看真实模型 → 工具 → 模型循环，再读单工具案例；最后读 OS 隔离案例。

每个类独立定义策略或隔离配置，包含预期结果断言；不满足预期会抛异常退出。`SecurityDemos` 只负责选择固定列出的类，不承载安全策略。原有 `command`、`sandbox` 等演示入口仍保留。

| 包 / 类 | 职责 |
|---|---|
| `io.agt.examples.asi02` | 工具许可、身份、参数、改写、审批及工具内部业务边界 |
| `io.agt.examples.asi05` | 执行工具许可、固定程序约束、原生沙箱及宿主 JVM 边界 |
| `io.agt.examples.support.ToolScenario` | 统一建立可信会话、包装真实 ToolExecutor、记录审计、校验副作用计数 |
| `io.agt.examples.support.CommandScenario` | 合成工作区、固定命令接线与临时文件清理 |

公共接线只减少重复代码，不模拟 AGT 的 ALLOW / DENY。单工具示例使用高级 `guardTool` / `guardCommand`，覆盖工具前后，不代表它自动治理输入、模型和最终输出；完整八类干预点由受控 AI Services 案例验证。

## ASI02：工具滥用

源码目录：`examples/src/main/java/io/agt/examples/asi02/`。

| 阅读顺序 / 类 | 输入或触发 | 预期行为 / 观察点 |
|---|---|---|
| `ControlledAiServiceDemo` | 本机模型调用 `lookup(tenant-a, original)` | 实际工具收到 modified；第二次模型与调用方收到 masked:modified；模型 2 次、工具副作用 1 次，关闭会话后八类干预点齐全 |
| `DefaultDenyDemo` | 已注册执行器，但策略工具清单为空 | tool_not_allowed；工具体进入 0 次、执行预算 0 |
| `PermissionDeniedDemo` | 工具允许，宿主身份没有 read | permission_denied；工具体进入 0 次 |
| `StrictJsonDemo` | 畸形 JSON、重复键、错误类型、缺失必填、未知字段、非法枚举 | 解析错误 invalid_json，其余 tool_arguments_denied；每个输入独立会话、副作用 0 |
| `NumericConstraintsDemo` | quantity 要求整数 1–10；tags 要求 1–2 个字符串 | 合法值执行；0、11、1.5 和 3 个标签均拒绝 |
| `TenantBindingDemo` | 宿主 tenant-a，模型参数 tenant-b | context_binding_denied；模型参数不能建立可信身份 |
| `ArgumentRewriteDemo` | query 从 original 改成 modified，结果 secret 改成 masked | 在工具体断言实际参数；交付结果也断言，不能只看裁决 TRANSFORM |
| `RewriteRevalidationDemo` | 把 original 改成枚举外 delete-all | 改写后重验失败 tool_arguments_denied，副作用 0 |
| `ApprovalAllowedDemo` | 当前动作要求审批，宿主批准请求 | 工具体进入 1 次；审计包含审批结果和动作关联 |
| `ApprovalDeniedDemo` | 宿主拒绝请求 | approval_denied，工具体进入 0 次 |
| `MissingApprovalResolverDemo` | 要求审批但未配置审批器 | approval_unavailable，工具体进入 0 次 |
| `ApprovalTimeoutDemo` | 审批 Future 不完成 | 演示期限 500ms 后 approval_timeout，工具体进入 0 次 |
| `ApprovalExceptionDemo` | 宿主审批器抛异常 | approval_failed，工具体进入 0 次 |
| `InternalHttpDemo` | 已授权工具体直接调用 JDK HttpClient | 真实回环服务收到 1 次请求；内部 HTTP 不自动产生第二个 AGT 治理动作 |
| `SqlBusinessAuthorizationDemo` | 参数 tenant-a 合法，SQL 查询 id=2 的 tenant-b 数据 | 缺少 SQL 租户条件的版本返回别的租户数据；增加 `AND tenant=?` 的版本得到 not-found |
| `FileSideEffectDemo` | 工具体先写临时文件，再返回受限文本 | POST_TOOL_CALL 拒绝 deny-result，但文件内容仍已改变；不能把结果阻断称为副作用回滚 |

### 跟一次实际参数流

在 `ArgumentRewriteDemo` 中，模型候选参数是 `{"tenant":"tenant-a","query":"original"}`。`ToolScenario` 的宿主身份持有 tenant-a 和 read；StructuredPolicy 先做工具许可、权限、闭合参数与租户绑定检查。PRE_TOOL_CALL 的 ContentRule 将 query 改成 modified，运行时重验有效参数，然后原执行器收到改写后的 request.arguments。

工具返回 `secret:modified`。POST_TOOL_CALL 把 secret 改成 masked；调用方收到 `masked:modified`。审批允许的审计仍可能显示原策略裁决 DENY、审批 approved：审批是带审批要求的拒绝结果，宿主批准后才执行。EXECUTION 记录的 succeeded / refused_before_execution / failed_after_execution 才是实际执行结果，不能把策略裁决与执行记录混为一谈。

公共接线打印干预点、裁决、改写是否应用、审批、执行结果、策略版本与动作 ID；默认不打印完整参数或敏感结果。学习例中出现的 tenant、secret 都是合成值。

在 `RewriteRevalidationDemo` 中，只改变替换目标为 delete-all，其余不变。工具枚举不允许该值，所以工具体不会进入。这能区分“记录了一次改写裁决”和“安全地落实了改写”。

### 审批和业务授权是不同责任

审批示例使用 `ApprovalResponse.allow(request)` / `deny(request)` 绑定 SDK 提供的当前请求，不是仅批准工具名。示例用固定宿主决定代替真实人工系统，不能宣称演示了审批人认证或 Web UI。完整的审批参数变化、重复使用保护由核心回归测试承担。

`SqlBusinessAuthorizationDemo` 特意展示一个漏洞：PreparedStatement 防止参数作为 SQL 语法执行，但遗漏 tenant 条件仍会越权。AGT 参数租户绑定与 SQL 行级条件各自承担责任；示例不把读取不到数据库行称为 AGT 的拒绝。第二个版本的结果是业务 not-found，而非治理 DENY。

`InternalHttpDemo` 不让模型指定 URL，只用于证明：进入已允许的工具以后，HTTP、JDBC、Files 没有被 guardTool 自动 hook。生产工具需要目标地址许可、连接器权限、存储授权或隔离进程；进程文件/网络隔离也不理解 SQL 表里的租户关系。

## ASI05：意外代码执行

源码目录：`examples/src/main/java/io/agt/examples/asi05/`。

| 阅读顺序 / 类 | 触发 | 预期行为 / 已验证边界 |
|---|---|---|
| `UnapprovedExecutionToolDemo` | 模型请求未获策略许可的 exec | tool_not_allowed；危险执行器进入 0 次，不运行候选代码 |
| `FixedArgumentInjectionDemo` | 固定 Java 程序，仅允许 option=-version；传入带 `; touch` 的值 | tool_arguments_denied，未启动进程，注入标记不存在 |
| `ExecutableIntegrityDemo` | 预期 SHA-256 故意不匹配 | 构造失败 command_integrity_denied；未修改 JDK 或启动子进程 |
| `WorkspaceWriteIsolationDemo` | HOST/DENY；先写工作区，再写工作区外 canary | 工作区内成功，工作区外 sandbox_exit_denied，canary 内容未改变 |
| `ReadOnlyWorkspaceDemo` | readOnlyWorkingDirectory 后尝试写入 | sandbox_exit_denied，目标文件不存在；SDK 临时目录写权限另有边界 |
| `NetworkIsolationDemo` | 固定 curl 访问回环服务，先 ALLOW 再 DENY | ALLOW 收到 OK 且服务端接入 1 次；DENY 失败且接入 0 次，避免把服务不可达误判为隔离成功 |
| `StrictReadIsolationDemo` | DECLARED_ONLY 尝试读取未声明的外部 canary | 当前本机 alpha.1 返回 sandbox_unavailable，目标未启动；若 profile 可用，必须实际拒绝读取；无 HOST 回退 |
| `TimeoutQuarantineDemo` | 固定 sleep 超出 100ms 期限，再用新会话尝试复用 | sandbox_timeout；再次执行 sandbox_cleanup_unconfirmed，backend 永久隔离 |
| `InProcessJvmBoundaryDemo` | 已允许的 Java 工具在宿主 JVM 直接 Files.writeString | 宿主临时文件写入成功，证明固定命令后端不会约束同 JVM 工具代码 |

五个原生类在 macOS 使用真实 SDK；其他平台明确跳过，不能当作隔离测试通过。固定命令模板、路径、程序和网络策略来自宿主静态配置；示例 shell 字符串都是审核过的固定 literal，模型不提供脚本。网络 ALLOW 仅是本机正向对照，默认示例配置使用 DENY。

六参数 FixedCommand 是有界的本机进程入口；它检查程序与参数，但不是 OS 沙箱。七参数入口注入 SandboxCommandBackend，执行才进入原生隔离后端。自动计算本机程序摘要只适合演示；生产预期摘要应来自可信的制品清单，摘要也不覆盖加载的动态库和工具链。

HOST 允许读取宿主账户能读到的文件，**只验证写约束和配置的网络约束，不能称为机密读取隔离**。当前 alpha.1 的严格读取 profile 在已测 macOS 15.5 上不可用；适配器拒绝它，而不是自动降级。取消/超时后中断 SDK 线程并隔离 backend，不保证 OS 后代已全部回收。详细边界见 [沙箱接入指南](sandbox-guide.md)。

## 验收方式

运行 `bash scripts/verify-security-demos.sh` 编译、检查格式并运行两个包全部独立场景。也可以在 IDE 逐个 main 运行：预期拒绝属于通过；未预期的结果会导致进程非零退出。运行时产生的文件、数据库与服务只用于演示，完成后关闭或清理。

本组示例的目标是帮助阅读并复现当前能力，不把场景数量换算成 OWASP 覆盖率；它不提供 JVM 沙箱、任意生成代码安全执行或跨平台原生保证。

2026-10-14 本机 macOS 15.5 arm64 验收：JDK 17.0.11 / 21.0.4 各通过全部 25 个场景类（ASI02 16 个、ASI05 9 个），核心 74 + 框架 114 + 沙箱 28 = 216 项测试，失败 / 错误 / 跳过均为 0。新增审批工作线程超时与宿主 stage 异常的确定性回归；MkDocs 严格构建、教学导航和代表性 Mermaid 浏览器验证通过。具体记录见 [当前验收数据](evidence/sandbox-verification.json)。
