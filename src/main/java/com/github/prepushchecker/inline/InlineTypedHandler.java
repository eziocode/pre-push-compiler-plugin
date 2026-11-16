package com.github.prepushchecker.inline;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public final class InlineTypedHandler extends TypedHandlerDelegate {
    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (InlineSuggestSettings.getInstance().settings().enabled)
            InlineSuggestService.getInstance().onEvent(editor, InlineTriggerPolicy.Event.TYPED);
        return Result.CONTINUE;
    }
}
