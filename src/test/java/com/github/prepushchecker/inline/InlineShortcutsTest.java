package com.github.prepushchecker.inline;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class InlineShortcutsTest extends BasePlatformTestCase {
    public void testFallbackFollowsOs() {
        assertEquals("⌥\\", InlineShortcuts.suggestFallback(true));
        assertEquals("Alt+\\", InlineShortcuts.suggestFallback(false));
    }

    public void testLabelsAreNeverBlank() {
        assertFalse(InlineShortcuts.suggest().isBlank());
        assertFalse(InlineShortcuts.accept().isBlank());
    }
}
