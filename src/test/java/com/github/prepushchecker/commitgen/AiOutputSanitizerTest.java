package com.github.prepushchecker.commitgen;

import junit.framework.TestCase;

public class AiOutputSanitizerTest extends TestCase {

    private static final String MSG = "feat(auth): add token refresh\n\nRefresh expired tokens before each request.";

    public void testCleanMessagePassesThroughUnchanged() {
        assertEquals(MSG, AiOutputSanitizer.clean(MSG));
        assertEquals("fix: typo", AiOutputSanitizer.clean("fix: typo\n"));
    }

    public void testShortAnswerLikeOkIsKept() {
        assertEquals("OK", AiOutputSanitizer.clean("OK"));
        assertEquals("OK", AiOutputSanitizer.clean("\u001B[32mOK\u001B[0m\n"));
    }

    public void testBulletBodyIsKept() {
        String msg = "refactor: split parser\n\n- move lexer\n- add tests";
        assertEquals(msg, AiOutputSanitizer.clean(msg));
    }

    public void testAnsiOscAndSpinnerOverwritesAreRemoved() {
        String raw = "\u001B]0;sahaa\u0007\u001B[2m[model] provider=platformAI model=x thinking=off mode=chat\u001B[0m\n"
            + "⠋ Working\r⠙ Working\r\u001B[2K\u001B[1mfix: typo\u001B[0m\n";
        assertEquals("fix: typo", AiOutputSanitizer.clean(raw));
    }

    public void testOrphanSgrLeftByTrimIsRemovedButBracketPrefixesSurvive() {
        // ProcessExecution trims stdout, which eats the leading ESC of "\u001B[2m".
        assertEquals("fix: typo", AiOutputSanitizer.clean("[2m[model] provider=x thinking=on\u001B[0m\nfix: typo"));
        assertEquals("[JIRA-12] fix: typo", AiOutputSanitizer.clean("[JIRA-12] fix: typo"));
        assertEquals("[2fa] add TOTP", AiOutputSanitizer.clean("[2fa] add TOTP"));
    }

    public void testThinkingBlocksAreRemoved() {
        assertEquals(MSG, AiOutputSanitizer.clean("<think>The diff adds refresh.\nSo feat.</think>\n" + MSG));
        assertEquals(MSG, AiOutputSanitizer.clean("<Thinking>\nhmm\n</Thinking>\n\n" + MSG));
        assertEquals(MSG, AiOutputSanitizer.clean("<reasoning effort=\"low\">x</reasoning>" + MSG));
    }

    public void testOrphanClosingThinkTagDropsEverythingBefore() {
        assertEquals(MSG, AiOutputSanitizer.clean("Okay, the user changed auth code.\n</think>\n\n" + MSG));
    }

    public void testUnterminatedBlockIsDroppedToEnd() {
        assertEquals(MSG, AiOutputSanitizer.clean(MSG + "\n<thinking>I could also mention"));
    }

    public void testToolCallBlocksAndSignaturesAreRemoved() {
        String raw = "<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"a\"}}</tool_call>\n"
            + "<function_calls>\n<invoke name=\"Bash\">\n<parameter name=\"command\">git diff</parameter>\n"
            + "</invoke>\n</function_calls>\n"
            + "<tool_result>diff --git a/x b/x</tool_result>\n"
            + "<signature>EqQBCkYIBxgCKkBZ3f9a</signature>\n"
            + "<signature value=\"abc\"/>\n"
            + MSG;
        assertEquals(MSG, AiOutputSanitizer.clean(raw));
    }

    public void testAgentTranscriptLinesAreRemoved() {
        String raw = "⏺ Bash(git diff --staged)\n"
            + "  ⎿  diff --git a/src/A.java b/src/A.java\n"
            + "● Reading files…\n"
            + "✓ Read 2 files\n"
            + "> Running git status\n"
            + "[tool] read_file src/A.java\n"
            + "Tool call: grep {\"pattern\":\"x\"}\n"
            + "Calling tool: list_dir\n"
            + "thinking…\n"
            + "Signature: EqQBCkYIBxgCKkBZ3f9a\n"
            + MSG + "\n"
            + "Tokens: 1,234 in / 56 out\n"
            + "Cost: $0.0042\n"
            + "Duration: 3.2s\n"
            + "12.3k tokens used\n";
        assertEquals(MSG, AiOutputSanitizer.clean(raw));
    }

    public void testBoxFramesAreRemoved() {
        String raw = "╭──────────────────╮\n│ Copilot CLI v1.2 │\n╰──────────────────╯\n" + MSG;
        assertEquals(MSG, AiOutputSanitizer.clean(raw));
    }

    public void testBase64SignatureBlobLineIsRemoved() {
        String blob = "EqQBCkYIBxgCKkBZ3f9aQ0x1c2R5ZmRzZmRzZmRzZmRzZmRzZmRzZmRzZmRzZmRzZmRzZmRzZmRzZmRzZmRzZmRzZmRzZmRz";
        assertEquals(MSG, AiOutputSanitizer.clean(blob + "\n" + MSG));
    }

