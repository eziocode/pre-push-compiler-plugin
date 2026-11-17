package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.commitgen.AiOutputSanitizer;
import com.github.prepushchecker.commitgen.AiRequest;
import junit.framework.TestCase;

/** {@link AiCliRunner#finish(String, String, String, AiRequest)} in COMPLETION mode, per CLI. */
public class AiCliRunnerCompletionTest extends TestCase {

    private static final String HINT = "hint";
    private static final AiRequest REQ =
        AiRequest.completion("sys", "usr", null, AiRequest.COMPLETION_FAST_TIMEOUT);

    public void testClaudeCliAnsiAndLeadingThinking() {
        String raw = "\u001B[2m<thinking>The user wants a return.</thinking>\u001B[0m\n"
            + "\u001B[32m    return a + b;\u001B[0m\n";
        assertEquals("    return a + b;", AiCliRunner.finish("Claude CLI", raw, HINT, REQ));
    }

    public void testCodexCliLastMessageFile() {
        // codex exec --output-last-message writes only the final answer
        String raw = "if (x == null) {\n    return;\n}\n\n";
        assertEquals("if (x == null) {\n    return;\n}", AiCliRunner.finish("Codex CLI", raw, HINT, REQ));
    }

    public void testCodexCliFencedAnswer() {
        String raw = "```java\nlist.add(item);\n```\n";
        assertEquals("list.add(item);", AiCliRunner.finish("Codex CLI", raw, HINT, REQ));
    }

    public void testLlmCliPlainAndFenced() {
        assertEquals("count++;", AiCliRunner.finish("llm", "count++;\n", HINT, REQ));
        assertEquals("count++;\ncount--;",
            AiCliRunner.finish("llm", "```\ncount++;\ncount--;\n```", HINT, REQ));
    }

    public void testBlankOutputMeansNoSuggestion() {
        // The model choosing to insert nothing is not a failure (it must not pause inline suggestions).
        assertEquals("", AiCliRunner.finish("Codex CLI", " \n", HINT, REQ));
        assertEquals("", AiCliRunner.finish("Codex CLI", null, HINT, REQ));
        assertEquals("", AiCliRunner.finish("Claude CLI", "<think>only thoughts</think>", HINT, REQ));
    }

    public void testBlankOutputStillFailsForCommitMessages() {
        try {
            AiCliRunner.finish("Claude CLI", " \n", HINT, AiRequest.commitMessage("s", "u"));
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("returned an empty response"));
        }
    }
}
