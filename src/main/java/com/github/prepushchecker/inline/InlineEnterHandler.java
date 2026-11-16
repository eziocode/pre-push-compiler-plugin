package com.github.prepushchecker.inline;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public final class InlineEnterHandler extends EnterHandlerDelegateAdapter {
    @Override
    public @NotNull Result postProcessEnter(@NotNull PsiFile file, @NotNull Editor editor, @NotNull DataContext dataContext) {
        if (InlineSuggestSettings.getInstance().settings().enabled)
            InlineSuggestService.getInstance().onEvent(editor, InlineTriggerPolicy.Event.ENTER);
        return Result.Continue;
    }
}
