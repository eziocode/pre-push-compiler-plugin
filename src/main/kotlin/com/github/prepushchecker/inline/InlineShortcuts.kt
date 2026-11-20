package com.github.prepushchecker.inline

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.SystemInfo

/**
 * Shortcut text for inline-suggestion labels, read from the active keymap so it follows the OS
 * (⌥\ on macOS) and any rebinding. Falls back to the plugin's default binding when unbound.
 */
object InlineShortcuts {
    const val SUGGEST_ACTION_ID = "PrePushChecker.InlineSuggest"

    /** Shortcut that requests a suggestion now, e.g. "Alt+\" or "⌥\". */
    @JvmStatic
    fun suggest(): String =
        KeymapUtil.getFirstKeyboardShortcutText(SUGGEST_ACTION_ID).ifBlank { suggestFallback(SystemInfo.isMac) }

    /** Shortcut that accepts the ghost text (the editor Tab action). */
    @JvmStatic
    fun accept(): String =
        KeymapUtil.getFirstKeyboardShortcutText(IdeActions.ACTION_EDITOR_TAB).ifBlank { "Tab" }

    @JvmStatic
    fun suggestFallback(mac: Boolean): String = if (mac) "⌥\\" else "Alt+\\"
}
