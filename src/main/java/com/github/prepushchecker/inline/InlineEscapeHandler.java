package com.github.prepushchecker.inline;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Esc dismisses the inline suggestion shown in this editor; otherwise the original Esc runs. */
public final class InlineEscapeHandler extends EditorActionHandler {
    private final EditorActionHandler original;

    public InlineEscapeHandler(EditorActionHandler original) {
        this.original = original;
    }

    @Override
    protected boolean isEnabledForCaret(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
        return InlineGhostText.isShowing(editor) || original.isEnabled(editor, caret, dataContext);
    }

    @Override
    public boolean executeInCommand(@NotNull Editor editor, DataContext dataContext) {
        return !InlineGhostText.isShowing(editor) && original.executeInCommand(editor, dataContext);
    }

    @Override
    protected void doExecute(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
        if (InlineGhostText.isShowing(editor)) {
            InlineGhostText.dismiss(editor);
        } else {
            original.execute(editor, caret, dataContext);
        }
    }
}
