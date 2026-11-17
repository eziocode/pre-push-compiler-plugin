package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.commitgen.AiRequest;
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

    public void testCodexCompletionAsksForLowReasoningEffort() {
        Path out = Path.of("/tmp/x/last.txt");
        assertEquals(List.of("/bin/codex", "exec", "--skip-git-repo-check", "--sandbox", "read-only",
                "--color", "never", "--output-last-message", "/tmp/x/last.txt",
                "-c", "model_reasoning_effort=\"low\"", "-c", "a=b", "-"),
            AiCliCommand.codex("/bin/codex", null, "-c a=b", out, true));
        assertFalse(AiCliCommand.codex("/bin/codex", null, "", out, false).contains("model_reasoning_effort=\"low\""));
        assertTrue(AiRequest.completion("s", "u", null, AiRequest.COMPLETION_SLOW_TIMEOUT).lowLatency());
        assertFalse(AiRequest.commitMessage("s", "u").lowLatency());
        assertFalse(AiRequest.review("s", "u").lowLatency());
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

    public void testStructuredPromptAsksForJsonOnly() {
        String p = AiCliCommand.combinePrompt("Review rules", "diff", true);
        assertTrue(p.contains("ONLY the JSON object"));
        assertFalse(p.contains("commit message"));
        assertTrue(AiCliCommand.combinePrompt("s", "u").contains("ONLY the commit message"));
    }

    public void testCompletionPromptAsksForCodeOnly() {
        AiRequest req = AiRequest.completion("sys", "usr", null, AiRequest.COMPLETION_FAST_TIMEOUT);
        String p = AiCliCommand.combinePrompt(req.system(), req.user(), req);
        assertTrue(p.contains("Reply with ONLY the code to insert \u2014 no explanation, no code fences, "
            + "do not run tools or edit files."));
        assertFalse(p.contains("commit message"));
        assertFalse(p.contains("JSON"));
    }

    public void testRequestOverloadMatchesBooleanOverloadForOtherKinds() {
        AiRequest commit = AiRequest.commitMessage("sys", "usr");
        assertEquals(AiCliCommand.combinePrompt("sys", "usr", false),
            AiCliCommand.combinePrompt("sys", "usr", commit));
        AiRequest review = AiRequest.review("sys", "usr");
        assertEquals(AiCliCommand.combinePrompt("sys", "usr", true),
            AiCliCommand.combinePrompt("sys", "usr", review));
    }

    public void testEveryCustomPresetPassesPromptAsOneArgument() {
        for (AiCli cli : AiCli.values()) {
            if (!cli.isCustom()) continue;
            String template = cli.defaultArgsTemplate();
            assertTrue(cli + " preset must take {prompt}", AiCliCommand.usesPromptArg(template));
            List<String> cmd = AiCliCommand.fromTemplate("/bin/" + cli.binaryName(), template, "m1", "a b");
            assertTrue(cli + " " + cmd, cmd.contains("a b"));
            assertTrue(cli + " " + cmd, cmd.contains("m1"));
            assertFalse(cli + " " + cmd, AiCliCommand.fromTemplate("/bin/x", template, "", "p").contains(""));
        }
    }

    public void testDedicatedClisHaveNoTemplate() {
        for (AiCli cli : List.of(AiCli.CODEX, AiCli.CLAUDE, AiCli.GH, AiCli.LLM)) {
            assertFalse(cli.isCustom());
            assertEquals("", cli.defaultArgsTemplate());
        }
    }
}
