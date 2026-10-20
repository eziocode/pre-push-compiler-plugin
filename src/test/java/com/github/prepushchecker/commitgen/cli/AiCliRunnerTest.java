package com.github.prepushchecker.commitgen.cli;

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
        } catch (RuntimeException expected) {
            // ok
        }
    }
}
