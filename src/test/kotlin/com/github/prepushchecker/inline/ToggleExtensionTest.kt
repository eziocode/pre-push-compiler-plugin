package com.github.prepushchecker.inline

import junit.framework.TestCase

class ToggleExtensionTest : TestCase() {
    fun testAddAndRemove() {
        assertEquals("md, java", InlineStatusBarWidget.toggleExtension(" .md ", "Java", true))
        assertEquals("md", InlineStatusBarWidget.toggleExtension("md, java", ".java", false))
        assertEquals("java", InlineStatusBarWidget.toggleExtension("", "java", true))
        assertEquals("java", InlineStatusBarWidget.toggleExtension("java", "java", true))
    }
}
