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

    public void testSmartBuildDefaultsToEnabled() {
        assertTrue(PrePushCheckerSettings.isSmartBuildEnabled(getProject()));
    }

    public void testSmartBuildCanBeDisabled() {
        try {
            PrePushCheckerSettings.setSmartBuildEnabled(getProject(), false);
            assertFalse(PrePushCheckerSettings.isSmartBuildEnabled(getProject()));
        } finally {
            PrePushCheckerSettings.setSmartBuildEnabled(getProject(), true);
        }
    }

    public void testCompileRelevanceFilter() {
        assertTrue(SmartBuildService.isCompileRelevant("/p/src/main/java/A.java"));
        assertTrue(SmartBuildService.isCompileRelevant("/p/module/pom.xml"));
        assertFalse(SmartBuildService.isCompileRelevant("/p/target/generated-sources/A.java"));
        assertFalse(SmartBuildService.isCompileRelevant("/p/build/classes/A.java"));
        assertFalse(SmartBuildService.isCompileRelevant("/p/.idea/workspace.xml"));
        assertFalse(SmartBuildService.isCompileRelevant("/p/README.md"));
    }
}