    public void testJsonResultObjectIsUnwrapped() {
        String raw = "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"fix: typo\\n\\nCorrect README.\","
            + "\"total_cost_usd\":0.01}";
        assertEquals("fix: typo\n\nCorrect README.", AiOutputSanitizer.clean(raw));
        assertEquals("fix: typo", AiOutputSanitizer.clean("{\"response\":\"fix: typo\",\"stats\":{\"tokens\":3}}"));
        assertEquals("fix: typo", AiOutputSanitizer.clean(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fix: typo\"}}]}"));
    }

    public void testJsonContentBlocksSkipThinkingAndToolUse() {
        String raw = "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"hmm\",\"signature\":\"abc\"},"
            + "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}},"
            + "{\"type\":\"text\",\"text\":\"fix: typo\"}]}";
        assertEquals("fix: typo", AiOutputSanitizer.clean(raw));
    }

    public void testStreamJsonEventLinesPreferFinalResult() {
        String raw = "{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[\"Bash\"]}\n"
            + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Bash\"}]}}\n"
            + "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\"diff\"}]}}\n"
            + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"draft\"}]}}\n"
            + "{\"type\":\"result\",\"result\":\"fix: typo\"}\n";
        assertEquals("fix: typo", AiOutputSanitizer.clean(raw));
    }

