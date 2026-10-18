# 可插拔注入检测与来源标记

本能力在确定性策略之外增加可选检测链：宿主在策略里声明检测位置、检测器版本、期限、容量和故障语义，再绑定实际实现。没有声明时不创建检测工作池，也不调用外部分类服务。注册插件本身不等于启用检测。

检测器只返回 CLEAR / SUSPECT / UNKNOWN。CLEAR 表示本次检测未命中，不代表内容可信、安全、没有注入，也不能增加工具权限、改变资源所属或降低保密等级。原有参数、审批、委托、数据流及预算约束继续执行。

## 选型与验证范围

检查了两个现成方案：

| 候选 | 适配点 | 本轮选择 |
| --- | --- | --- |
| [LLM Guard](https://github.com/protectai/llm-guard)，[MIT 许可](https://github.com/protectai/llm-guard/blob/main/LICENSE) | 提供 PromptInjection 分类器及 HTTP API，可作为独立检测后端 | 复用官方 `/analyze/prompt` 协议；Java 保留宿主身份、权限和交付控制 |
| [NeMo Guardrails](https://github.com/NVIDIA-NeMo/Guardrails)，[Apache-2.0 许可](https://github.com/NVIDIA-NeMo/Guardrails/blob/develop/LICENSE-Apache-2.0.txt) | 覆盖会话编排及可编程护栏 | 当前工程已有 Java 运行时，不增加另一套 Python 编排层；仍可通过宿主 SPI 对接 |

本轮阅读了官方协议、分类器与风险分数源码，并用真实本机 HTTP 验证 Java 适配器。没有下载模型权重、部署真实分类模型或测量检测准确率。仓库当前发布维护周期本次未核验，不能将 API 适配等同于长期支持承诺。分类器的模型许可、版本、语言覆盖、误报与漏报仍须部署者审核，不能把协议测试当成防注入准确率证明。

## 集中声明和绑定

现有结构化策略新增可选 `injectionChecks`。各字段必填；缺失或越界会在策略加载/运行时初始化时拒绝。以下片段可放入完整策略文件；`interventionPoints` 还需包含应用使用的模型、工具和生命周期检查点：

```yaml
injectionChecks:
  - id: external-context
    detector: llm-guard
    revision: deberta-v2-reviewed-1
    points: [post_data_access, post_tool_call]
    timeoutMillis: 2000
    maxCharacters: 8192
    failure: DENY
```

| 字段 | 含义与范围 |
| --- | --- |
| id | 唯一检查标识，遵循已有 1–128 字符治理标识符规范 |
| detector / revision | 宿主插件名和审核版本；绑定必须完全一致 |
| points | 已配置的干预点；禁止 startup/shutdown，避免依赖外部检测才能退出清理 |
| timeoutMillis | 单次检测总等待期限，包含排队，1–30000 毫秒；还受任务剩余时间限制 |
| maxCharacters | 实际内容规范化 JSON 的字符上限，1–1048576；不截断后声称完成检查 |
| failure | DENY：未知/异常/超时/过载/超限均拒绝；WARN：记录不确定原因后继续，不记为 CLEAR |

每份策略最多 16 个检查，按声明顺序执行。`StructuredPolicy.builder(...).injection(check)` 是对应 Java 入口，旧 Document 构造方式和无检测策略保留兼容。

```java
var detector = new LlmGuardDetector(
        URI.create("https://guard.internal/analyze/prompt"),
        () -> System.getenv("LLM_GUARD_TOKEN"),
        Duration.ofMillis(1500));
var control = AgentControl.fromPath(Path.of("config/policy.yml"))
        .detector("llm-guard", "deberta-v2-reviewed-1", detector)
        .audit(auditSink)
        .build();
```

宿主也可直接绑定 `InjectionDetector`，例如调用已有内部分类器。`DetectionInput` 提供当前干预点、ORIGINAL/EFFECTIVE 阶段、来源、session/action ID 和只读内容。返回 `DetectionResult`，不要返回指令、改写建议或包含原文的自由文本。插件可能被并发调用，宿主实现须线程安全；插件资源生命周期由宿主管理。

策略存在检测声明但插件缺失或版本不匹配时，初始化以 `injection_detector_binding_denied` 失败。`revision` 是可信宿主对实现/模型/部署配置的声明，不是远端权重的独立证明；模型、阈值、分片策略等变更应审核并更新版本。

## 来源与信任不会从正文升级

| 实际边界 | 来源 / 信任 |
| --- | --- |
| 用户输入 | USER_INPUT / UNTRUSTED |
| 模型完整上下文 | MODEL_CONTEXT / MIXED，不自动豁免自报 SYSTEM 角色 |
| 模型返回 | MODEL_RESULT / MODEL_GENERATED |
| 工具参数 | TOOL_ARGUMENTS / MIXED |
| 已注册工具返回 | TOOL_RESULT / UNTRUSTED |
| 检索、记忆读取结果 | RETRIEVAL_RESULT 或 MEMORY_RESULT / UNTRUSTED |
| 数据输入、最终输出 | DATA_INPUT 或 CALLER_OUTPUT / MIXED |

来源由受控干预点和实际绑定资源推导。工具来源使用注册名称，数据来源使用完整资源描述符的摘要；扫描内容剥离外层资源身份、操作与片段控制字段。结果内部的文档元数据和消息属性仍是不可信内容。记忆中保存的 SYSTEM 消息也不能变成可信宿主指令。

这些标记描述当前检查边界，不是字节级污点跟踪、作者签名或内容真实性认证。已有[版本化片段来源](document-versions.md)与[数据流等级](persistent-data-flow.md)仍承担各自职责；保密等级与注入风险不能相互替代。

## 调用、改写与交付

先执行确定性策略。强制模式下已经明确拒绝的请求不会再发送给检测服务。其他请求检查原始内容；策略实际改写后，还会对最终内容重新检测。SUSPECT 在 ENFORCE 模式拒绝，以 `injection_detected` 审计；无需等待模型自行拒绝。

EVALUATE_ONLY 可记录命中并继续，属于明确的评估模式，不能用于导出签名委托。检测执行失败仍按声明的 failure 处理；WARN 不会绕过权限、数据流或会话期限检查。生产阻断路径应使用 ENFORCE 和 DENY，不能把 WARN 的可用性选择描述成检测通过。

配置 POST_MODEL_CALL 或 OUTPUT 检测后，原生实时流、TokenStream 和 Reactive 实时交付会以 `injection_strict_stream_required` 拒绝，要求显式 STRICT。直接 `guardStreamingModel` 原本就使用严格缓冲。OUTPUT 针对受控 Agent 的最终交付；原始模型包装的响应检查应配置 POST_MODEL_CALL。完整输出命中时，严格模式不会先交付部分文本再报告拒绝。仅做输入/检索检测时可以继续使用原有实时语义。

工具返回检测发生在工具已运行之后，不能撤销已经发生的副作用；它阻止受控结果继续进入下一步。直接绕过 SDK 的模型、工具、HTTP 或文件操作不在该保护范围内。

## 有界运行与故障

每个运行时共享 2 个检测线程、32 个排队名额。检测声明在启动时冻结，严格流式判断使用同一快照。计时从入队前开始，等待包含排队时间；前置权威授权查询仍受原有 RPC 和任务期限约束。超时、关闭或撤销会停止等待并请求中断。忽略中断的插件仍占据原线程，不自动扩容，也不能靠迟到 CLEAR 恢复已拒绝操作。检测线程不继承可调用工具的会话绑定；插件应调用独立分类后端，不能递归调用当前 Agent 的治理模型。

这是宿主插件隔离边界，不是任意 Java 代码沙箱。不能强杀任意插件线程，也不证明 HTTP 超时后远端分类器已经停止。分类后端的计算配额和费用需要单独管理，不自动计入生成模型的 Token 账本。

审计只增加检查/检测器版本摘要、来源、信任、阶段与机器原因码，不写检测正文、原始服务异常或自由文本理由。CLEAR 标记对应成功的检查，不表示整条链安全；多检查/改写场景必须同时查看其他阶段的未知、错误或命中标记。HTTP 检测服务会收到宿主选择的内容：必须由宿主批准其数据边界、认证与日志配置，不能默认向公共第三方发送敏感数据。

## LLM Guard HTTP 契约

适配器对固定 HTTPS `/analyze/prompt` 端点 POST `{"prompt": "实际内容"}`，使用宿主 Bearer 凭据，不跟随重定向。显式 `loopbackForTesting` 只接受字面回环地址，供本机测试使用。内容本身不能指定 URL、凭据或抑制扫描器。

当前要求服务只返回 PromptInjection 的结果，且响应字段严格为 `sanitized_prompt`、`is_valid`、`scanners`。返回文本必须等于实际扫描输入；不采纳改写，也不使用改写后文本的通过结果放行原文。扫描器缺失、额外扫描器、错误类型、越界/矛盾分数或其他不完整结果记为 UNKNOWN。服务不可用、非 200、重复 JSON 字段、非法 UTF-8 或超过 64 KiB 响应进入检测错误。全响应期限包含收到头之后的正文读取。

根据[官方风险分数实现](https://github.com/protectai/llm-guard/blob/main/llm_guard/util.py)，原值是相对服务端阈值的 -1 到 1；适配器换算为 0 到 1 的相对分数，不当作概率。`is_valid` 与分数矛盾时不能记为 CLEAR。

[官方 PromptInjection 实现](https://github.com/protectai/llm-guard/blob/main/llm_guard/input_scanners/prompt_injection.py)包含模型长度限制和不同匹配方式。服务端必须按实际输入长度审核分片/截断策略；客户端字符上限不是 tokenizer 覆盖证明。应部署专用 PromptInjection 配置，审核服务日志、模型权重、阈值和语言覆盖，而不是依赖默认配置名称推定保护范围。

## 委托与验证

检测声明进入原有签名策略摘要，包含检查位置、插件版本、故障语义和容量/期限。接收 Agent 删除声明或弱化故障策略时，`delegation_runtime_policy_denied` 拒绝；缺少实际绑定时运行时不能启动。未启用检测的策略保持原指纹。实际插件执行仍依赖可信接收宿主，不提供恶意节点的远端执行证明。

测试覆盖真实 HTTP 协议、超时正文、重定向、响应上限、缺失扫描器和输入回显；独立线程超时/撤销/迟到结果与资源占用；原生同步/异步 RAG、记忆角色混淆、工具返回、严格流式无预览泄露，以及签名委托无法移除检测。完整回归与真实边界见[注入检测验收记录](evidence/injection-detection-governance.json)。真实分类模型的准确率、部署验证及生产审计治理仍待完成。
