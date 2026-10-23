package com.github.prepushchecker;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/** Writes {@link LayerRules#FILE_NAME} through the editor, so unsaved edits in an open tab can't conflict. */
final class LayerRulesFile {
    private LayerRulesFile() {
    }

    static void write(@NotNull Project project, @NotNull VirtualFile repositoryRoot, @NotNull String content)
            throws IOException {
        WriteCommandAction.writeCommandAction(project)
            .withName("Update Layer Rules")
            .run(() -> {
                VirtualFile file = repositoryRoot.findChild(LayerRules.FILE_NAME);
                if (file == null) file = repositoryRoot.createChildData(LayerRulesFile.class, LayerRules.FILE_NAME);
                Document document = FileDocumentManager.getInstance().getDocument(file);
                if (document == null) {
                    VfsUtil.saveText(file, content);
                    return;
                }
                document.setText(StringUtil.convertLineSeparators(content));
                FileDocumentManager.getInstance().saveDocument(document);
            });
    }
}
