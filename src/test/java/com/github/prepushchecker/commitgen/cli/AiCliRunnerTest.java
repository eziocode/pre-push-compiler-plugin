package com.github.prepushchecker.commitgen.cli;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class AiCliRunnerTest extends TestCase {

    private Path dir;

    @Override
    protected void setUp() throws Exception {
        dir = AiCliRunner.createWorkDir();
    }

    @Override
    protected void tearDown() {
        AiCliRunner.deleteQuietly(dir);
        assertFalse(Files.exists(dir));
    }

    private Path script(String body) throws Exception {
        Path p = dir.resolve("fake-cli");
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        return p;
    }

    public void testEchoesStdinAndRunsInWorkDir() throws Exception {
        Path cli = script("cat; pwd >&2");
        String out = AiCliRunner.run("Fake", List.of(cli.toString()), "feat: add thing\n",
            dir, Map.of(), Duration.ofSeconds(20), "hint");
        assertEquals("feat: add thing", out);
    }

    public void testNonZeroExitIncludesStderrAndHint() throws Exception {
        Path cli = script("echo 'not logged in' >&2; exit 3");
        try {
            AiCliRunner.run("Fake", List.of(cli.toString()), "x", dir, Map.of(),
                Duration.ofSeconds(20), "Run: fake login");
            fail("expected failure");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("code 3"));
            assertTrue(e.getMessage(), e.getMessage().contains("not logged in"));
            assertTrue(e.getMessage(), e.getMessage().contains("Run: fake login"));
        }
    }

    public void testTimeout() throws Exception {
        Path cli = script("sleep 30");
        long start = System.nanoTime();
        try {
            AiCliRunner.run("Fake", List.of(cli.toString()), null, dir, Map.of(),
                Duration.ofSeconds(1), "hint");
            fail("expected timeout");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("timed out"));
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 15);
    }

    public void testExtraEnvIsPassed() throws Exception {
        Path cli = script("printf '%s' \"$FAKE_KEY\"");
        assertEquals("secret", AiCliRunner.run("Fake", List.of(cli.toString()), null, dir,
            Map.of("FAKE_KEY", "secret"), Duration.ofSeconds(20), "hint"));
    }

    public void testRequireTextStripsFenceAndRejectsBlank() {
        assertEquals("fix: x", AiCliRunner.requireText("Fake", "```\nfix: x\n```", "h"));
        try {
            AiCliRunner.requireText("Fake", "  ", "h");
            fail("expected failure");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("empty response"));
        }
    }

    public void testRequireTextRemovesAgentNoise() {
        String raw = "<thinking>look at diff</thinking>\n⏺ Bash(git diff)\n  ⎿  diff --git a/x b/x\n"
            + "<tool_call>{\"name\":\"read\"}</tool_call>\nfix: typo\nTokens: 12 in / 3 out\n";
        assertEquals("fix: typo", AiCliRunner.requireText("Fake", raw, "h"));
    }

    public void testRequireTextAllNoiseFailsWithClearMessage() {
        try {
            AiCliRunner.requireText("Fake", "<thinking>only</thinking>\n⏺ Bash(ls)\n", "Run: fake login");
            fail("expected failure");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no commit message after removing tool output"));
            assertTrue(e.getMessage(), e.getMessage().contains("Run: fake login"));
        }
    }

    public void testCustomCliNoiseEndToEnd() throws Exception {
        Path cli = script("printf '\\033[2m[model] provider=x thinking=on\\033[0m\\n'; "
            + "echo '<think>hmm</think>'; echo '● Reading diff'; echo 'docs: fix typo'; echo 'Cost: $0.01'");
        String out = AiCliRunner.run("Fake", List.of(cli.toString()), null, dir, Map.of(),
            Duration.ofSeconds(20), "hint");
        assertEquals("docs: fix typo", AiCliRunner.requireText("Fake", out, "hint"));
    }

    public void testStderrInErrorIsCapped() throws Exception {
        Path cli = script("i=0; while [ $i -lt 300 ]; do printf 'errorline%03d ' $i >&2; i=$((i+1)); done; exit 2");
        try {
            AiCliRunner.run("Fake", List.of(cli.toString()), null, dir, Map.of(), Duration.ofSeconds(20), "hint");
            fail("expected failure");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().length() < 700);
        }
    }

    public void testCancelledIndicatorKillsProcess() throws Exception {
        Path cli = script("sleep 30");
        ProgressIndicatorBase indicator = new ProgressIndicatorBase();
        Thread canceller = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            indicator.cancel();
        });
        canceller.start();
        long start = System.nanoTime();
        try {
            AiCliRunner.runCancellable(new ProcessBuilder(cli.toString()).directory(dir.toFile()),
                Duration.ofSeconds(25), "", indicator);
            fail("expected cancellation");
        } catch (ProcessCanceledException expected) {
            // ok
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
        canceller.join();
    }

    public void testErrorSummaryPrefersCliErrorOverEchoedPrompt() {
        String out = "OpenAI Codex v0.169.0\n--------\nuser\nWrite in English, concise. Reply with ONLY the commit message text\n"
            + "diff --git a/A.java b/A.java\n+    throw new IllegalStateException(\"file not found\");\n"
            + "ERROR: The 'gpt-5-codex' model is not supported when using Codex with a ChatGPT account.\n";
        assertEquals("ERROR: The 'gpt-5-codex' model is not supported when using Codex with a ChatGPT account.",
            AiCliRunner.errorSummary(out));
    }

    public void testErrorSummaryFallsBackToTheEnd() {
        String out = "x".repeat(3000) + "\nlast words";
        String s = AiCliRunner.errorSummary(out);
        assertTrue(s, s.endsWith("last words"));
        assertTrue(AiCliRunner.errorSummary("Error: stdin is not a terminal").contains("stdin is not a terminal"));
    }
}
