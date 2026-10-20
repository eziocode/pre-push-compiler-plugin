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
}
