package io.agt.examples.live;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.model.openai.OpenAiChatModel;
import io.agt.core.AgentControl;
import io.agt.core.TrustedContext;
import io.agt.examples.live.config.AgentPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** 用户显式启用的收费真实模型验收；默认测试不发外网请求。 */
@EnabledIfEnvironmentVariable(named = "AGT_LIVE_TEST", matches = "true")
class LiveProviderTest {
    @TempDir Path workspace;

    @Test
    void realModelWritesAndEditsFile() throws Exception {
        var env = System.getenv();
        assertNotNull(env.get("LLM_API_KEY"), "LLM_API_KEY required");
        assertNotNull(env.get("LLM_MODEL"), "LLM_MODEL required");
        var model =
                OpenAiChatModel.builder()
                        .baseUrl(env.getOrDefault("LLM_BASE_URL", "https://api.openai.com/v1"))
                        .apiKey(env.get("LLM_API_KEY"))
                        .modelName(env.get("LLM_MODEL"))
                        .timeout(java.time.Duration.ofSeconds(60))
                        .maxRetries(0)
                        .build();
        try (var control =
                        AgentControl.builder(AgentPolicy.create("agent")).audit(r -> {}).build();
                var session =
                        control.openSession(
                                new TrustedContext(
                                        "agent",
                                        "local",
                                        "test",
                                        AgentPolicy.permissions("agent", false)))) {
            String answer =
                    GeneralAgentDemo.assistant(control, model, workspace, null)
                            .chat(
                                    "Use writeFile to create result.txt containing exactly hello; then use editFile to replace hello with verified; finally readFile to verify. Do not use Bash.");
            assertNotNull(answer);
            assertEquals("verified", Files.readString(workspace.resolve("result.txt")));
            assertTrue(session.budgetSnapshot().toolCalls() >= 3);
        }
    }
}
