package com.github.prepushchecker.commitgen.providers;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class CodexLastMessageTest extends TestCase {

    public void testEmptyLastMessageFileFallsBackToStdout() throws Exception {
        Path dir = Files.createTempDirectory("codex-last");
        Path file = dir.resolve("last-message.txt");
        try {
            Files.writeString(file, "  \n", StandardCharsets.UTF_8);
            assertEquals("return a + b;", CodexCliProvider.lastMessageOrStdout(file, "return a + b;"));
            Files.writeString(file, "x++;", StandardCharsets.UTF_8);
            assertEquals("x++;", CodexCliProvider.lastMessageOrStdout(file, "ignored"));
            Files.delete(file);
            assertEquals("out", CodexCliProvider.lastMessageOrStdout(file, "out"));
            assertEquals("", CodexCliProvider.lastMessageOrStdout(file, null));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
}
