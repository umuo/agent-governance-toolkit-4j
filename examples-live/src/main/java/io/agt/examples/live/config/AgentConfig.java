package io.agt.examples.live.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** 集中读取真实模型配置；密钥不进入日志。 */
public record AgentConfig(
        String baseUrl,
        String apiKey,
        String model,
        Path workspace,
        Path auditDirectory,
        String scenario,
        boolean bash,
        boolean bashHostReads) {
    public static AgentConfig fromEnvironment(Map<String, String> env, String scenario) {
        if (!Set.of("agent", "read-only", "approval", "budget").contains(scenario)) {
            throw new IllegalArgumentException("Scenario: agent | read-only | approval | budget");
        }
        String bash = env.getOrDefault("AGT_ENABLE_BASH", "false");
        if (!Set.of("true", "false").contains(bash)) {
            throw new IllegalArgumentException("AGT_ENABLE_BASH must be true or false");
        }
        String hostReads = env.getOrDefault("AGT_BASH_HOST_READS", "false");
        if (!Set.of("true", "false").contains(hostReads)) {
            throw new IllegalArgumentException("AGT_BASH_HOST_READS must be true or false");
        }
        return new AgentConfig(
                env.getOrDefault("LLM_BASE_URL", "https://api.openai.com/v1"),
                required(env, "LLM_API_KEY"),
                required(env, "LLM_MODEL"),
                Path.of(required(env, "AGT_WORKSPACE")),
                Path.of(env.getOrDefault("AGT_AUDIT_DIR", ".cache/live-audit")),
                scenario,
                Boolean.parseBoolean(bash),
                Boolean.parseBoolean(hostReads));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable: " + name);
        }
        return value;
    }

    @Override
    public String toString() {
        return "AgentConfig[scenario=" + scenario + ", bash=" + bash + "]";
    }
}
