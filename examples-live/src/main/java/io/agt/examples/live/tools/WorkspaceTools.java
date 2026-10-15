package io.agt.examples.live.tools;

import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;

/**
 * 绑定宿主工作区的真实文件工具。
 *
 * <p>拒绝链接与目录穿越；宿主必须保证运行期间没有外部进程并发替换目录。
 */
public final class WorkspaceTools {
    public static final int MAX_BYTES = 32768;
    private final Path root;

    public WorkspaceTools(Path root) throws IOException {
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root)) {
            throw new IllegalArgumentException("Workspace must be a directory");
        }
    }

    private Path resolve(String value) throws IOException {
        Path relative = Path.of(value);
        Path path = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !path.startsWith(root)) {
            throw new IOException("workspace_path_denied");
        }
        Path component = root;
        for (Path part : root.relativize(path)) {
            component = component.resolve(part);
            if (Files.isSymbolicLink(component)) {
                throw new IOException("workspace_link_denied");
            }
        }
        return path;
    }

    private String read(Path path) throws IOException {
        var attributes =
                Files.readAttributes(
                        path,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("regular_file_required");
        }
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("file_size_limit");
            }
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        }
    }

    @Tool("Read a UTF-8 file using a relative workspace path; maximum 32768 bytes")
    public String readFile(String path) throws IOException {
        return read(resolve(path));
    }

    @Tool("Create or replace a UTF-8 file; parent directory must exist; relative workspace path")
    public String writeFile(String path, String content) throws IOException {
        Path target = resolve(path);
        write(target, content);
        return "written: " + path;
    }

    private void write(Path path, String content) throws IOException {
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("file_size_limit");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("regular_file_required");
        }
        Files.writeString(
                path,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
    }

    @Tool("Replace exactly one occurrence of oldText with newText in a UTF-8 workspace file")
    public String editFile(String path, String oldText, String newText) throws IOException {
        Path target = resolve(path);
        String content = read(target);
        int index = content.indexOf(oldText);
        if (oldText.isEmpty()
                || index < 0
                || content.indexOf(oldText, index + oldText.length()) >= 0) {
            throw new IOException("edit_requires_unique_match");
        }
        write(
                target,
                content.substring(0, index)
                        + newText
                        + content.substring(index + oldText.length()));
        return "edited: " + path;
    }

    @Tool("List workspace files and directories under a relative path; depth <= 8; no symlinks")
    public String listFiles(String path) throws IOException {
        return scan(path, "", false);
    }

    @Tool(
            "Search literal text in UTF-8 workspace files under a relative directory; returns path:line:text")
    public String searchFile(String path, String query) throws IOException {
        if (query.isEmpty()) {
            throw new IOException("empty_search_denied");
        }
        return scan(path, query, true);
    }

    private String scan(String path, String query, boolean search) throws IOException {
        var output = new ArrayList<String>();
        int size = 0;
        try (var paths = Files.walk(resolve(path), 8)) {
            var iterator = paths.iterator();
            int visited = 0;
            while (iterator.hasNext()) {
                if (++visited > 2000) {
                    output.add("[entry limit reached]");
                    break;
                }
                Path item = iterator.next();
                if (Files.isSymbolicLink(item)) {
                    continue;
                }
                String name = root.relativize(item).toString();
                if (!search) {
                    output.add(name + (Files.isDirectory(item) ? "/" : ""));
                    size += name.length() + 2;
                } else if (Files.isRegularFile(item) && Files.size(item) <= MAX_BYTES) {
                    String text;
                    try {
                        text = read(item);
                    } catch (java.nio.charset.CharacterCodingException binary) {
                        continue;
                    }
                    String[] lines = text.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains(query)) {
                            String match = name + ":" + (i + 1) + ":" + lines[i];
                            output.add(match);
                            size += match.length() + 1;
                            if (size >= MAX_BYTES || output.size() >= 100) {
                                break;
                            }
                        }
                    }
                }
                if (size >= MAX_BYTES || output.size() >= 100) {
                    output.add("[result limit reached]");
                    break;
                }
            }
        }
        return String.join("\n", output);
    }
}
