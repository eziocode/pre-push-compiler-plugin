package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class PrePushCompilationHandlerTest extends BasePlatformTestCase {
    public void testBackgroundCompilationMarkerIsRecognized() {
        assertTrue(PrePushCompilationHandler.isBackgroundCompilation(
            List.of(PrePushCompilationHandler.COMPILATION_RUNNING_IN_BACKGROUND)
        ));
    }

    public void testNormalCompilationErrorsAreNotBackgroundMarker() {
        assertFalse(PrePushCompilationHandler.isBackgroundCompilation(
            List.of("[src/main/java/App.java:10] cannot find symbol")
        ));
    }
}
