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

    public void testClaudeCompletionSkipsToolsMcpAndSession() {
        assertEquals(List.of("/bin/claude", "-p", "--output-format", "text", "--model", "haiku",
                "--tools", "", "--strict-mcp-config", "--no-session-persistence", "--effort", "low"),
            AiCliCommand.claude("/bin/claude", "haiku", "--effort low", true));
        assertEquals(List.of("/bin/claude", "-p", "--output-format", "text"),
            AiCliCommand.claude("/bin/claude", null, null, false));
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
                "--ephemeral", "-c", "model_reasoning_effort=\"low\"", "-c", "a=b", "-"),
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
            // amp and crush have no model option: they use the model set in their own config.
            if (cli != AiCli.AMP && cli != AiCli.CRUSH) assertTrue(cli + " " + cmd, cmd.contains("m1"));
            assertFalse(cli + " " + cmd, AiCliCommand.fromTemplate("/bin/x", template, "", "p").contains(""));
        }
    }

    public void testDedicatedClisHaveNoTemplate() {
        for (AiCli cli : List.of(AiCli.CODEX, AiCli.CLAUDE, AiCli.GH, AiCli.LLM)) {
            assertFalse(cli.isCustom());
            assertEquals("", cli.defaultArgsTemplate());
        }
    }

    public void testEffortFlags() {
        java.nio.file.Path out = java.nio.file.Path.of("/tmp/o.txt");
        assertTrue(AiCliCommand.claude("/bin/claude", null, null, false, "low").containsAll(List.of("--effort", "low")));
        assertFalse(AiCliCommand.claude("/bin/claude", null, null, false, "").contains("--effort"));
        assertTrue(AiCliCommand.codex("/bin/codex", null, "", out, false, "medium")
            .contains("model_reasoning_effort=\"medium\""));
        assertFalse(AiCliCommand.codex("/bin/codex", null, "", out, false, "").toString().contains("reasoning"));
    }

    // ── Lean flags gated by the probed capabilities ───────────────────────────

    private static final CliCapabilities CLAUDE_ALL = CliCapabilities.of("--tools", "--strict-mcp-config",
        "--no-session-persistence", "--disable-slash-commands", "--setting-sources", "--system-prompt",
        "--include-partial-messages", "--verbose", "--effort");

    public void testClaudeLeanFlagsForEveryRequestWhenAdvertised() {
        assertEquals(List.of("/bin/claude", "-p", "--output-format", "text", "--model", "haiku",
                "--tools", "", "--strict-mcp-config", "--no-session-persistence", "--disable-slash-commands",
                "--setting-sources", "", "--system-prompt", AiCliCommand.LEAN_SYSTEM_PROMPT, "--effort", "low"),
            AiCliCommand.claude("/bin/claude", "haiku", null, false, "low", CLAUDE_ALL, false, false));
    }

    public void testClaudeOnlyAddsFlagsTheInstalledVersionLists() {
        CliCapabilities old = CliCapabilities.of("--tools", "--strict-mcp-config");
        assertEquals(List.of("/bin/claude", "-p", "--output-format", "text", "--tools", "", "--strict-mcp-config"),
            AiCliCommand.claude("/bin/claude", null, null, false, null, old, true, false));
    }

    public void testClaudeUnprobedKeepsOldBehaviour() {
        assertEquals(List.of("/bin/claude", "-p", "--output-format", "text"),
            AiCliCommand.claude("/bin/claude", null, null, false, null, CliCapabilities.UNKNOWN, true, false));
        assertEquals(List.of("/bin/claude", "-p", "--output-format", "text",
                "--tools", "", "--strict-mcp-config", "--no-session-persistence"),
            AiCliCommand.claude("/bin/claude", null, null, true, null, CliCapabilities.UNKNOWN, false, false));
    }

    public void testClaudeKeepsUserSettingsWhenAsked() {
        List<String> cmd = AiCliCommand.claude("/bin/claude", null, null, false, null, CLAUDE_ALL, false, true);
        assertFalse(cmd.contains("--setting-sources"));
        assertTrue(cmd.contains("--strict-mcp-config"));
    }

    public void testClaudeStreamsOnlyWhenSupported() {
        List<String> cmd = AiCliCommand.claude("/bin/claude", null, null, false, null, CLAUDE_ALL, true, false);
        assertEquals(List.of("/bin/claude", "-p", "--output-format", "stream-json", "--verbose",
            "--include-partial-messages"), cmd.subList(0, 6));
        assertTrue(AiCliCommand.claude("/bin/claude", null, null, false, null,
            CliCapabilities.of("--tools"), true, false).containsAll(List.of("--output-format", "text")));
    }

    public void testClaudeFastModelOnlyForCommitMessagesAndCompletions() {
        assertEquals("haiku", AiCliCommand.claudeModelFor(AiRequest.commitMessage("s", "u"), ""));
        assertEquals("haiku", AiCliCommand.claudeModelFor(
            AiRequest.completion("s", "u", null, AiRequest.COMPLETION_FAST_TIMEOUT), null));
        assertEquals("opus", AiCliCommand.claudeModelFor(AiRequest.commitMessage("s", "u"), "opus"));
        assertNull(AiCliCommand.claudeModelFor(AiRequest.review("s", "u"), null));
        assertEquals("", AiCliCommand.claudeModelFor(AiRequest.fix("s", "u"), ""));
    }

    public void testCodexLeanFlagsWhenEphemeralIsAdvertised() {
        Path out = Path.of("/tmp/x/last.txt");
        assertEquals(List.of("/bin/codex", "exec", "--skip-git-repo-check", "--sandbox", "read-only",
                "--color", "never", "--output-last-message", "/tmp/x/last.txt",
                "--ephemeral", "-c", "mcp_servers={}", "-c", "model_reasoning_effort=\"medium\"", "-"),
            AiCliCommand.codex("/bin/codex", null, "", out, false, "medium", CliCapabilities.of("--ephemeral")));
        assertFalse(AiCliCommand.codex("/bin/codex", null, "", out, false, null, CliCapabilities.of("--json"))
            .contains("mcp_servers={}"));
        assertFalse(AiCliCommand.codex("/bin/codex", null, "", out, false, null, CliCapabilities.UNKNOWN)
            .contains("--ephemeral"));
    }

    public void testTemplateCliLeanFlags() {
        assertEquals(List.of("--disable-builtin-mcps", "--no-custom-instructions"),
            AiCliCommand.leanTemplateFlags(AiCli.COPILOT,
                CliCapabilities.of("--disable-builtin-mcps", "--no-custom-instructions")));
        assertEquals(List.of("--no-custom-instructions"),
            AiCliCommand.leanTemplateFlags(AiCli.COPILOT, CliCapabilities.of("--no-custom-instructions")));
        assertEquals(List.of("--extensions", "none"),
            AiCliCommand.leanTemplateFlags(AiCli.GEMINI, CliCapabilities.of("--extensions")));
        assertEquals(List.of(), AiCliCommand.leanTemplateFlags(AiCli.GEMINI, CliCapabilities.UNKNOWN));
        assertEquals(List.of(), AiCliCommand.leanTemplateFlags(AiCli.OPENCODE, CliCapabilities.of("--extensions")));
        assertEquals(List.of(), AiCliCommand.leanTemplateFlags(null, CliCapabilities.of("--extensions")));
    }
}
