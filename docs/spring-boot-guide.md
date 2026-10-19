# Spring Boot 接入

独立模块 `agt-langchain4j-spring-boot-starter` 提供受治理服务工厂、默认 Options 属性绑定与标准 Boot 自动配置。
当前固定验证 Spring Boot 3.5.16、Java 17/21 和 LangChain4j 1.20.0，制品尚未发布到 Maven Central，先在仓库根目录 `mvn install`。

```xml
<dependency>
  <groupId>io.agt</groupId>
  <artifactId>agt-langchain4j-spring-boot-starter</artifactId>
  <version>0.6.0-SNAPSHOT</version>
</dependency>
```

## 创建服务 Bean

宿主已有的策略、审计和认证逻辑继续提供给 AGT。Starter 检测 `AgentControl` Bean 后注册 `GovernedAiServices`；
默认 Options 与治理门面允许宿主用同类型 Bean 覆盖。多个实例时用 `@Primary` 或自建工厂明确选择。
没有 `AgentControl` 时自动配置退让；有运行时而缺少 `TrustedContextResolver` 时启动失败。

下面代码中 `PolicyEvaluator`、`AuditSink`、`HostAuthentication`、`ChatModel` 和工具 Bean 由宿主应用提供：

```java
import dev.langchain4j.model.chat.ChatModel;
import io.agt.core.AgentControl;
import io.agt.core.PolicyEvaluator;
import io.agt.core.TrustedContext;
import io.agt.core.audit.AuditSink;
import io.agt.langchain4j.spring.GovernedAiServices;
import io.agt.langchain4j.spring.TrustedContextResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AgentConfiguration {
    interface Assistant {
        String chat(String message);
    }

    // 宿主实现这个桥接接口，从经过认证的请求上下文取得身份与权限。
    interface HostAuthentication {
        TrustedContext trustedContext();
    }

    @Bean
    AgentControl agentControl(PolicyEvaluator policy, AuditSink audit) {
        return AgentControl.builder(policy).audit(audit).build();
    }

    @Bean
    TrustedContextResolver trustedIdentity(HostAuthentication authentication) {
        return authentication::trustedContext;
    }

    @Bean
    Assistant assistant(GovernedAiServices services, ChatModel model) {
        return services.create(Assistant.class,
                builder -> builder.chatModel(model).systemMessageProvider(id -> "简洁回答"));
    }
}
```

业务侧正常注入 `Assistant` 并调用。身份在每次顶层调用时解析，不在 Bean 初始化时解析；嵌套治理调用复用当前会话。
原生 `.tools(...)`、动态 Provider、记忆、检索、Guardrails、listener 等仍在同一个回调配置，支持范围见[原生指南](native-integration-guide.md)。

工厂只治理经 `services.create(...)` 创建的服务。该接口以普通接口配合 `@Bean` 注册；不要再同时给它标原生 `@AiService`，
以免原生扫描器创建第二个服务。模型 Provider 的 Starter 可以继续提供 `ChatModel` / `StreamingChatModel` Bean。
工厂不负责改写其他原生 `@AiService` Bean，也不从提示词、HTTP 参数或模型输出自动授予权限。

## 统一配置与服务覆盖

```yaml
agt:
  langchain4j:
    enabled: true
    delivery: REALTIME
    max-characters: 1048576
    max-events: 10000
    publisher-prefetch: 32
```

| 属性 | 默认值 | 含义与约束 |
| --- | --- | --- |
| `enabled` | `true` | 关闭时不注册自动配置的 Options、门面和工厂 |
| `delivery` | `REALTIME` | 实时预览；设为 `STRICT` 时缓冲至完整检查后交付 |
| `max-characters` | `1048576` | 模型每轮流与服务聚合各自的字符限额，1..16777216 |
| `max-events` | `10000` | 模型每轮流与服务聚合各自的事件限额，1..100000 |
| `publisher-prefetch` | `32` | 外层 Publisher 预取及待交付队列窗口，1..1024 |

空值不表示无限额度；错误枚举或非法容量在启动时失败。JSON 策略投影仍受全局字符、深度与节点约束，增大流额度不会放宽这些限制。
实时内容尚未经过最终检查，最终失败不能撤回已交付预览。推送式模型生产速度不保证随下游 demand 暂停。

按服务覆盖时传入不可变 `AiServiceOptions`：

```java
var strict = new io.agt.langchain4j.AiServiceOptions(
        io.agt.langchain4j.StreamDelivery.STRICT,
        new io.agt.langchain4j.StreamingLimits(262144, 2000), 8);
var assistant = services.create(Assistant.class, strict, b -> b.chatModel(model));
```

优先级为服务显式 Options、宿主 `AiServiceOptions` Bean、上述属性默认值。
Starter 不自动创建策略、审批解析器、审计后端或可信身份。需审批的工具在宿主 `AgentControl` 中设置 `approvalResolver`。
控制器在请求结束前应订阅异步/流式结果，取消时传播取消信号；会话会在完成、失败或取消后关闭。

## 非 Spring 接入

相同配置可直接用于基础模块，无需引入 Spring：

```java
var governance = new io.agt.langchain4j.LangChain4jGovernance(control);
var service = governance.create(Assistant.class, identitySupplier, strict,
        b -> b.chatModel(model));
```

也可以通过 `new LangChain4jGovernance(control, strict)` 设置门面默认值。
旧 Builder 的 `streamingLimits(...)` 和独立 `guardStreamingModel` 继续保持原严格契约。
缺少同步/流式模型或 Publisher 交付适配器时，在服务装配阶段报告问题。

## 验证

`GovernanceAutoConfigurationTest` 用实际 Boot 上下文验证 Imports 发现、属性绑定、宿主覆盖、禁用、缺失身份、非法额度、
延迟身份解析、自动关闭审计与原生工具审批允许/拒绝。
`AiServiceOptionsTest` 验证服务配置隔离、模型与聚合限额、预取、缺模型提前失败和缓存后逐次治理。
缓存只存公开方法/构造器与服务级 SPI 选择，不缓存参数、身份、输出或策略结果；没有进行吞吐或延迟基准测试。

## 流式局部护栏

正式原生服务可显式选择 GUARDED；所选 ID 必须存在于同一 AgentControl 的 StructuredPolicy，并绑定 post_model_call 的字面拒绝规则。

~~~yaml
agt:
  langchain4j:
    delivery: GUARDED
    guardrails:
      local-rules: [local-deny]
      chunk-characters: 20
      boundaries: "，。！？\n"
      max-wait: 50ms
      max-buffered-characters: 4096
~~~

属性与 Java `ChunkBufferOptions` 使用同一份校验。服务专属 `AiServiceOptions` 优先于全局默认，可单独选择 STRICT 或另一组局部规则；原生默认仍为 REALTIME。缺失规则、不支持的规则组合及缓冲容量不足均提前失败。保证与限制见 [流式增量护栏](guarded-streaming-guide.md)。
