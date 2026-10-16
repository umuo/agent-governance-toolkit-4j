# 原生接入的边界与资源优化

本轮基于 `11fd857`，落实空回执补偿、有界序列化与 Reactive 预取/缓冲三项优化。
服务级 Options、反射/SPI 缓存和 Spring Boot Starter 不在本轮范围。

## 行为与验收

| 范围 | 行为与验收要求 |
| --- | --- |
| 空补偿回执 | `arguments` 必须提供，`receipt` / `text` 为 null 时省略；可选字段允许缺失，required 仍拒绝；补偿继续独立授权、审批并计入工具预算 |
| POJO 写入 | 在构建完整 JSON 字符串前停止超限写入；约束总字符、节点、深度、容器和数字；不得读取完全部后再以失败冒充提前限额 |
| 原生类型语义 | 对照固定版本原生 JSON 检查私有字段、Jackson 注解、日期、枚举、sealed 类型、嵌套 byte[] 和非字符串 Map 键；恢复继续使用原生 codec 并检查往返形状 |
| Reactive 预取 | 外层每次最多申请 32 项；实时待交付队列最多 32 项，按下游消费情况补充 credit；拒绝无 credit 的上游发送 |
| 严格交付 | 分批读取完整输出后做最终检查，保留取消、期限和关闭审计语义，Mono 自有会话关闭成功后才交付单值 |
| 文本缓冲 | 拼接文本作为 OUTPUT 的 `text`；`items` 不再重复记录每个 String 分片；超过 4096 个文本分片但仍在事件/字符额度内的调用可正常完成 |
| 事件缓冲 | 实时历史保存交付前 JSON 快照，不保存已交付原始事件；消费者修改预览对象不得改变最终检查依据；严格模式保留恢复类型所需的原对象 |
| 生命周期 | 取消/失败立即清理待交付与历史缓冲；完成后释放原始调用参数及执行闭包；无额外 demand 时也交付错误信号 |

## 选型与约束

固定版本 `Json.JsonCodec` 只提供返回完整 String 的 `toJson`，没有 Writer/Generator 扩展接口。
因此用 Jackson 的 `JsonGeneratorDelegate` 和有限 Writer 执行有界写入，复用其成熟的字段、注解与 serializer 机制，
对齐原生默认 mapper 配置，并通过公开 `PolymorphicTypes` 复用 sealed 元数据。没有全局替换 codec 或访问框架私有字段。
共享限制由 `JsonValues.MAX_*` 定义：1,048,576 字符、8192 节点、32 层、4096 容器项、128 字符的数字表示。
超大整数先检查位数，避免仅为了拒绝它而分配完整十进制文本。

非默认 `JsonCodecFactory` 需要另行适配其编码约定。自定义 getter/serializer 的任意 Java 代码分配和副作用不由 JSON Writer 隔离。
容量限定针对序列化与策略数据，不声称是任意 Java 对象图或 JVM 的内存沙箱。

Reactive 复用原生 Publisher 与现有运行时状态机，只调整外层 credit 与交付队列。
严格模式为完整检查继续读取，实时模式在交付队列达到窗口后停止补充 credit。
推送式 Provider 可继续产生数据；其底层缓冲仍由模型流容量限制约束，不声称网络生产端能够随 demand 暂停。
最终文本检查仍匹配跨片段内容；实时预览依然未经最终检查，不能撤回先前交付。

## 验证方式

`NativeCompensationTest` 覆盖 void 补偿的允许、拒绝、required、预算和审批。
`BoundedValuesTest` 用计数 serializer 与后续 getter 验证在写入期间停止，并对照原生 codec。
`PublisherResourceTest` 覆盖慢消费者预取、5000 分片、取消、跨片段拒绝、可变预览快照、错误信号和违规上游。
保留原有原生接入、结构化结果、事件、Mono/Flux、严格流和 HTTP/SSE 测试，并在 JDK 17/21 全量验证。

## 本机验收记录

