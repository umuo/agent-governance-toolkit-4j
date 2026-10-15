# v0.6 沙箱 SDK alpha.2 接入要求

当前引用 Maven Central `io.github.umuo:agent-sandbox-sdk:0.1.0-alpha.2`，实际回归正式二进制。agt-sandbox 保持可选，core / 单独 LangChain4j 适配器不依赖 JNA；沿用固定命令后端 SPI。

- 宿主绑定程序、摘要、argv、工作区、OS 权限及工具目录；模型不能提供 SandboxRequest。授权、改写后重验、审批、预算与结果治理不绕过。
- 默认严格读取、明确网络策略；HOST 仅经显式入口。准备阶段按有效请求执行 validate，探针失败、能力不足和非法配置在工具预算提交前拒绝，无普通进程降级。
- 完整保留 resourceLimits / requiredIsolation；不支持的硬限制、VM 要求明确拒绝，不把 coarse FULL 视为每一项都由内核保证。
- 默认严格环境，不继承宿主 PATH；HOME 使用 SDK 私有临时目录，持久输出放工作区。宿主可通过四参数工厂显式供应严格工具目录，不能传 legacy 环境静默放宽。
- 输出共享及每流额度取更小值；TERMINATE-on-limit、无回调、严格 UTF-8、结果与截断复查。原文和调试异常不进默认审计。
- 有限控制池与 SDK 池、零队列；通过执行句柄协作取消，容量保持到实际 workerFinished。调用方等待、执行完成和资源回收互相独立。
- 管道/临时资源/宿主准备资源未确认或失败时拒绝。macOS 进程树 BEST_EFFORT 正常退出的 UNKNOWN 明确披露；取消、超时、清理失败继续保守隔离，不宣称完整后代回收。
- 使用正式 Central 制品、真实 LangChain4j、本机合成文件与回环服务验证；测试对比 SDK-01 至 SDK-07 的具体措施与平台边界。不把 fake runner、跳过、只改版本号当作原生保证。
- 核心固定工具接口不扩展为任意生成代码执行器；live 示例显式脚本入口保持示例边界。SDK 并不提供 JVM 沙箱或本机尚未实现的资源硬限额。

历史 alpha.1 的接入结论保留在 [旧验收](sandbox-verification-alpha1.md)。当前使用方式见 [指南](sandbox-guide.md)。
