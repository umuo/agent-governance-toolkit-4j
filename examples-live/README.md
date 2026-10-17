# 真实 LLM 对话案例

Spring Boot 后端通过 `agt-langchain4j-spring-boot-starter` 接入治理，配合 Next.js 前端，提供真实模型连续对话、Markdown、流式预览、工具调用事件、浏览器审批和 AGT 治理。

1. 配置 `.env.local` 中的模型和密钥（新克隆时复制 `.env.example`）；完整 Agent 案例额外需要工作区。私密文件已被 Git 忽略。
2. 仓库根目录构建：`mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 -pl examples-live -am verify`。
3. 后端：`java -jar examples-live/target/agt-examples-live-0.6.0-SNAPSHOT.jar`。
4. 另一个终端：`cd examples-live/frontend`，执行 `npm ci`、`npm run dev`。
5. 浏览器打开 [Agent Lab](http://127.0.0.1:3000)。

首页提供原生 LangChain4j、安全护栏入门、ASI01–ASI10 十大风险及完整 Agent 目录，共 43 个安全对照案例。建议先运行普通聊天、加法工具、输入拦截、输出脱敏和工具权限五个最简案例，并展开实际 Java 源码。入门案例只要求模型配置，不要求工作区；每次问答独立，无文件操作和审批。原有工作区实验台位于“完整 Agent”。

“ASI02 工具滥用”包含十个可运行对照案例：批量删除、跨租户读取、邮件滥发、高成本调用、工具链外传、动作审批、工具结果注入、故障熔断、网络隔离与事后拦截。每轮分别运行原生 LangChain4j 与 Starter 防护路径，显示实际模型请求、工具候选调用、执行前后状态和审计。所有网页案例都使用后端配置的真实 API Key 请求 LLM，不提供固定响应或失败回退；若模型不触发工具，页面明确标注未触发。测试替身只在测试目录中使用。

详见 [Web 实验台指南](../docs/web-agent-guide.md)。完整 Agent 默认完整过程实时渲染，保留每轮正文、接口返回的 reasoning 和工具事件，过程输出标注未检查。也可选择严格检查后交付；工具执行始终受治理。

文件工具使用相对路径，`listFiles(".")` 查看当前会话的工作区根目录。文件不存在、文本格式不支持或编辑匹配失败等可恢复错误会经过工具输出检查后返回 Agent，允许修正参数并继续，保留对话上下文；路径越界、符号链接访问和治理检查失败仍会终止本轮。

终端模式仍可通过 `java -jar examples-live/target/agt-examples-live-0.6.0-SNAPSHOT.jar --cli` 使用，配置与工具说明见 [真实 LLM 指南](../docs/live-agent-guide.md)。

Bash 的 `AGT_BASH_HOST_READS` 仅扩大读取范围，不解除写入、网络和审批限制；`~` 仍指向工作区。开启前请阅读 [完整能力边界与验证方法](../docs/live-agent-guide.md#agt_bash_host_reads)。

“ASI05 意外代码执行”提供九个真实模型对照：执行权限、命令参数注入、越界写入、只读工作区、严格读取、执行环境禁网、程序完整性、运行超时和高风险执行审批。脚本全部由宿主固定，仅操作临时合成文件和本机接收器。当前原生探针验收 macOS，不可用时明确标注；不执行任意模型代码，也不宣称已验证容器逃逸防御。详见 [案例与能力边界](../docs/asi05-reproduction-cases.md)。

其余八类风险各有三个独立源码案例，覆盖目标劫持、身份冒用、制品与清单校验、Memory/RAG 投毒、消息签名与重放、级联失败、误导审批和自主任务撤销。见 [十大风险完整实验目录与能力边界](../docs/top10-live-cases.md)。

“多 Agent 执行链路”提供固定父级调度、两个并行真实 LLM 子 Agent、节点审计、请求/响应正文（支持大窗口查看）与历史时间线，详见 [执行链路接入与边界](../docs/agent-tracing.md)。
