package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class BypassControllerTest extends BasePlatformTestCase {
    public void testSwitchStartsOff() {
        BypassController controller = BypassController.getInstance(getProject());
        assertFalse(controller.isActive());
        assertEquals(0, controller.remainingMillis());
    }

    public void testEnableThenDisable() {
        BypassController controller = BypassController.getInstance(getProject());
        try {
            controller.enable();
            assertTrue(controller.isActive());
            long remaining = controller.remainingMillis();
            assertTrue(remaining > PrePushCheckerSettings.bypassMaxAgeMillis() - 5_000);
            assertTrue(remaining <= PrePushCheckerSettings.bypassMaxAgeMillis());

            controller.disable();
            assertFalse(controller.isActive());
            assertEquals(0, controller.remainingMillis());
        } finally {
            controller.disable();
        }
    }

    public void testResetOnStartupKeepsSwitchTurnedOnThisSession() {
        BypassController controller = BypassController.getInstance(getProject());
        try {
            controller.enable();
            controller.resetOnStartup();
            assertTrue(controller.isActive());
        } finally {
            controller.disable();
        }
    }

    public void testFormatRemainingRoundsUpToWholeMinutes() {
        assertEquals("60 min", CompilationCheckerPanel.formatRemaining(60 * 60 * 1000L));
        assertEquals("1 min", CompilationCheckerPanel.formatRemaining(1_000L));
        assertEquals("2 min", CompilationCheckerPanel.formatRemaining(61_000L));
    }
}
