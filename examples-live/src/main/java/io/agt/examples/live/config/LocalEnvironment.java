package io.agt.examples.live.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载本地配置文件，系统环境变量覆盖文件值。
 *
 * <p>仅解析 KEY=value 和成对引号，不执行 shell、变量插值或转义序列。
 */
public final class LocalEnvironment {
    private LocalEnvironment() {}

    public static Map<String, String> load(Path directory, Map<String, String> environment)
            throws IOException {
        String explicit = environment.get("AGT_ENV_FILE");
        Path file = explicit == null ? locate(directory) : directory.resolve(explicit);
        var values = new LinkedHashMap<String, String>();
        if (file != null) {
            int number = 0;
            for (String original : Files.readAllLines(file)) {
                number++;
                String line = original.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring(7).strip();
                }
                int separator = line.indexOf('=');
                if (separator < 1
                        || !line.substring(0, separator)
                                .strip()
                                .matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IOException("Invalid .env entry at line " + number);
                }
                String key = line.substring(0, separator).strip();
                String value = line.substring(separator + 1).strip();
                if (value.startsWith("\"") || value.startsWith("'")) {
                    if (value.length() < 2 || value.charAt(value.length() - 1) != value.charAt(0)) {
                        throw new IOException("Invalid .env quote at line " + number);
                    }
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
            // 文件内的相对目录绑定到配置文件所在目录，避免从仓库根目录启动时发生偏移。
            for (String key : List.of("AGT_WORKSPACE", "AGT_AUDIT_DIR")) {
                if (values.containsKey(key) && !values.get(key).isBlank()) {
                    values.put(
                            key,
                            file.toAbsolutePath()
                                    .getParent()
                                    .resolve(values.get(key))
                                    .normalize()
                                    .toString());
                }
            }
        }
        values.putAll(environment);
        return Map.copyOf(values);
    }

    private static Path locate(Path directory) {
        for (Path candidate :
                List.of(
                        directory.resolve(".env.local"),
                        directory.resolve("examples-live/.env.local"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
