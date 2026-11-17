package com.github.prepushchecker.inline

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/** Status bar icon for inline suggestions (View → Appearance → Status Bar Widgets can hide it). */
class InlineStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = InlineStatusBarWidget.ID
    override fun getDisplayName() = "Pre-Push Checker Inline Suggestions"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = InlineStatusBarWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar) = true
}
