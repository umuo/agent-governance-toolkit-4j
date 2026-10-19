# 流式护栏网页案例

`examples-live` 侧栏新增“流式护栏实验”，三个案例都直接使用正式 SDK 的 `AiServiceOptions` 和 `ChunkBufferOptions`，不存在演示模块的护栏算法或缓冲原型。

## 页面操作

1. 展开“流式护栏实验”，选择安全内容、跨分片敏感词或长于微批的词。
2. 默认选择固定分片及 GUARDED，点击“运行案例”。无需 API Key。
3. 切换 REALTIME / GUARDED / STRICT 后再次运行，比较正文和首块时间。REALTIME 可能预览合成标记后被完整检查拒绝；GUARDED 不交付命中词字符；STRICT 拒绝时不交付正文。
4. 长词案例可点击“停止生成”。取消绑定当前浏览器会话，服务端停止受控流并尝试取消模型 handle。
5. 选择“真实模型”后使用后端 LLM_API_KEY、LLM_MODEL、LLM_BASE_URL，可编辑提问。真实模型不保证输出标记；没有命中时页面明确显示“未触发”，不能当作拦截证据。

固定分片只是模型替身，通常每180 ms 返回一个预设片段；长词案例放慢为600 ms，方便观察和手动停止。它通过正式 StreamingChatModel、原生 TokenStream 和 SDK 治理入口运行，稳定复现 ABC / DEF 及长词跨微批。真实模型运行使用配置的模型额度；自动验收只调用本机固定分片或模型替身。

页面显示客户端实际收到的正文、首块时间、接收/交付字符数、局部审计次数、已观察到的取消次数及最近50条检查记录。字符以 UTF-16 计量，首块时间包含排队与 SDK 初始化开销；取消能力和局部字面保证的边界见 [正式能力说明](guarded-streaming-guide.md)。原始 provider 分片不作为 GUARDED 正文旁路返回网页。

## 启动

下面使用独立端口，避免占用宿主现有的8080服务。先在仓库根目录打包：

~~~bash
mvn -pl examples-live -am package -DskipTests
cd examples-live
java -jar target/agt-examples-live-0.6.0-SNAPSHOT.jar \
  --server.port=18080 \
  --agt.examples.frontend-origin=http://127.0.0.1:13000 \
  --server.servlet.session.cookie.name=AGT_STREAM_DEMO_SESSION
~~~

在另一个终端启动前端：

~~~bash
cd examples-live/frontend
npm ci
npm run build
AGT_API_URL=http://127.0.0.1:18080 npm run start -- --port 13000
~~~

打开 `http://127.0.0.1:13000`。后端只监听回环地址，演示最多同时运行4个请求，独立运行时与缓存在结束后关闭。目录、源码和运行入口都在 `/api/examples/streaming` 下，源码仅来自打包的固定资源。

## 验证

- `StreamingExamplesTest` 覆盖目录/打包源码、三模式的分片及长词拦截、正常完成无重复、会话取消、请求校验，以及真实模型替身未命中时保持完成且 triggered=false。
- 前端 `streaming-demo.test.ts` 验证收到拒绝后保留安全前缀、不重放全文、检查历史有界及未触发状态。
- 前端执行 `npm test`、`npm run typecheck`、`npm run build`。
- 浏览器通过实际 Next.js 代理和 Spring Boot SSE 验证界面上的正文、错误码、模式切换与停止操作；不调用外部付费模型。