本轮新增 22 项回归用例（7 项有界值、9 项 Publisher 资源行为、6 项 void 补偿）。

| 环境 | 全量 clean verify | 结果 |
| --- | --- | --- |
| Azul JDK 17.0.11 | 通过 | 363 项，362 通过、1 项按条件跳过 |
| Azul JDK 21.0.4 | 通过 | 363 项，362 通过、1 项按条件跳过 |

跳过项为真实付费 Provider 测试，未调用外部付费模型。本地 HTTP/SSE、原生沙箱和既有兼容性回归均执行。
两套 JDK 的 native、同步、异步、严格流式、数据、命令、沙箱和 security 离线示例共 16 组全部通过。
文档严格构建通过，已在浏览器检查优化页面、导航及 Mermaid 渲染。

这里的性能证据是确定性的行为边界：自定义序列化器在上限前停止，单次上游 request 不超过 32，
慢消费者保持有限待交付队列，5000 个文本分片可完成完整检查。
未进行 JMH 或生产负载压测，不提供堆内存降幅、吞吐提升或延迟降低的量化结论。

## 后续阶段：服务配置、元数据缓存与 Spring Boot

目标是让宿主复用原生 Builder、通过不可变 Options 按服务覆盖流式策略，并在 Boot 中减少接线代码。
前文的“不在本轮范围”与验收数字仅对应 `84cccfe`，以下为下一阶段规格。

- `AiServiceOptions` 统一 delivery、streamingLimits、publisherPrefetch；默认实时、1,048,576 字符/10,000 事件、预取 32。
  预取只允许 1..1024；非空与有限额度在创建配置时验证；旧 Builder 和单独 guardStreamingModel 保持原契约。
- 同一服务的模型轮次与 Reactive 聚合共用所选额度；服务间不得串用配置。
- 方法参数类型、可信参数位置和 SPI 选择在服务装配时解析；事件 getter/构造器用 ClassValue 缓存。
  缓存不得包含用户参数、身份、策略结果或事件正文；每次调用仍运行完整治理。SPI 快照随服务实例释放，允许不同宿主 ClassLoader。
- 服务缺少对应模型、Publisher 缺少可交付适配器时在装配阶段失败。
- Starter 采用 Spring Boot `AutoConfiguration.imports`、配置属性和条件 Bean，复用本项目 Boot 3.5.16 与 Java 17。
  独立模块提供 `GovernedAiServices` 工厂；宿主提供 `AgentControl` 和 `TrustedContextResolver`，以原生配置回调创建普通 `@Bean`。
  不默认推断身份或创建放行策略。不存在 control 时退让；存在 control 而缺身份解析器时报启动错误；显式禁用或宿主自定义工厂时退让。
- 验收包括按服务覆盖与隔离、模型/聚合限额、慢消费预取、缺模型提前失败、SPI 装配后不反复发现、缓存不省略检查，
  以及真实 Boot 上下文属性绑定、Bean 覆盖、禁用、缺失身份与正常调用的关闭审计。

选型依据：[LangChain4j 原生 Spring 装配](https://docs.langchain4j.dev/tutorials/spring-boot-integration/) 已提供模型和工具选择机制；
[Boot 条件自动配置](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html) 支持标准发现与宿主覆盖。
本阶段复用这些机制提供显式治理工厂，避免全局修改未由工厂创建的 AI Service，基础模块不新增 Spring 依赖。

### 后续阶段验收记录

新增 15 项测试：6 项服务 Options/SPI 行为测试、9 项 Boot 上下文与工具审批测试。
最终代码在 Azul Java 17.0.11 与 21.0.4 分别执行全量 `clean verify`：各 378 项，377 通过、1 项真实付费 Provider 按条件跳过。
本机 HTTP/SSE 和原生沙箱均执行；两套 JDK 共 16 组离线 CLI 示例通过。
MkDocs 严格构建通过，浏览器确认 Starter 页面、新导航与首页 Mermaid 实际渲染。
未执行 JMH 或生产负载基准，不声称缓存带来具体吞吐或延迟增益。
