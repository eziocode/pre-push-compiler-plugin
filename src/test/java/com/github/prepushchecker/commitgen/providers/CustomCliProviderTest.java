package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.cli.AiCli;
import junit.framework.TestCase;

public class CustomCliProviderTest extends TestCase {

    public void testBlankTemplateUsesPresetOfKnownBinary() {
        assertEquals(AiCli.GEMINI.defaultArgsTemplate(), CustomCliProvider.effectiveTemplate("", AiCli.GEMINI));
        assertEquals(AiCli.QWEN.defaultArgsTemplate(), CustomCliProvider.effectiveTemplate(null, AiCli.QWEN));
        assertEquals("", CustomCliProvider.effectiveTemplate(" ", null));
    }

    public void testSavedTemplateWins() {
        assertEquals("ask {prompt}", CustomCliProvider.effectiveTemplate("ask {prompt}", AiCli.GEMINI));
    }

    public void testTerminalErrorsAreRecognised() {
        assertTrue(CustomCliProvider.needsTerminal("Codex CLI exited with code 1:\nError: stdin is not a terminal"));
        assertTrue(CustomCliProvider.needsTerminal("Raw mode is not supported on the current process.stdin"));
        assertTrue(CustomCliProvider.needsTerminal("ioctl: Inappropriate ioctl for device"));
        assertFalse(CustomCliProvider.needsTerminal("401 unauthorized"));
        assertFalse(CustomCliProvider.needsTerminal(null));
    }
}
