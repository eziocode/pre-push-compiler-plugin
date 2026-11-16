package com.github.prepushchecker.inline;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class InlineSuggestConfigurableTest extends BasePlatformTestCase {
    public void testSettingsPanelCreatesResetsAndApplies() throws Exception {
        InlineSuggestConfigurable configurable = new InlineSuggestConfigurable();
        try {
            assertNotNull(configurable.createComponent());
            configurable.reset();
            assertFalse(configurable.isModified());
            configurable.apply();
            assertFalse(configurable.isModified());
        } finally {
            configurable.disposeUIResources();
        }
    }
}
