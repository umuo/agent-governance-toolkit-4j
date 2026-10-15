package io.agt.examples.live;

import static org.junit.jupiter.api.Assertions.*;

import io.agt.examples.live.tools.WorkspaceTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 真实文件副作用及路径、大小、精确编辑边界。 */
class WorkspaceToolsTest {
    @TempDir Path root;

    @Test
    void filesAndSearch() throws Exception {
        var tools = new WorkspaceTools(root);
        tools.writeFile("a.txt", "hello\nworld");
        tools.editFile("a.txt", "world", "agent");
        assertEquals("hello\nagent", tools.readFile("a.txt"));
        assertTrue(tools.searchFile(".", "agent").contains("a.txt:2:agent"));
        assertTrue(tools.listFiles(".").contains("a.txt"));
        assertThrows(IOException.class, () -> tools.editFile("a.txt", "absent", "x"));
        tools.writeFile("duplicate.txt", "xx");
        assertThrows(IOException.class, () -> tools.editFile("duplicate.txt", "x", "y"));
        assertThrows(IOException.class, () -> tools.writeFile("large", "界".repeat(32768)));
        Files.writeString(root.resolve("large"), "x".repeat(32769));
        assertThrows(IOException.class, () -> tools.readFile("large"));
    }

    @Test
    void rejectsEscapeAndLinks() throws Exception {
        var work = Files.createDirectory(root.resolve("work"));
        var outside = root.resolve("outside.txt");
        Files.writeString(outside, "canary");
        Files.createSymbolicLink(work.resolve("link"), outside);
        var tools = new WorkspaceTools(work);
        assertThrows(IOException.class, () -> tools.readFile("../outside.txt"));
        assertThrows(IOException.class, () -> tools.writeFile("../outside.txt", "changed"));
        assertThrows(IOException.class, () -> tools.readFile(outside.toString()));
        assertThrows(IOException.class, () -> tools.readFile("link"));
        assertThrows(IOException.class, () -> tools.writeFile("link", "changed"));
        assertEquals("canary", Files.readString(outside));
    }
}
