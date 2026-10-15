package io.agt.examples.live;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.examples.live.config.LocalEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 本地私密配置的解析、覆盖与相对路径绑定。 */
class LocalEnvironmentTest {
    @TempDir Path directory;

    @Test
    void loadsModuleFileAndEnvironmentWins() throws Exception {
        var module = Files.createDirectory(directory.resolve("examples-live"));
        Files.writeString(
                module.resolve(".env.local"),
                """
                # private configuration
                export LLM_API_KEY='local-secret#literal'
                LLM_MODEL="tool model"
                AGT_WORKSPACE=.cache/work
                AGT_AUDIT_DIR=.cache/audit
                """);
        var values = LocalEnvironment.load(directory, Map.of("LLM_API_KEY", "env-secret"));
        assertEquals("env-secret", values.get("LLM_API_KEY"));
        assertEquals("tool model", values.get("LLM_MODEL"));
        assertEquals(module.resolve(".cache/work").toString(), values.get("AGT_WORKSPACE"));
        assertEquals(
                "local-secret#literal", LocalEnvironment.load(module, Map.of()).get("LLM_API_KEY"));
    }

    @Test
    void explicitFileAndInvalidContent() throws Exception {
        var file = directory.resolve("private.env");
        Files.writeString(file, "LLM_MODEL=test\n");
        assertEquals(
                "test",
                LocalEnvironment.load(directory, Map.of("AGT_ENV_FILE", "private.env"))
                        .get("LLM_MODEL"));
        Files.writeString(file, "DO_NOT_ECHO_SECRET\n");
        var error =
                assertThrows(
                        IOException.class,
                        () ->
                                LocalEnvironment.load(
                                        directory, Map.of("AGT_ENV_FILE", "private.env")));
        assertFalse(error.getMessage().contains("SECRET"));
    }
}
