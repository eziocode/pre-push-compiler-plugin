package com.github.prepushchecker;

import com.intellij.openapi.wm.ToolWindowContentUiType;
import junit.framework.TestCase;

import static com.intellij.openapi.wm.ToolWindowContentUiType.COMBO;
import static com.intellij.openapi.wm.ToolWindowContentUiType.TABBED;

public class AdaptiveContentTabsTest extends TestCase {

    private static ToolWindowContentUiType choose(ToolWindowContentUiType current, int width) {
        return AdaptiveContentTabs.choose(current, width, 400, 24);
    }

    public void testTabsStayWhileTheyFit() {
        assertEquals(TABBED, choose(TABBED, 400));
        assertEquals(TABBED, choose(TABBED, 900));
    }

    public void testNarrowWindowSwitchesToDropdown() {
        assertEquals(COMBO, choose(TABBED, 399));
        assertEquals(COMBO, choose(COMBO, 200));
    }

    public void testDropdownNeedsHysteresisBeforeReturningToTabs() {
        assertEquals(COMBO, choose(COMBO, 400));
        assertEquals(COMBO, choose(COMBO, 423));
        assertEquals(TABBED, choose(COMBO, 424));
    }
}
