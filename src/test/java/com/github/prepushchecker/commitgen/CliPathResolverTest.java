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
}
