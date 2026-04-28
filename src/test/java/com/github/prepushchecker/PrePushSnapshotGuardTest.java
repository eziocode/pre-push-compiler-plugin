package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class PrePushSnapshotGuardTest extends BasePlatformTestCase {
    public void testDisabledGuardDoesNotBlock() {
        PrePushCheckerSettings.setStrictSnapshotGuardEnabled(getProject(), false);

        assertTrue(PrePushSnapshotGuard.collectBlockingMessages(getProject()).isEmpty());
    }

    public void testBlockingMessageExplainsRemediation() {
        String message = PrePushSnapshotGuard.blockingMessage("src/main/java/App.java");

        assertTrue(message.contains("src/main/java/App.java"));
        assertTrue(message.contains("Commit or stash"));
        assertTrue(message.contains("Compilation Checker tool window"));
    }

    public void testProjectRelativePathIsUsedForProjectFiles() {
        String basePath = getProject().getBasePath();
        assertNotNull(basePath);

        String path = PrePushSnapshotGuard.toProjectRelativePath(
            getProject(), basePath + "/src/main/java/App.java");

        assertEquals("src/main/java/App.java", path);
    }
}
