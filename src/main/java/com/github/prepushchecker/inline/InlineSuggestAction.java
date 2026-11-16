package com.github.prepushchecker.inline;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.NotNull;

public final class InlineSuggestAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getData(CommonDataKeys.EDITOR) != null
            && InlineSuggestSettings.getInstance().settings().enabled);
    }
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor != null) InlineSuggestService.getInstance().requestNow(editor);
    }
}
