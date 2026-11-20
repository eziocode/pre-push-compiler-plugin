package com.github.prepushchecker.commitgen;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;

public class CliPathResolverTest extends TestCase {
    public void testWhichViaShellRejectsShellMetacharacters() throws Exception {
        Path marker = Files.createTempFile("prepushchecker-cli-injection", ".marker");
        Files.deleteIfExists(marker);

        assertNull(CliPathResolver.whichViaShell("missing-cli; touch " + marker));
        assertFalse(Files.exists(marker));
    }

    public void testResolveOrNullUsesConfiguredAbsolutePath() throws Exception {
        Path bin = Files.createTempFile("prepushchecker-fake-cli", "");
        try {
            bin.toFile().setExecutable(true);
            assertEquals(bin.toString(), CliPathResolver.resolveOrNull(bin.toString(), "no-such-cli-xyz"));
        } finally {
            Files.deleteIfExists(bin);
        }
    }

    public void testResolveOrNullReturnsNullWhenMissing() {
        assertNull(CliPathResolver.resolveOrNull("", "no-such-cli-xyz-123"));
        assertEquals("no-such-cli-xyz-123", CliPathResolver.resolve("", "no-such-cli-xyz-123"));
    }

    public void testFindInExtraDirsRejectsUnsafeNames() {
        assertNull(CliPathResolver.findInExtraDirs("../../bin/sh"));
    }

    public void testResolvedPathIsCachedUntilClearedOrGone() throws Exception {
        CliPathResolver.clearResolveCache();
        Path bin = Files.createTempFile("prepushchecker-cached-cli", "");
        try {
            assertTrue(bin.toFile().setExecutable(true));
            assertEquals(bin.toString(), CliPathResolver.resolveOrNull(bin.toString(), "no-such-cli-xyz"));
            int size = CliPathResolver.resolveCacheSize();
            assertTrue(size >= 1);
            // Second lookup is a cache hit: no new entry, same answer.
            assertEquals(bin.toString(), CliPathResolver.resolveOrNull(bin.toString(), "no-such-cli-xyz"));
            assertTrue(CliPathResolver.resolveCacheSize() <= size);
            CliPathResolver.clearResolveCache();
            assertEquals(bin.toString(), CliPathResolver.resolveOrNull(bin.toString(), "no-such-cli-xyz"));
        } finally {
            Files.deleteIfExists(bin);
        }
        // A cached path that no longer exists is looked up again (and is now missing).
        assertNull(CliPathResolver.resolveOrNull(bin.toString(), "no-such-cli-xyz"));
        CliPathResolver.clearResolveCache();
    }

    public void testMissingCliIsCachedBriefly() {
        CliPathResolver.clearResolveCache();
        long started = System.nanoTime();
        assertNull(CliPathResolver.resolveOrNull("", "no-such-cli-cache-123"));
        long firstNanos = System.nanoTime() - started;
        started = System.nanoTime();
        assertNull(CliPathResolver.resolveOrNull("", "no-such-cli-cache-123"));
        long secondNanos = System.nanoTime() - started;
        // The first lookup spawns a login shell; the cached miss answers without one.
        assertTrue(firstNanos + " vs " + secondNanos, secondNanos < firstNanos);
        assertTrue(secondNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20));
        CliPathResolver.clearResolveCache();
    }
}
