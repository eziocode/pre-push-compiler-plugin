package com.github.prepushchecker.review;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class ReviewSettingsTest extends BasePlatformTestCase {
    private static final String ENABLED = "prepushchecker.review.enabled";
    private static final String MIGRATED = "prepushchecker.review.enabledDefaultMigrated";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PropertiesComponent props = PropertiesComponent.getInstance(getProject());
        props.unsetValue(ENABLED);
        props.unsetValue(MIGRATED);
        ReviewStateStore.getInstance(getProject()).clear();
    }

    public void testNewProjectDefaultsOff() {
        assertFalse(ReviewSettings.isEnabled(getProject()));
        assertTrue(PropertiesComponent.getInstance(getProject()).isValueSet(MIGRATED));
    }

    public void testExistingUserKeepsReviewOn() {
        ReviewSettings.migrateEnabledDefault(getProject(), () -> true);
        assertTrue(ReviewSettings.isEnabled(getProject()));
        assertTrue(PropertiesComponent.getInstance(getProject()).getBoolean(ENABLED, false));
    }

    public void testExistingReviewStateCountsAsUsedBefore() {
        ReviewStateStore.getInstance(getProject()).record("/r/A.java", "blob1", java.util.List.of());
        assertTrue(ReviewSettings.isEnabled(getProject()));
    }

    public void testMigrationRunsOnce() {
        ReviewSettings.migrateEnabledDefault(getProject(), () -> false);
        ReviewSettings.migrateEnabledDefault(getProject(), () -> true);
        assertFalse(ReviewSettings.isEnabled(getProject()));
    }

    public void testExplicitOffIsKept() {
        PropertiesComponent.getInstance(getProject()).setValue(ENABLED, "false");
        ReviewSettings.migrateEnabledDefault(getProject(), () -> true);
        assertFalse(ReviewSettings.isEnabled(getProject()));
    }

    public void testSetEnabledRoundTrips() {
        ReviewSettings.setEnabled(getProject(), true);
        assertTrue(ReviewSettings.isEnabled(getProject()));
        ReviewSettings.setEnabled(getProject(), false);
        assertFalse(ReviewSettings.isEnabled(getProject()));
    }
}
