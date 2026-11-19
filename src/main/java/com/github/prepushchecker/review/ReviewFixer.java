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
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
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
import java.util.ArrayList;
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
                List<String> errors = List.of();
                String proposed = original;
                try {
                    fix = askForFix(system, user, original, lines);
                    // An unparseable or no-op answer is usually a one-off: ask again with what was wrong.
                    for (int attempt = 0; attempt < MAX_NO_CHANGE_RETRIES && !changes(original, fix); attempt++) {
                        indicator.checkCanceled();
                        indicator.setText(fix == null
                            ? "AI answer was not usable, asking again…"
                            : "AI fix did not change the file, asking again…");
                        ReviewPrompt.Fix retry = askForFix(system,
                            ReviewPrompt.fixNoChangeRetryUserPrompt(user, fix, lines), original, lines);
                        if (retry != null || fix == null) fix = retry;
                    }
                    if (fix != null) dropped = ReviewPrompt.droppedLines(original, fix);
                    if (!dropped.isEmpty()) {
                        // The AI replaced more than it rewrote. Ask once more, showing what went missing.
                        indicator.checkCanceled();
                        indicator.setText("AI fix removed existing lines, asking again…");
                        ReviewPrompt.Fix retry = askForFix(system,
                            ReviewPrompt.fixRetryUserPrompt(user, fix, dropped), original, lines);
                        if (changes(original, retry)) {
                            List<String> retryDropped = ReviewPrompt.droppedLines(original, retry);
                            if (retryDropped.size() <= dropped.size()) {
                                fix = retry;
                                dropped = retryDropped;
                            }
                        }
                    }
                    if (fix != null) {
                        // A fix that breaks the file's syntax is asked for once more, with the errors.
                        indicator.checkCanceled();
                        int baseline = syntaxErrors(project, vf, original).size();
                        proposed = ReviewFixFormatter.format(project, vf, original, ReviewPrompt.apply(original, fix));
                        errors = newErrors(syntaxErrors(project, vf, proposed), baseline);
                        if (!errors.isEmpty()) {
                            indicator.setText("AI fix does not parse, asking again…");
                            ReviewPrompt.Fix retry = askForFix(system,
                                ReviewPrompt.fixSyntaxRetryUserPrompt(user, fix, errors), original, lines);
                            if (changes(original, retry)) {
                                String formatted = ReviewFixFormatter.format(project, vf, original, ReviewPrompt.apply(original, retry));
                                List<String> retryErrors = newErrors(syntaxErrors(project, vf, formatted), baseline);
                                List<String> retryDropped = ReviewPrompt.droppedLines(original, retry);
                                if (retryErrors.size() < errors.size() && retryDropped.size() <= dropped.size()) {
                                    fix = retry;
                                    proposed = formatted;
                                    errors = retryErrors;
                                    dropped = retryDropped;
                                }
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
                String preview = proposed;
                if (proposed.equals(original)) {
                    ReviewService.getInstance(project).notify(
                        "The AI fix does not change the file. Fix it by hand, then click Resolve.",
                        NotificationType.WARNING, null);
                    return;
                }
                List<String> removed = dropped;
                List<String> broken = errors;
                ApplicationManager.getApplication().invokeLater(
                    () -> showAndApply(project, vf, snapshot, preview, removed, broken, finding), ModalityState.nonModal());
            }
        });
    }

    private static @Nullable ReviewPrompt.Fix askForFix(@NotNull String system, @NotNull String user,
                                                       @NotNull String original, int lines) throws Exception {
        ReviewPrompt.Fix fix = requestEdit(system, user, original, lines);
        if (fix == null || !ReviewPrompt.repeatsContext(original, fix)) return fix;
        String retry = user + "\nYour previous replacement for lines " + fix.startLine() + "-" + fix.endLine()
            + " repeats adjacent code outside the edit range:\n" + fix.replacement()
            + "\nCorrect the range or remove the copied context. Return only the corrected JSON.";
        fix = requestEdit(system, retry, original, lines);
        if (fix == null || ReviewPrompt.repeatsContext(original, fix)) {
            throw new IllegalArgumentException("The suggested fix repeats surrounding code. Review the finding manually.");
        }
        return fix;
    }

    private static @Nullable ReviewPrompt.Fix requestEdit(String system, String user, String original, int lines)
        throws Exception {
        String raw = CommitMessageGeneratorService.resolveProvider().generate(AiRequest.fix(system, user));
        ReviewPrompt.Fix fix = ReviewPrompt.parseFix(AiOutputSanitizer.cleanStructured(raw, "replacement"), lines);
        return fix == null ? null : ReviewPrompt.normalize(original, fix);
    }

    /** Extra attempts when the AI answer is unusable or leaves the file unchanged. */
    static final int MAX_NO_CHANGE_RETRIES = 2;

    /** {@code true} when {@code fix} is usable and actually edits {@code original}. */
    static boolean changes(@NotNull String original, @Nullable ReviewPrompt.Fix fix) {
        return fix != null && !ReviewPrompt.apply(original, fix).equals(original);
    }

    private static final int MAX_REPORTED_ERRORS = 8;

    /**
     * Parse errors of {@code text} as the file's language sees it ("line N: message"). A
     * non-physical copy is parsed, so nothing in the project changes. Empty for languages
     * without a parser (plain text).
     */
    static @NotNull List<String> syntaxErrors(@NotNull Project project, @NotNull VirtualFile vf, @NotNull String text) {
        return ReadActions.compute(() -> {
            if (project.isDisposed()) return List.<String>of();
            PsiFile copy = PsiFileFactory.getInstance(project).createFileFromText(vf.getName(), vf.getFileType(), text);
            if (copy == null) return List.<String>of();
            List<String> out = new ArrayList<>();
            for (PsiErrorElement e : PsiTreeUtil.collectElementsOfType(copy, PsiErrorElement.class)) {
                int line = StringUtil.offsetToLineNumber(text, e.getTextOffset()) + 1;
                out.add("line " + line + ": " + e.getErrorDescription());
            }
            return out;
        });
    }

    /** Errors beyond what the file already had; the file's own errors are not the fix's fault. */
    private static @NotNull List<String> newErrors(@NotNull List<String> errors, int baseline) {
        if (errors.size() <= baseline) return List.of();
        return errors.subList(0, Math.min(errors.size(), MAX_REPORTED_ERRORS));
    }

    /** Document state captured in one read action. */
    private record Snapshot(@NotNull Document document, @NotNull String text, long stamp) {}

    private static void showAndApply(@NotNull Project project, @NotNull VirtualFile vf, @NotNull Snapshot snapshot,
                                     @NotNull String proposed, @NotNull List<String> removed,
                                     @NotNull List<String> errors, @NotNull ReviewFinding finding) {
        if (project.isDisposed()) return;
        Document document = snapshot.document;
        String original = snapshot.text;
        // Modification stamp needs no read lock; the text itself is re-checked inside the write command.
        if (document.getModificationStamp() != snapshot.stamp) {
            notifyChanged(project);
            return;
        }
        FixDialog dialog = new FixDialog(project, vf, original, proposed, removed, errors, finding);
        if (!dialog.showAndGet()) return;

        if (!applyPreview(project, document, original, proposed)) {
            notifyChanged(project);
            return;
        }
        FileDocumentManager.getInstance().saveDocument(document);
        ReviewPanel.resolveInBackground(project, finding.id, "ai");
    }

    static boolean applyPreview(Project project, Document document, String original, String proposed) {
        boolean[] applied = {false};
        WriteCommandAction.runWriteCommandAction(project, "Apply AI Review Fix", null, () -> {
            if (!document.getText().equals(original)) return;
            int[] range = changedRange(original, proposed);
            document.replaceString(range[0], original.length() - range[1],
                proposed.substring(range[0], proposed.length() - range[1]));
            applied[0] = true;
        });
        return applied[0];
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
        private final List<String> errors;
        private final ReviewFinding finding;

        FixDialog(Project project, VirtualFile file, String original, String proposed, List<String> removed,
                  List<String> errors, ReviewFinding finding) {
            super(project, true);
            this.project = project;
            this.file = file;
            this.original = original;
            this.proposed = proposed;
            this.removed = removed;
            this.errors = errors;
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
            JPanel warnings = new JPanel();
            warnings.setLayout(new javax.swing.BoxLayout(warnings, javax.swing.BoxLayout.Y_AXIS));
            if (!errors.isEmpty()) {
                warnings.add(warning("This fix leaves the file with syntax errors ("
                    + String.join("; ", errors.stream().limit(3).map(l -> AiOutputSanitizer.truncate(l, 80)).toList())
                    + (errors.size() > 3 ? "; …" : "") + "). It will not compile as is."));
            }
            if (!removed.isEmpty()) {
                warnings.add(warning("This fix deletes " + removed.size() + " existing line(s): "
                    + String.join("  ·  ", removed.stream().limit(3).map(l -> AiOutputSanitizer.truncate(l, 80)).toList())
                    + (removed.size() > 3 ? "  …" : "") + ". Check the diff before applying."));
            }
            if (warnings.getComponentCount() > 0) root.add(warnings, BorderLayout.NORTH);
            root.setPreferredSize(JBUI.size(900, 560));
            return root;
        }

        private static JBLabel warning(String text) {
            JBLabel label = new JBLabel(text, AllIcons.General.Warning, SwingConstants.LEFT);
            label.setForeground(JBColor.namedColor("Label.errorForeground", JBColor.RED));
            label.setBorder(JBUI.Borders.empty(0, 2, 6, 2));
            return label;
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getOKAction(), getCancelAction()};
        }
    }
}
