# v0.6 当前沙箱 SDK 接入需求

固定引用 Maven Central `io.github.umuo:agent-sandbox-sdk:0.1.0-alpha.1`，无需等待维护中的下一版本。新增可选 agt-sandbox；core 不依赖 JNA，LangChain4j 原有 commandTool/guardCommand 接口复用固定命令后端 SPI。

- 宿主绑定固定程序、摘要、argv 和不可变 SDK 策略；模型不能提供路径、网络权限、环境或原始 SandboxRequest。保留授权、改写、审批、预算和审计。
- 默认要求 DECLARED_ONLY；HOST 仅经明确命名的宿主 opt-in 工厂注册，禁止失败后自动降级。按策略/能力/目录检查准入；SDK 原生 profile 探测仍由当前 SDK execute 完成，不将基础 status 当作完整准入证明。
- 不提供输出回调；最终结果同时检查 timeout、exitCode、stdout/stderr 截断和聚合大小。严格 UTF-8，异常和输出原文不进入默认日志。成功文本按 stdout 后 stderr 合并，再进入原有工具后置治理。
- 为阻塞 SDK 设置独立有限守护工作池、无等待队列；调用方按命令期限及会话状态有界等待。终止时中断 SDK 线程；直到 SDK 调用返回并结束处理才释放后端容量。退出仍不证明所有后代已回收，不能宣称 SDK-02 已修复。
- 已进入 SDK 后的取消/超时或未知执行异常使该实例永久隔离，即使线程随后退出也不解封。后端 close 拒绝新任务并中断已有任务。无法中断的 SDK 任务继续占据有限容量；调用方可以及时收到拒绝，但不能复用可能仍执行的该后端槽位。
- 自动化验证真实 Central 依赖、fake SDK 的故障/取消/晚到结果、真实 LangChain4j 与本机 macOS HOST 写隔离/网络拒绝及严格模式关闭。Windows/Linux 本机未运行时明确记录。
- 当前只支持受审核固定工具，不开放任意模型代码和脚本生成。保留已知读取、资源硬限额、后代清理及平台边界，维护需求仍在 sandbox-demo 独立推进。
