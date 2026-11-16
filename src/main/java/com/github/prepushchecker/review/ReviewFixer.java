package com.github.prepushchecker.review;

import com.github.prepushchecker.ReadActions;
import com.github.prepushchecker.commitgen.AiOutputSanitizer;
import com.github.prepushchecker.commitgen.AiRequest;
import com.github.prepushchecker.commitgen.CommitMessageGeneratorService;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.Map;

/**
 * "Resolve with AI": asks the AI for a minimal edit, shows it as a diff, and applies it as one
 * undoable command only when the developer accepts. The AI never writes files itself.
 */
public final class ReviewFixer {

    private ReviewFixer() {}

    public static void fixWithAi(@NotNull Project project, @NotNull ReviewFinding finding) {
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(finding.path);
        if (vf == null) {
            ReviewService.getInstance(project).notify("File not found: " + finding.path, NotificationType.WARNING, null);
            return;
        }
        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return;
        Path file = Path.of(finding.path);
        Path found = ReviewGit.repoRoot(file);
        Path root = found == null ? null : ReviewGit.canonical(found);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "AI fix for review finding", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Asking the AI for a fix…");
                String original = ReadActions.compute(() -> document.getText());
                ReviewRules rules = root == null ? null : ReviewService.getInstance(project).rules(root);
                String display = root == null ? file.getFileName().toString() : ReviewGit.relative(root, file);
                AiRequest request = AiRequest.fix(
                    ReviewPrompt.fixSystemPrompt(rules == null ? "(no rule file)" : rules.body()),
                    ReviewPrompt.fixUserPrompt(display, finding, original));
                ReviewPrompt.Fix fix;
                try {
                    String raw = CommitMessageGeneratorService.resolveProvider().generate(request);
                    Map<?, ?> answer = AiOutputSanitizer.cleanStructured(raw, "replacement");
                    fix = ReviewPrompt.parseFix(answer, lineCount(original));
                } catch (ProcessCanceledException pce) {
                    throw pce;
                } catch (Exception ex) {
                    ReviewService.getInstance(project).notify("AI fix failed: "
                        + AiOutputSanitizer.truncate(String.valueOf(ex.getMessage()), 500), NotificationType.ERROR, null);
                    return;
                }
                if (fix == null) {
                    ReviewService.getInstance(project).notify(
                        "The AI did not return a usable fix. Fix it by hand, then click Resolve.",
                        NotificationType.WARNING, null);
                    return;
                }
                String proposed = ReviewPrompt.apply(original, fix);
                ApplicationManager.getApplication().invokeLater(
                    () -> showAndApply(project, vf, document, original, proposed, finding), ModalityState.nonModal());
            }
        });
    }

    private static void showAndApply(@NotNull Project project, @NotNull VirtualFile vf, @NotNull Document document,
                                     @NotNull String original, @NotNull String proposed,
                                     @NotNull ReviewFinding finding) {
        if (project.isDisposed()) return;
        if (!document.getText().equals(original)) {
            ReviewService.getInstance(project).notify(
                "The file changed while the AI was working. Run Resolve with AI again.", NotificationType.WARNING, null);
            return;
        }
        FixDialog dialog = new FixDialog(project, vf, original, proposed, finding);
        if (!dialog.showAndGet()) return;

        WriteCommandAction.runWriteCommandAction(project, "Apply AI Review Fix", null, () -> {
            int[] range = changedRange(original, proposed);
            document.replaceString(range[0], original.length() - range[1],
                proposed.substring(range[0], proposed.length() - range[1]));
        });
        FileDocumentManager.getInstance().saveDocument(document);
        ReviewPanel.resolveInBackground(project, finding.id, "ai");
    }

    /** {@code [commonPrefix, commonSuffix]} lengths, non-overlapping. */
    static int[] changedRange(@NotNull String a, @NotNull String b) {
        int max = Math.min(a.length(), b.length());
        int prefix = 0;
        while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < max - prefix
            && a.charAt(a.length() - 1 - suffix) == b.charAt(b.length() - 1 - suffix)) suffix++;
        return new int[]{prefix, suffix};
    }

    private static int lineCount(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        return lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
    }

    private static final class FixDialog extends DialogWrapper {
        private final Project project;
        private final VirtualFile file;
        private final String original;
        private final String proposed;
        private final ReviewFinding finding;

        FixDialog(Project project, VirtualFile file, String original, String proposed, ReviewFinding finding) {
            super(project, true);
            this.project = project;
            this.file = file;
            this.original = original;
            this.proposed = proposed;
            this.finding = finding;
            setTitle("AI Fix: " + file.getName());
            setOKButtonText("Apply Fix");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            DiffContentFactory factory = DiffContentFactory.getInstance();
            SimpleDiffRequest request = new SimpleDiffRequest(finding.message,
                factory.create(project, original, file.getFileType()),
                factory.create(project, proposed, file.getFileType()),
                "Current", "With AI fix");
            DiffRequestPanel panel = DiffManager.getInstance().createRequestPanel(project, getDisposable(), getWindow());
            panel.setRequest(request);
            JPanel root = new JPanel(new BorderLayout());
            root.add(panel.getComponent(), BorderLayout.CENTER);
            root.setPreferredSize(JBUI.size(900, 560));
            return root;
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getOKAction(), getCancelAction()};
        }
    }
}