    public void testStreamJsonWithoutResultUsesAssistantTextOrDeltas() {
        String events = "{\"type\":\"thread.started\",\"thread_id\":\"1\"}\n"
            + "{\"type\":\"item.completed\",\"item\":{\"type\":\"reasoning\",\"text\":\"thinking\"}}\n"
            + "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"fix: typo\"}}\n"
            + "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":5}}\n";
        assertEquals("fix: typo", AiOutputSanitizer.clean(events));

        String deltas = "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"fix: \"}}\n"
            + "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"typo\"}}\n";
        assertEquals("fix: typo", AiOutputSanitizer.clean(deltas));
    }

    public void testJsonEventLinesMixedWithTextKeepText() {
        String raw = "{\"tool\":\"read_file\",\"args\":{\"path\":\"a\"}}\n"
            + "{\"tool_calls\":[{\"id\":\"1\"}]}\n" + MSG;
        assertEquals(MSG, AiOutputSanitizer.clean(raw));
    }

    public void testFencedBlockAnywhereIsPreferred() {
        String raw = "I looked at the diff.\n\n```bash\ngit diff --staged\n```\n\nHere is the message:\n\n```\n"
            + MSG + "\n```\n\nLet me know if you want changes.";
        assertEquals(MSG, AiOutputSanitizer.clean(raw));
        assertEquals("fix: x", AiOutputSanitizer.clean("```text\nfix: x\n```"));
        assertEquals("fix: x", AiOutputSanitizer.clean("```\nfix: x"));
    }

    public void testPreambleAndTrailingChatterAreRemoved() {
        String raw = "Sure!\nHere's the commit message:\n\n" + MSG
            + "\n\nLet me know if you'd like a shorter subject line.\nHappy to help.";
        assertEquals(MSG, AiOutputSanitizer.clean(raw));
        assertEquals(MSG, AiOutputSanitizer.clean("Commit message:\n" + MSG));
        assertEquals("fix: typo", AiOutputSanitizer.clean("\"fix: typo\""));
        assertEquals("fix: typo", AiOutputSanitizer.clean("`fix: typo`"));
    }

    public void testCombinedNoiseFromCustomCli() {
        String raw = "\u001B[2m[model] provider=platformAI model=claude thinking=medium mode=chat\u001B[0m\n"
            + "<thinking>\nThe user staged a README change.\n</thinking>\n"
            + "⏺ Read(README.md)\n  ⎿  Read 40 lines\n"
            + "<tool_call>{\"name\":\"git_diff\"}</tool_call>\n"
            + "Here is the commit message:\n\n"
            + "docs: fix README typo\n\nCorrect the install command spelling.\n\n"
            + "Tokens: 812 in / 23 out\n";
        assertEquals("docs: fix README typo\n\nCorrect the install command spelling.",
            AiOutputSanitizer.clean(raw));
    }

    public void testAllNoiseYieldsEmptyAndCleanOrThrowFails() {
        String raw = "<thinking>only thoughts</thinking>\n⏺ Bash(git diff)\n  ⎿  diff\nTokens: 12\n"
            + "{\"type\":\"tool_use\",\"name\":\"Bash\"}\n";
        assertEquals("", AiOutputSanitizer.clean(raw));
        assertEquals("", AiOutputSanitizer.clean(null));
        try {
            AiOutputSanitizer.cleanOrThrow(raw);
            fail("expected failure");
        } catch (IllegalStateException e) {
            assertEquals(AiOutputSanitizer.EMPTY_RESULT_MESSAGE, e.getMessage());
        }
        assertEquals(MSG, AiOutputSanitizer.cleanOrThrow(MSG));
    }

    public void testErrorDetailUnwrapsJsonAndCaps() {
        assertEquals("Invalid API key", AiOutputSanitizer.errorDetail(
            "{\"error\":{\"message\":\"Invalid API key\",\"type\":\"auth\"}}"));
        String longBody = "x".repeat(2000);
        String detail = AiOutputSanitizer.errorDetail(longBody);
        assertEquals(AiOutputSanitizer.MAX_ERROR_DETAIL + 1, detail.length());
        assertTrue(detail.endsWith("…"));
        assertEquals("boom", AiOutputSanitizer.errorDetail("\u001B[31mboom\u001B[0m"));
    }

    public void testStructuredBareJson() {
        java.util.Map<?, ?> m = AiOutputSanitizer.cleanStructured(
            "{\"findings\":[{\"file\":\"A.java\",\"line\":3}]}", "findings");
        assertNotNull(m);
        assertEquals(1, ((java.util.List<?>) m.get("findings")).size());
    }

    public void testStructuredInsideCliNoiseAndFence() {
        String raw = "\u001B[2m[model] gpt-5 mode=chat\u001B[0m\n"
            + "<thinking>check {\"findings\": \"no\"} draft</thinking>\n"
            + "Sure, here is the review:\n```json\n"
            + "{\"findings\":[{\"file\":\"B.java\",\"message\":\"Use } inside string\"}]}\n```\n"
            + "tokens: 120 in / 40 out";
        java.util.Map<?, ?> m = AiOutputSanitizer.cleanStructured(raw, "findings");
        assertNotNull(m);
        java.util.Map<?, ?> f = (java.util.Map<?, ?>) ((java.util.List<?>) m.get("findings")).get(0);
        assertEquals("B.java", f.get("file"));
        assertEquals("Use } inside string", f.get("message"));
    }

    public void testStructuredInsideClaudeResultEnvelope() {
        String raw = "{\"type\":\"result\",\"result\":\"{\\\"findings\\\":[]}\"}";
        java.util.Map<?, ?> m = AiOutputSanitizer.cleanStructured(raw, "findings");
        assertNotNull(m);
        assertTrue(((java.util.List<?>) m.get("findings")).isEmpty());
    }

    public void testStructuredMissingKeyIsNull() {
        assertNull(AiOutputSanitizer.cleanStructured("{\"other\":1} no answer", "findings"));
        assertNull(AiOutputSanitizer.cleanStructured("", "findings"));
    }

    public void testCompletionKeepsCodeThatLooksLikeNoise() {
        String code = "    [info] = load();\n    String s = \"quoted\";\n    \u2502 box";
        assertEquals(code, AiOutputSanitizer.cleanCompletion(code));
    }

    public void testCompletionUnwrapsFenceAndAnsi() {
        assertEquals("return a + b;",
            AiOutputSanitizer.cleanCompletion("\u001B[32m```java\nreturn a + b;\n```\u001B[0m\n"));
    }

    public void testCompletionDropsThinkingBlock() {
        assertEquals("x++;", AiOutputSanitizer.cleanCompletion("<thinking>plan</thinking>\nx++;"));
    }

    public void testCompletionBlankStaysBlank() {
        assertEquals("", AiOutputSanitizer.cleanCompletion("  \n\n"));
        assertEquals("", AiOutputSanitizer.cleanCompletion(null));
    }

    public void testCompletionKeepsParameterTagsInCode() {
        String xml = "<parameter name=\"x\">1</parameter>";
        assertEquals(xml, AiOutputSanitizer.cleanCompletion(xml));
        String more = "<invoke a=\"1\">\n  <signature>s</signature>\n</invoke>";
        assertEquals(more, AiOutputSanitizer.cleanCompletion(more));
    }

    public void testCompletionKeepsLaterThinkCloseTag() {
        String code = "String s = \"a</think>b\";\nint y = 2;";
        assertEquals(code, AiOutputSanitizer.cleanCompletion(code));
    }

    public void testCompletionRemovesRepeatedLeadingBlocks() {
        assertEquals("a();", AiOutputSanitizer.cleanCompletion(
            "\n<think>x</think>\n<thinking>y</thinking>\n<tool_call>z</tool_call>\na();"));
    }

    public void testCompletionKeepsOnlyFencedBodyAmongProse() {
        assertEquals("a();\nb();",
            AiOutputSanitizer.cleanCompletion("Here is the code:\n```java\na();\nb();\n```\nHope this helps!"));
    }

    public void testCompletionLeavesUnfencedAndTwoFenceAnswersAlone() {
        String plain = "Here is text\na();";
        assertEquals(plain, AiOutputSanitizer.cleanCompletion(plain));
        String two = "x\n```\na();\n```\ny\n```\nb();\n```";
        assertEquals(two, AiOutputSanitizer.cleanCompletion(two));
    }
}
