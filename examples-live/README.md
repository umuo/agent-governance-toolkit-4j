# 真实 LLM 对话案例

Spring Boot 后端通过 `agt-langchain4j-spring-boot-starter` 接入治理，配合 Next.js 前端，提供真实模型连续对话、Markdown、流式预览、工具调用事件、浏览器审批和 AGT 治理。

1. 配置 `.env.local` 中的模型和密钥（新克隆时复制 `.env.example`）；完整 Agent 案例额外需要工作区。私密文件已被 Git 忽略。
2. 仓库根目录构建：`mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=.cache/m2 -pl examples-live -am verify`。
3. 后端：`java -jar examples-live/target/agt-examples-live-0.6.0-SNAPSHOT.jar`。
4. 另一个终端：`cd examples-live/frontend`，执行 `npm ci`、`npm run dev`。
5. 浏览器打开 [Agent Lab](http://127.0.0.1:3000)。

首页提供“原生 LangChain4j”“安全护栏入门”“完整 Agent”三个文件夹。建议先运行普通聊天、加法工具、输入拦截、输出脱敏和工具权限五个最简案例，并展开实际 Java 源码。入门案例只要求模型配置，不要求工作区；每次问答独立，无文件操作和审批。原有工作区实验台位于“完整 Agent”。

详见 [Web 实验台指南](../docs/web-agent-guide.md)。完整 Agent 默认完整过程实时渲染，保留每轮正文、接口返回的 reasoning 和工具事件，过程输出标注未检查。也可选择严格检查后交付；工具执行始终受治理。

文件工具使用相对路径，`listFiles(".")` 查看当前会话的工作区根目录。文件不存在、文本格式不支持或编辑匹配失败等可恢复错误会经过工具输出检查后返回 Agent，允许修正参数并继续，保留对话上下文；路径越界、符号链接访问和治理检查失败仍会终止本轮。

终端模式仍可通过 `java -jar examples-live/target/agt-examples-live-0.6.0-SNAPSHOT.jar --cli` 使用，配置与工具说明见 [真实 LLM 指南](../docs/live-agent-guide.md)。

Bash 的 `AGT_BASH_HOST_READS` 仅扩大读取范围，不解除写入、网络和审批限制；`~` 仍指向工作区。开启前请阅读 [完整能力边界与验证方法](../docs/live-agent-guide.md#agt_bash_host_reads)。
