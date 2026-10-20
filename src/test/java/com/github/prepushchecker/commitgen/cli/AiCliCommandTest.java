package com.github.prepushchecker.commitgen.cli;

import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;

public class AiCliCommandTest extends TestCase {

    public void testClaudeWithoutModel() {
        assertEquals(List.of("/bin/claude", "-p", "--output-format", "text"),
            AiCliCommand.claude("/bin/claude", "", null));
    }

    public void testClaudeWithModelAndExtraArgs() {
        assertEquals(List.of("/bin/claude", "-p", "--output-format", "text",
                "--model", "opus", "--max-turns", "1"),
            AiCliCommand.claude("/bin/claude", " opus ", "--max-turns 1"));
    }

    public void testCodexReadsPromptFromStdinAndWritesLastMessage() {
        Path out = Path.of("/tmp/x/last.txt");
        assertEquals(List.of("/bin/codex", "exec", "--skip-git-repo-check", "--sandbox", "read-only",
                "--color", "never", "-m", "gpt-5", "--output-last-message", "/tmp/x/last.txt", "-"),
            AiCliCommand.codex("/bin/codex", "gpt-5", "", out));
        assertEquals(List.of("/bin/codex", "exec", "--skip-git-repo-check", "--sandbox", "read-only",
                "--color", "never", "--output-last-message", "/tmp/x/last.txt", "-c", "a=b", "-"),
            AiCliCommand.codex("/bin/codex", null, "-c a=b", out));
    }

    public void testTemplateDropsModelSegmentWhenBlank() {
        assertEquals(List.of("/bin/sahaa"),
            AiCliCommand.fromTemplate("/bin/sahaa", "{model?--model {model}}", "", "P"));
        assertEquals(List.of("/bin/sahaa", "--model", "big one"),
            AiCliCommand.fromTemplate("/bin/sahaa", "{model?--model {model}}", "big one", "P"));
    }

    public void testTemplatePromptStaysSingleArgument() {
        String prompt = "line one\nline \"two\" with spaces";
        assertEquals(List.of("/bin/gemini", "-p", prompt),
            AiCliCommand.fromTemplate("/bin/gemini", "{model?-m {model}} -p {prompt}", null, prompt));
        assertTrue(AiCliCommand.usesPromptArg("-p {prompt}"));
        assertFalse(AiCliCommand.usesPromptArg("{model?--model {model}}"));
    }

    public void testTokenizeHonoursQuotes() {
        assertEquals(List.of("-c", "a b", "", "x'y"),
            AiCliCommand.tokenize("-c \"a b\" '' \"x'y\""));
        assertTrue(AiCliCommand.tokenize("   ").isEmpty());
    }
}
