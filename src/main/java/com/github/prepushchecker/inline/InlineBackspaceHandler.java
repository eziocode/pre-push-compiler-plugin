package com.github.prepushchecker.inline;

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public final class InlineBackspaceHandler extends BackspaceHandlerDelegate {
    @Override
    public void beforeCharDeleted(char c, @NotNull PsiFile file, @NotNull Editor editor) { }

    @Override
    public boolean charDeleted(char c, @NotNull PsiFile file, @NotNull Editor editor) {
        if (InlineSuggestSettings.getInstance().settings().enabled)
            InlineSuggestService.getInstance().onEvent(editor, InlineTriggerPolicy.Event.BACKSPACE);
        return false;
    }
}
