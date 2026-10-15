# v0.6 SDK alpha.2 接入验收

2026-10-15，固定消费 Central `io.github.umuo:agent-sandbox-sdk:0.1.0-alpha.2` 的正式二进制。没有编译 sandbox-demo 工作区替代发布版本，也没有使用未发布 API。版本未升级 AGT 制品号，仍为 0.6.0-SNAPSHOT。

发布的 79 个 Java 源文件与本轮审查的本地 SDK 源码逐一一致。制品 SHA-256：`f21bd0791b6f38641ac75062f4c6d9102d90eb9ff5dac836f1fc9866bc3e9769`。来源：[Central POM](https://repo.maven.apache.org/maven2/io/github/umuo/agent-sandbox-sdk/0.1.0-alpha.2/agent-sandbox-sdk-0.1.0-alpha.2.pom)；完整环境与计数见 [验收 JSON](evidence/sandbox-verification.json)。旧版原始结论保留在 [alpha.1 历史](sandbox-verification-alpha1.md)。

## 与 SDK 维护要求的对应

| 需求 | 当前接入与证据 | 已验证边界 |
|---|---|---|
| SDK-01 实际策略预检 / 严格读取 | prepare 调用 client.validate；预检拒绝时目标次数与工具预算均为 0。真实严格模式允许 printf/sh 和显式声明 JDK runtime 的 Java，拒绝外部 canary | macOS 15.5 arm64，目标 JDK 17 / 21；未验证 Windows/Linux 原生 |
| SDK-02 取消与独立回收 | 使用 start 返回的执行句柄；Future 取消实际传入 SDK control；四类不合作任务、晚到取消继续回归；workerFinished 前占住容量 | completion 与 cleanup 独立；macOS 进程树仍 BEST_EFFORT，正常退出的进程 UNKNOWN 被明确接受，不作为强回收证明；取消/超时仍保守隔离 |
| SDK-03 输出超限终止 | SDK 请求强制 TERMINATE + retained、每流和聚合额度；真实单进程持续输出在 5 秒期限前（验收阈值 3 秒）终止，结果不交付 | 不是原子终止或副作用回滚；停止 shell 子进程后，shell 仍可能短暂继续执行 |
| SDK-04 能力维度 | SDK admission 按实际能力拒绝硬限制；测试 coarse FULL 也不能放行不支持的 memory 硬限制 | 只有本机原生证据；未运行其他平台的限额强制测试 |
| SDK-05 稳定错误 / 脱敏 | 10 类 SDK code 经真实 SDK 执行路径映射，不匹配自由文本；合成 debug marker 不出现在公开异常 | 不记录 SDK debugMessage/debugCause；并不认证所有宿主自定义日志实现 |
| SDK-06 严格环境 / 私有目录 | 默认 PATH 只含程序父目录；宿主可固定多个工具目录；无额外环境、stdin 或原文回调。真实 env / live Bash 验证 PATH 和私有 HOME 清理 | 工具目录必须宿主保护；没有完整父进程危险变量注入矩阵，不能声称任意工具链完整性已验证 |
| SDK-07 硬限额与隔离机制 | 重建请求保留全部 resourceLimits、requiredIsolation；不支持的硬限制在工具执行前拒绝 | macOS 不提供这些硬限额；未证明 VM / cgroup 的资源强制，也未开放核心任意生成代码执行 |

## 自动化测试与真实隔离

| JDK | core | LangChain4j | sandbox 契约 / 原生 | 核心及沙箱小计 | live 发现 / 通过 / 跳过 | 全部发现 / 通过 |
|---|---:|---:|---:|---:|---:|---:|
| 17.0.11 | 74 | 116 | 39 / 9 | 238 | 38 / 37 / 1 | 276 / 275 |
| 21.0.4 | 74 | 116 | 39 / 9 | 238 | 38 / 37 / 1 | 276 / 275 |

两个 JDK 都通过 clean verify，没有失败或错误。唯一跳过项是需要显式设置 `AGT_LIVE_TEST=true` 的真实收费 provider 测试；本轮未启用，不算模型服务验收通过。其余测试使用本机 HTTP/SSE 模拟端点、合成数据和临时工作区，无外部模型 key。

SDK 契约新增 16 项（预检、资源要求、10 类稳定错误、协作取消、两类回收异常），加既有 23 项；fake runner 只证明接入行为，不证明内核隔离。9 项真实 Central 原生测试覆盖 HOST 写入/外部写拒绝、只读工作区、strict 无回退、回环网络 ALLOW 对照 / DENY、严格读取成功 / 外部读取拒绝、持续输出提前终止、严格环境和显式 JDK runtime。

Live Bash 在 HOST 与严格读取两种配置下都执行真实文件操作与越界拒绝测试，并验证私有 HOME 已清理、非零退出诊断及 executeReport 的结果治理契约。工具供应的 PATH 明确为 `/bin`、`/usr/bin`，不从宿主 PATH 扩展。

每个 JDK 另实际运行 40 个原有演示场景、2 个 YAML 示例、25 个独立安全教学类。严格模式演示当前为 `masked / workspaceWrite=true / toolCalls=1`；旧版 strict 不可用的结果只留在历史验收。

## 故障与回收边界

- 预检、权限、参数、审批拒绝在执行前发生；目标未执行，工具预算未提交。SDK 真正进入执行后的错误保留尝试计数。
- 管道、临时文件、宿主资源的 UNKNOWN / FAILED 或进程 FAILED 均拒绝交付并隔离 backend；SDK 明确 CLEANUP_FAILED 同样隔离。
- macOS 正常退出允许其明确声明的进程树 BEST_EFFORT / UNKNOWN；不承诺全部后代已结束。取消、超时及不合作任务继续保守隔离，不能靠重建 backend 掩盖活跃进程。
- 超限终止不是事务。早期探针中观察到停止 shell 子进程后 shell 仍可能短暂写文件；最终用单进程持续输出验证有限终止，不用“后续文件必不存在”冒充原子保证。
- 未修改 sandbox-demo 仓库，未调整宿主账号或防火墙，未自动发布/推送。未新增性能测量或 Sonar 扫描。

运行命令：

```bash
bash scripts/verify-java-matrix.sh
java -jar examples/target/agt-examples-0.6.0-SNAPSHOT.jar sandbox all
```

MkDocs 严格构建通过；浏览器已确认当前/历史验收导航及首页 Mermaid 渲染。

接入与安全语义见 [当前指南](sandbox-guide.md)，ASI02 / ASI05 按类阅读见 [安全教学](security-demos.md)。
