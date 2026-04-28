package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class CompilationEntryRendererTest extends BasePlatformTestCase {
    public void testDisplayTextIsTrimmedForLongMessages() {
        String entry = "[src/main/java/ImportNewAction1.java] "
            + "Strict A/B dependency check failed because this line is intentionally long "
            + "and should not overflow the dialog list viewport.";

        String displayText = CompilationEntryRenderer.displayText(entry);

        assertTrue(displayText.length() <= 100);
        assertTrue(displayText.endsWith("..."));
    }
}
