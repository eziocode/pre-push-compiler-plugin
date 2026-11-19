package com.github.prepushchecker.review;

import com.intellij.application.options.CodeStyle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;

import java.util.ArrayList;
import java.util.List;

/** Format the preview on a PSI copy. Only copy formatted changed lines back into the proposal. */
final class ReviewFixFormatter {
    private ReviewFixFormatter() {}

    static String format(Project project, VirtualFile file, String original, String proposed) {
        Diff.Change changes;
        try {
            changes = Diff.buildChanges(original.split("\\n", -1), proposed.split("\\n", -1));
        } catch (FilesTooBigForDiffException e) {
            return proposed;
        }
        if (changes == null) return proposed;
        String[] result = {proposed};
        Runnable format = () -> com.intellij.openapi.command.CommandProcessor.getInstance().runUndoTransparentAction(() -> WriteAction.run(() -> {
            if (project.isDisposed()) return;
            PsiFile source = PsiManager.getInstance(project).findFile(file);
            if (source == null) return;
            PsiFile copy = PsiFileFactory.getInstance(project).createFileFromText(
                file.getName(), source.getLanguage(), proposed, true, false, false, file);
            if (copy == null) return;
            PsiDocumentManager psi = PsiDocumentManager.getInstance(project);
            Document doc = psi.getDocument(copy);
            if (doc == null) return;
            List<TextRange> ranges = new ArrayList<>();
            List<RangeMarker> markers = new ArrayList<>();
            for (Diff.Change h = changes; h != null; h = h.link) {
                if (h.inserted == 0) continue;
                int start = doc.getLineStartOffset(h.line1);
                int end = doc.getLineEndOffset(h.line1 + h.inserted - 1);
                if (start == end) continue;
                ranges.add(new TextRange(start, end));
                RangeMarker marker = doc.createRangeMarker(start, end);
                marker.setGreedyToLeft(true);
                marker.setGreedyToRight(true);
                markers.add(marker);
            }
            if (ranges.isEmpty()) return;
            try {
                CodeStyle.runWithLocalSettings(project, CodeStyle.getSettings(source),
                    () -> CodeStyleManager.getInstance(project).reformatText(copy, ranges));
                psi.doPostponedOperationsAndUnblockDocument(doc);
                StringBuilder text = new StringBuilder(proposed);
                for (int i = ranges.size() - 1; i >= 0; i--) {
                    RangeMarker marker = markers.get(i);
                    if (!marker.isValid()) return;
                    TextRange range = ranges.get(i);
                    text.replace(range.getStartOffset(), range.getEndOffset(),
                        doc.getText(new TextRange(marker.getStartOffset(), marker.getEndOffset())));
                }
                result[0] = text.toString();
            } finally {
                markers.forEach(RangeMarker::dispose);
            }
        }));
        if (ApplicationManager.getApplication().isDispatchThread()) format.run();
        else ApplicationManager.getApplication().invokeAndWait(format, ModalityState.nonModal());
        return result[0];
    }
}
