# 流式增量护栏实现验收

2026-10-19 实现首阶段 SDK 能力：Core 有界局部字面检查、原生 GUARDED 交付及 Starter 配置。没有新增演示原型，也没有改动 examples-live 案例。语义慢轨、正则及流式替换不在本次范围。

## 实现与选型

Core 使用 JDK 字面窗口匹配，限制最多 256 个词、总长 65536、单词长 2048 UTF-16 单元。最大局部缓冲 65536 单元，Overlap 由词长推导。与引入 AC 库相比，这个阶段避免外部依赖及私有状态续接；以下测试验证有界窗口重扫的跨块行为，不宣称实现 AC 自动机或已验证第三方库性能。

增量正文经过局部门后才送入框架；provider thinking、partial tool call、raw event 被抑制。完整响应通过模型检查后重放权威工具调用事件，解决原生 TokenStream 工具结果缺失问题，工具执行仍经过现有授权。最终无工具轮次额外经过完整正文投影的 OUTPUT 检查后释放尾部，服务原有的最终/聚合检查继续执行。

原生事件和文本队列在 GUARDED 下限制为服务的 publisherPrefetch（宿主更小的配置仍保留），外层队列继续使用同一上限。推送型 provider 无法暂停时，队列溢出使受控流失败并尝试取消；容量错误不会等待额外 demand。

## 验收映射

| 需求 | 本次证据与边界 |
| --- | --- |
| SG01 | AiServiceOptionsTest、NativeStreamingCompatibilityTest 保留 REALTIME/STRICT 基线；GuardedStreamingTest 验证显式配置及缺失/改写配置提前失败；Starter 上下文绑定测试验证 Java 同源校验。旧模式 EnumSource 显式保留两种模式，GUARDED 用独立规则场景测试 |
| SG02 | TokenStream 可控 provider 完成前已交付安全前缀；正常/EVALUATE_ONLY 完成后拼接结果无重复；既有 STRICT 回归继续通过 |
| SG03–04 | ChunkBufferEngineTest 枚举长词、中文/代理对、跨标点的所有两段切分，每次插入虚拟计时；逐字符路径与重叠词覆盖；命中区间没有任何字符外发 |
| SG05 | 可控调度器验证短流、超时保留尾部、无重复空调度；正常完成 flush，关闭/取消/拒绝丢弃；计时任务用代际编号防止已取消任务干扰下一批 |
| SG06 | 代理对跨回调拼合、非法阈值/等待时间、空规则和词表/词长/缓冲容量上限失败；最终响应长度和 UTF-16 摘要一致性检查 |
| SG07 | Core 并发输入/关闭 40 轮压力测试、任务清理、会话关闭、客户端取消、拒绝、迟到 handle 取消及晚到结果抑制；不把有限竞争测试视为所有线程交错的形式化证明 |
| SG08 | handler、TokenStream、Flow.Publisher、Flux 共用决策；无额外 demand 的 DENY 错误传递；推送超出原生/外层队列信用时失败并取消；非正文通道未成为预览旁路 |
| SG09 | 完整 OUTPUT 拒绝不释放尾部；权威正文不一致失败；已知替换配置和原生输出护栏提前拒绝；EVALUATE_ONLY 继续原文并审计；自定义 PolicyEvaluator 暂不支持装配 GUARDED |
| SG10 | 独立工具轮次 ABC / DEF 不跨轮匹配，DENY 轮次没有工具副作用/下一轮模型；局部 actionId 与完整模型审计关联；既有授权、审批、预算、身份隔离与自动会话回归复用 |
| SG11 | 局部审计包含阶段、版本、规则 ID、偏移及耗时；无输入原文，重叠扫描不重复记录同一次命中；必需局部审计失败时零对应文本外发 |
| SG12 | 固定 LangChain4j 1.20.0 官方 OpenAI 客户端连接本机 SSE：跨片段拒绝后 HTTP 写入观察到断开；JDK 矩阵和文档构建结果见下方 |

## 运行方式与结果

在 JDK 17/21 下分别执行（JAVA_HOME 指向相应 JDK）：

~~~bash
mvn -o -Dmaven.repo.local=/Volumes/Nas/envs/maven/repository clean verify
~~~

文档验证：

~~~bash
.cache/docs-venv/bin/mkdocs build --strict
~~~

最终全项目结果：JDK 17.0.13 与 JDK 21.0.7 的 `clean verify` 均通过；每轮 493 项测试中 491 项通过、2 项跳过（可选性能观察与既有真实模型条件测试）。Spotless 检查通过。MkDocs 严格构建通过，`git diff --check` 通过。构建没有启动长期服务或调用付费模型。

| 模块 | 测试总数 | 失败 / 错误 | 跳过 |
| --- | --- | --- | --- |
| agt-core | 81 | 0 / 0 | 0 |
| agt-langchain4j | 219 | 0 / 0 | 1 |
| Starter | 10 | 0 / 0 | 0 |
| agt-sandbox | 48 | 0 / 0 | 0 |
| examples-live | 135 | 0 / 0 | 1 |

机器可读摘要见 [验收结果](evidence/guarded-streaming-verification.json)。性能观察另行显式运行过一次，不算入上表的常规测试通过数。

## 固定负载观察

通过显式开启 `GuardedStreamingBenchmarkTest` 运行，普通回归默认跳过该计时观察：

~~~bash
mvn -o -Dmaven.repo.local=/Volumes/Nas/envs/maven/repository \
  -pl agt-langchain4j -am test -Dagt.streaming.benchmark=true \
  -Dtest=GuardedStreamingBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false
~~~

原始结果见 [JSON 记录](evidence/guarded-streaming-benchmark.json)。JDK 17.0.13，16 个 8 单元词，2 个并发流，每流 1280 UTF-16 单元，provider 每 16 单元等待 250000 ns。数据包含框架、计时与同步审计开销，运行一次，没有 warmup 或统计置信区间；用于确认流式价值和局部窗口占用，不作为生产吞吐提升结论。

| 消费回调延迟 | 模式 | 首块 ms | 总耗时 ms | 字符/秒 | 局部检查次数 | 局部检测总 ms | 局部窗口峰值 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0 ms | REALTIME | 1.04 | 26.98 | 94899 | 0 | 0 | 不适用 |
| 0 ms | GUARDED | 1.10 | 27.89 | 91802 | 130 | 0.204 | 27 |
| 0 ms | STRICT | 26.39 | 26.44 | 96808 | 0 | 0 | 不适用 |
| 1 ms | REALTIME | 0.75 | 127.94 | 20009 | 0 | 0 | 不适用 |
| 1 ms | GUARDED | 1.25 | 110.78 | 23109 | 130 | 0.083 | 27 |
| 1 ms | STRICT | 26.37 | 27.68 | 92478 | 0 | 0 | 不适用 |

该观察的慢消费者是同步回调等待，不能替代 Publisher 背压测试。最大词表/最大词长的长期吞吐、更多并发流、真实远程模型延迟和生产负载压测仍未执行。50 ms 是可配置的调度目标，不是端到端 SLA。
