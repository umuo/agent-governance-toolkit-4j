package io.agt.examples.live.web.streaming;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.agt.examples.live.config.LocalEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class StreamingExamplesConfiguration {
    @Bean("guardrailLiveModel")
    @Lazy
    StreamingChatModel guardrailLiveModel() throws IOException {
        var env = LocalEnvironment.load(Path.of("."), System.getenv());
        if (env.getOrDefault("LLM_API_KEY", "").isBlank()
                || env.getOrDefault("LLM_MODEL", "").isBlank())
            throw new IllegalStateException("live_model_not_configured");
        return OpenAiStreamingChatModel.builder()
                .baseUrl(env.getOrDefault("LLM_BASE_URL", "https://api.openai.com/v1"))
                .apiKey(env.get("LLM_API_KEY"))
                .modelName(env.get("LLM_MODEL"))
                .timeout(Duration.ofSeconds(45))
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
