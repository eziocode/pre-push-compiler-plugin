package com.github.prepushchecker.inline;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import org.jetbrains.annotations.NotNull;

public final class ToggleInlineSuggestAction extends ToggleAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public boolean isSelected(@NotNull AnActionEvent e) {
        return InlineSuggestSettings.getInstance().settings().enabled;
    }
    @Override public void setSelected(@NotNull AnActionEvent e, boolean selected) {
        InlineSuggestSettings.getInstance().settings().enabled = selected;
        InlineSuggestService.getInstance().settingsChanged();
        if (!selected) for (Editor editor : EditorFactory.getInstance().getAllEditors()) InlineGhostText.dismiss(editor);
    }
}
