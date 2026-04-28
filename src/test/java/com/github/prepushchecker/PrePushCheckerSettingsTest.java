package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class PrePushCheckerSettingsTest extends BasePlatformTestCase {
    public void testStrictSnapshotGuardDefaultsToDisabled() {
        assertFalse(PrePushCheckerSettings.isStrictSnapshotGuardEnabled(getProject()));
    }

    public void testStrictSnapshotGuardCanBeToggled() {
        try {
            PrePushCheckerSettings.setStrictSnapshotGuardEnabled(getProject(), true);
            assertTrue(PrePushCheckerSettings.isStrictSnapshotGuardEnabled(getProject()));

            PrePushCheckerSettings.setStrictSnapshotGuardEnabled(getProject(), false);
            assertFalse(PrePushCheckerSettings.isStrictSnapshotGuardEnabled(getProject()));
        } finally {
            PrePushCheckerSettings.setStrictSnapshotGuardEnabled(getProject(), false);
        }
    }

    public void testStashSnapshotFallbackDefaultsToDisabled() {
        assertFalse(PrePushCheckerSettings.isStashSnapshotFallbackEnabled(getProject()));
    }

    public void testStashSnapshotFallbackCanBeToggled() {
        try {
            PrePushCheckerSettings.setStashSnapshotFallbackEnabled(getProject(), true);
            assertTrue(PrePushCheckerSettings.isStashSnapshotFallbackEnabled(getProject()));

            PrePushCheckerSettings.setStashSnapshotFallbackEnabled(getProject(), false);
            assertFalse(PrePushCheckerSettings.isStashSnapshotFallbackEnabled(getProject()));
        } finally {
            PrePushCheckerSettings.setStashSnapshotFallbackEnabled(getProject(), false);
        }
    }
}
