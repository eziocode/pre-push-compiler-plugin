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
import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.List;

/**
 * "Resolve with AI": asks the AI for a minimal edit, shows it as a diff, and applies it as one
 * undoable command only when the developer accepts. The AI never writes files itself.
 */
public final class ReviewFixer {

    private ReviewFixer() {}

    /** Safe to call from the EDT: file lookup, document access and git calls all run in the background task. */
    public static void fixWithAi(@NotNull Project project, @NotNull ReviewFinding finding) {
        Path file = Path.of(finding.path);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "AI fix for review finding", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(finding.path);
                if (vf == null) {
                    ReviewService.getInstance(project).notify("File not found: " + finding.path, NotificationType.WARNING, null);
                    return;
                }
                Snapshot snapshot = ReadActions.compute(() -> {
                    Document d = FileDocumentManager.getInstance().getDocument(vf);
                    return d == null ? null : new Snapshot(d, d.getText(), d.getModificationStamp());
                });
                if (snapshot == null) return;
                Path found = ReviewGit.repoRoot(file);
                Path root = found == null ? null : ReviewGit.canonical(found);

                indicator.setText("Asking the AI for a fix…");
                String original = snapshot.text;
                ReviewRules rules = root == null ? null : ReviewService.getInstance(project).rules(root);
                String display = root == null ? file.getFileName().toString() : ReviewGit.relative(root, file);
                String system = ReviewPrompt.fixSystemPrompt(rules == null ? "(no rule file)" : rules.body());
                String user = ReviewPrompt.fixUserPrompt(display, finding, original);
                int lines = lineCount(original);
                ReviewPrompt.Fix fix;
                List<String> dropped = List.of();
                try {
                    fix = askForFix(system, user, lines);
                    if (fix != null) dropped = ReviewPrompt.droppedLines(original, fix);
                    if (!dropped.isEmpty()) {
                        // The AI replaced more than it rewrote. Ask once more, showing what went missing.
                        indicator.checkCanceled();
                        indicator.setText("AI fix removed existing lines, asking again…");
                        ReviewPrompt.Fix retry = askForFix(system,
                            ReviewPrompt.fixRetryUserPrompt(user, fix, dropped), lines);
                        if (retry != null) {
                            List<String> retryDropped = ReviewPrompt.droppedLines(original, retry);
                            if (retryDropped.size() <= dropped.size()) {
                                fix = retry;
                                dropped = retryDropped;
                            }
                        }
                    }
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
                List<String> removed = dropped;
                ApplicationManager.getApplication().invokeLater(
                    () -> showAndApply(project, vf, snapshot, proposed, removed, finding), ModalityState.nonModal());
            }
        });
    }

    private static @Nullable ReviewPrompt.Fix askForFix(@NotNull String system, @NotNull String user, int lines)
        throws Exception {
        String raw = CommitMessageGeneratorService.resolveProvider().generate(AiRequest.fix(system, user));
        return ReviewPrompt.parseFix(AiOutputSanitizer.cleanStructured(raw, "replacement"), lines);
    }

    /** Document state captured in one read action. */
    private record Snapshot(@NotNull Document document, @NotNull String text, long stamp) {}

    private static void showAndApply(@NotNull Project project, @NotNull VirtualFile vf, @NotNull Snapshot snapshot,
                                     @NotNull String proposed, @NotNull List<String> removed,
                                     @NotNull ReviewFinding finding) {
        if (project.isDisposed()) return;
        Document document = snapshot.document;
        String original = snapshot.text;
        // Modification stamp needs no read lock; the text itself is re-checked inside the write command.
        if (document.getModificationStamp() != snapshot.stamp) {
            notifyChanged(project);
            return;
        }
        FixDialog dialog = new FixDialog(project, vf, original, proposed, removed, finding);
        if (!dialog.showAndGet()) return;

        boolean[] applied = {false};
        WriteCommandAction.runWriteCommandAction(project, "Apply AI Review Fix", null, () -> {
            if (!document.getText().equals(original)) return;
            int[] range = changedRange(original, proposed);
            document.replaceString(range[0], original.length() - range[1],
                proposed.substring(range[0], proposed.length() - range[1]));
            applied[0] = true;
        });
        if (!applied[0]) {
            notifyChanged(project);
            return;
        }
        FileDocumentManager.getInstance().saveDocument(document);
        ReviewPanel.resolveInBackground(project, finding.id, "ai");
    }

    private static void notifyChanged(@NotNull Project project) {
        ReviewService.getInstance(project).notify(
            "The file changed while the AI was working. Run Resolve with AI again.", NotificationType.WARNING, null);
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
        private final List<String> removed;
        private final ReviewFinding finding;

        FixDialog(Project project, VirtualFile file, String original, String proposed, List<String> removed,
                  ReviewFinding finding) {
            super(project, true);
            this.project = project;
            this.file = file;
            this.original = original;
            this.proposed = proposed;
            this.removed = removed;
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
            if (!removed.isEmpty()) {
                JBLabel warning = new JBLabel("This fix deletes " + removed.size() + " existing line(s): "
                    + String.join("  ·  ", removed.stream().limit(3).map(l -> AiOutputSanitizer.truncate(l, 80)).toList())
                    + (removed.size() > 3 ? "  …" : "") + ". Check the diff before applying.",
                    AllIcons.General.Warning, SwingConstants.LEFT);
                warning.setForeground(JBColor.namedColor("Label.errorForeground", JBColor.RED));
                warning.setBorder(JBUI.Borders.empty(0, 2, 6, 2));
                root.add(warning, BorderLayout.NORTH);
            }
            root.setPreferredSize(JBUI.size(900, 560));
            return root;
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getOKAction(), getCancelAction()};
        }
    }
}
