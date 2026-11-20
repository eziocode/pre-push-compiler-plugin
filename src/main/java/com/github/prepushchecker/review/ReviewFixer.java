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
                String original = snapshot.text;
                Result result;
                try {
                    result = FixPrefetch.takeOrNull(project, finding, original);
                    if (result == null) result = generateFor(project, vf, finding, original, indicator);
                } catch (ProcessCanceledException pce) {
                    throw pce;
                } catch (Exception ex) {
                    ReviewService.getInstance(project).notify("AI fix failed: "
                        + AiOutputSanitizer.truncate(String.valueOf(ex.getMessage()), 500), NotificationType.ERROR, null);
                    return;
                }
                if (result == null) {
                    ReviewService.getInstance(project).notify(
                        "The AI did not return a usable fix. Fix it by hand, then click Resolve.",
                        NotificationType.WARNING, null);
                    return;
                }
                String preview = result.proposed();
                if (preview.equals(original)) {
                    ReviewService.getInstance(project).notify(
                        "The AI fix does not change the file. Fix it by hand, then click Resolve.",
                        NotificationType.WARNING, null);
                    return;
                }
                List<String> removed = result.dropped();
                List<String> broken = result.errors();
                ApplicationManager.getApplication().invokeLater(
                    () -> showAndApply(project, vf, snapshot, preview, removed, broken, finding), ModalityState.nonModal());
            }
        });
    }

    /** A proposed fix and what is still wrong with it (both lists empty for a clean fix). */
    record Result(@NotNull ReviewPrompt.Fix fix, @NotNull String proposed, @NotNull List<String> dropped,
                  @NotNull List<String> errors) {}

    /** One AI answer and its problems. */
    private record Attempt(@Nullable ReviewPrompt.Fix fix, boolean truncated, boolean repeats,
                           @NotNull String proposed, @NotNull List<String> dropped, @NotNull List<String> errors) {
        boolean usable(String original) {
            return fix != null && !repeats && !proposed.equals(original);
        }

        /** Lower is better. */
        int badness(String original) {
            if (!usable(original)) return Integer.MAX_VALUE;
            return errors.size() * 10 + dropped.size();
        }

        boolean clean(String original) {
            return usable(original) && errors.isEmpty() && dropped.isEmpty();
        }
    }

    /**
     * Asks the AI for a fix of {@code finding} in {@code original} and checks it (unusable or
     * no-op answer, copied context, deleted lines, new syntax errors). When anything is wrong it
     * asks once more with every problem listed, so a fix takes at most two AI calls. Call off the EDT.
     *
     * @return the best fix, or {@code null} when neither answer was usable
     */
    private static final java.util.Set<Project> BATCHES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Files fixed at the same time in a batch; findings of one file are always fixed one after another. */
    static final int BATCH_PARALLEL_FILES = 2;

    static boolean isBatchRunning(@NotNull Project project) {
        return BATCHES.contains(project);
    }

    /** Fixes proposed for one file in a batch. */
    record FileFix(@NotNull VirtualFile file, @NotNull Snapshot snapshot, @NotNull String proposed,
                   @NotNull List<ReviewFinding> fixed, @NotNull List<String> warnings) {}

    /**
     * "Resolve Checked with AI": fixes every finding in {@code findings}. Findings of one file are
     * fixed in turn on the evolving text (each later finding's line follows the earlier edits);
     * up to {@link #BATCH_PARALLEL_FILES} files run at once. Nothing is written until the developer
     * reviews every file's diff and accepts it; a file edited meanwhile is skipped.
     */
    public static void fixManyWithAi(@NotNull Project project, @NotNull List<ReviewFinding> findings) {
        if (findings.isEmpty() || !BATCHES.add(project)) return;
        java.util.Map<String, List<ReviewFinding>> byFile = new java.util.LinkedHashMap<>();
        for (ReviewFinding f : findings) byFile.computeIfAbsent(f.path, k -> new ArrayList<>()).add(f);
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
            "AI fixes for " + findings.size() + " finding(s) in " + byFile.size() + " file(s)", true) {
            private final List<FileFix> results = java.util.Collections.synchronizedList(new ArrayList<>());
            private final List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.ExecutorService pool = com.intellij.util.concurrency.AppExecutorUtil
                    .createBoundedApplicationPoolExecutor("PrePushChecker-AiFixBatch", BATCH_PARALLEL_FILES);
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                try {
                    for (var entry : byFile.entrySet()) {
                        futures.add(pool.submit(() -> ProgressManager.getInstance().executeProcessUnderProgress(() -> {
                            fixFile(project, entry.getKey(), entry.getValue(), indicator, results, failures);
                            indicator.setFraction((double) done.incrementAndGet() / byFile.size());
                        }, indicator)));
                    }
                    for (var f : futures) {
                        while (true) {
                            indicator.checkCanceled();
                            try {
                                f.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                break;
                            } catch (java.util.concurrent.TimeoutException ignored) {
                                // poll for cancel
                            } catch (java.util.concurrent.ExecutionException ex) {
                                if (ex.getCause() instanceof ProcessCanceledException pce) throw pce;
                                failures.add(String.valueOf(ex.getCause().getMessage()));
                                break;
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new ProcessCanceledException();
                            }
                        }
                    }
                } finally {
                    if (indicator.isCanceled()) futures.forEach(f -> f.cancel(true));
                    pool.shutdown();
                }
            }

            @Override
            public void onSuccess() {
                BATCHES.remove(project);
                if (!failures.isEmpty()) {
                    ReviewService.getInstance(project).notify("AI could not fix " + failures.size() + " finding(s): "
                        + AiOutputSanitizer.truncate(String.join("; ", failures), 500), NotificationType.WARNING, null);
                }
                if (results.isEmpty()) return;
                List<FileFix> sorted = new ArrayList<>(results);
                sorted.sort(java.util.Comparator.comparing(r -> r.file().getPath()));
                showAndApplyBatch(project, sorted);
            }

            @Override
            public void onFinished() {
                BATCHES.remove(project);
            }
        });
    }

    private static void fixFile(Project project, String path, List<ReviewFinding> findings, ProgressIndicator indicator,
                                List<FileFix> results, List<String> failures) {
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
        if (vf == null) {
            failures.add("file not found: " + path);
            return;
        }
        Snapshot snapshot = ReadActions.compute(() -> {
            Document d = FileDocumentManager.getInstance().getDocument(vf);
            return d == null ? null : new Snapshot(d, d.getText(), d.getModificationStamp());
        });
        if (snapshot == null) return;
        // Top to bottom; each line is re-mapped through the edits made so far.
        List<ReviewFinding> ordered = new ArrayList<>(findings);
        ordered.sort(java.util.Comparator.comparingInt(f -> f.line));
        String content = snapshot.text();
        List<ReviewFinding> fixed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ReviewFinding f : ordered) {
            indicator.checkCanceled();
            indicator.setText2(vf.getName() + ": " + AiOutputSanitizer.truncate(f.message, 80));
            ReviewFinding at = copyAt(f, translatedLine(snapshot.text(), content, f.line));
            try {
                Result r = content.equals(snapshot.text()) ? FixPrefetch.takeOrNull(project, f, content) : null;
                if (r == null) r = generateFor(project, vf, at, content, indicator);
                if (r == null || r.proposed().equals(content)) {
                    failures.add(vf.getName() + ":" + Math.max(1, at.line) + " (no usable fix)");
                    continue;
                }
                if (!r.errors().isEmpty()) warnings.add("line " + at.line + ": leaves syntax errors");
                if (!r.dropped().isEmpty()) warnings.add("line " + at.line + ": deletes " + r.dropped().size() + " existing line(s)");
                content = r.proposed();
                fixed.add(f);
            } catch (ProcessCanceledException pce) {
                throw pce;
            } catch (Exception ex) {
                failures.add(vf.getName() + ":" + Math.max(1, at.line) + " " + ex.getMessage());
            }
        }
        if (!fixed.isEmpty()) results.add(new FileFix(vf, snapshot, content, fixed, warnings));
    }

    private static int translatedLine(String before, String after, int line) {
        if (line <= 0 || before.equals(after)) return line;
        try {
            var change = com.intellij.util.diff.Diff.buildChanges(before.split("\n", -1), after.split("\n", -1));
            return change == null ? line : ReviewStateStore.translate(change, line - 1) + 1;
        } catch (com.intellij.util.diff.FilesTooBigForDiffException e) {
            return line;
        }
    }

    private static ReviewFinding copyAt(ReviewFinding f, int line) {
        ReviewFinding c = new ReviewFinding(f.path, line, f.rule, f.message, f.suggestion);
        c.id = f.id;
        return c;
    }

    private static void showAndApplyBatch(@NotNull Project project, @NotNull List<FileFix> fixes) {
        if (project.isDisposed()) return;
        BatchDialog dialog = new BatchDialog(project, fixes);
        if (!dialog.showAndGet()) return;
        int applied = 0;
        int skipped = 0;
        for (FileFix fix : dialog.accepted()) {
            Snapshot snap = fix.snapshot();
            if (snap.document().getModificationStamp() != snap.stamp()
                    || !applyPreview(project, snap.document(), snap.text(), fix.proposed())) {
                skipped++;
                continue;
            }
            FileDocumentManager.getInstance().saveDocument(snap.document());
            List<String> ids = fix.fixed().stream().map(f -> f.id).toList();
            ReviewStateStore store = ReviewStateStore.getInstance(project);
            // Findings left unfixed in this file follow the new line numbers.
            store.remapLines(fix.fixed().get(0).path, snap.text(), fix.proposed(), null);
            for (String id : ids) ReviewPanel.resolveInBackground(project, id, "ai");
            applied++;
        }
        if (skipped > 0) {
            ReviewService.getInstance(project).notify(skipped + " file(s) changed while the AI was working and were "
                + "not touched. Run Resolve with AI again for them.", NotificationType.WARNING, null);
        } else if (applied > 0) {
            ReviewService.getInstance(project).notify("Applied AI fixes to " + applied + " file(s).",
                NotificationType.INFORMATION, null);
        }
    }

    /** Lists the batch's files with a checkbox each and shows the selected file's diff. */
    private static final class BatchDialog extends DialogWrapper {
        private final Project project;
        private final List<FileFix> fixes;
        private final com.intellij.ui.CheckBoxList<FileFix> list = new com.intellij.ui.CheckBoxList<>();
        private final JBLabel warning = new JBLabel();
        private DiffRequestPanel diff;

        BatchDialog(Project project, List<FileFix> fixes) {
            super(project, true);
            this.project = project;
            this.fixes = fixes;
            setTitle("AI Fixes: " + fixes.size() + " File(s)");
            setOKButtonText("Apply Checked Fixes");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            for (FileFix f : fixes) {
                String label = f.file().getName() + "  (" + f.fixed().size() + " fix" + (f.fixed().size() == 1 ? "" : "es")
                    + (f.warnings().isEmpty() ? "" : ", check") + ")";
                list.addItem(f, label, f.warnings().isEmpty());
            }
            diff = DiffManager.getInstance().createRequestPanel(project, getDisposable(), getWindow());
            list.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) show(list.getSelectedIndex());
            });
            warning.setIcon(AllIcons.General.Warning);
            warning.setForeground(JBColor.namedColor("Label.errorForeground", JBColor.RED));
            warning.setBorder(JBUI.Borders.empty(0, 2, 6, 2));
            JPanel right = new JPanel(new BorderLayout());
            right.add(warning, BorderLayout.NORTH);
            right.add(diff.getComponent(), BorderLayout.CENTER);
            com.intellij.ui.OnePixelSplitter split = new com.intellij.ui.OnePixelSplitter(false, 0.25f);
            split.setFirstComponent(com.intellij.ui.ScrollPaneFactory.createScrollPane(list, true));
            split.setSecondComponent(right);
            split.setPreferredSize(JBUI.size(1100, 620));
            if (!fixes.isEmpty()) list.setSelectedIndex(0);
            show(0);
            return split;
        }

        private void show(int index) {
            if (index < 0 || index >= fixes.size()) return;
            FileFix f = fixes.get(index);
            DiffContentFactory factory = DiffContentFactory.getInstance();
            String title = String.join(" · ", f.fixed().stream().map(x -> AiOutputSanitizer.truncate(x.message, 60)).toList());
            diff.setRequest(new SimpleDiffRequest(title,
                factory.create(project, f.snapshot().text(), f.file().getFileType()),
                factory.create(project, f.proposed(), f.file().getFileType()), "Current", "With AI fixes"));
            warning.setText(f.warnings().isEmpty() ? "" : String.join("; ", f.warnings()) + ". Check the diff before applying.");
            warning.setVisible(!f.warnings().isEmpty());
        }

        List<FileFix> accepted() {
            List<FileFix> out = new ArrayList<>();
            for (FileFix f : fixes) if (list.isItemSelected(f)) out.add(f);
            return out;
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getOKAction(), getCancelAction()};
        }
    }

    /** Number of fixes the user is waiting for right now; background prefetch yields to them. */
    static final java.util.concurrent.atomic.AtomicInteger INTERACTIVE = new java.util.concurrent.atomic.AtomicInteger();

    /** {@link #generate} with the display path and rules of {@code finding}'s repository. */
    static @Nullable Result generateFor(@NotNull Project project, @NotNull VirtualFile vf, @NotNull ReviewFinding finding,
                                        @NotNull String content, @Nullable ProgressIndicator indicator) throws Exception {
        boolean interactive = indicator != null;
        if (interactive) INTERACTIVE.incrementAndGet();
        try {
            Path file = Path.of(finding.path);
            Path found = ReviewGit.repoRoot(file);
            Path root = found == null ? null : ReviewGit.canonical(found);
            ReviewRules rules = root == null ? null : ReviewService.getInstance(project).rules(root);
            String display = root == null ? file.getFileName().toString() : ReviewGit.relative(root, file);
            return generate(project, vf, display, rules == null ? null : rules.body(), finding, content, indicator);
        } finally {
            if (interactive) INTERACTIVE.decrementAndGet();
        }
    }

    static @Nullable Result generate(@NotNull Project project, @NotNull VirtualFile vf, @NotNull String display,
                                     @Nullable String rules, @NotNull ReviewFinding finding, @NotNull String original,
                                     @Nullable ProgressIndicator indicator) throws Exception {
        String system = ReviewPrompt.fixSystemPrompt(rules == null ? "(no rule file)" : rules);
        String user = ReviewPrompt.fixUserPrompt(display, finding, original);
        int lines = lineCount(original);
        int baseline = syntaxErrors(project, vf, original).size();
        if (indicator != null) indicator.setText("Asking the AI for a fix…");
        Attempt first = attempt(project, vf, system, user, original, baseline);
        Attempt best = first;
        if (!first.clean(original)) {
            if (indicator != null) {
                indicator.checkCanceled();
                indicator.setText("Checking the AI fix, asking once more…");
            }
            String retry = ReviewPrompt.fixCorrectionUserPrompt(user, first.fix(), lines, first.truncated(),
                first.repeats(), first.dropped(), first.errors(), first.fix() != null && first.proposed().equals(original));
            Attempt second = attempt(project, vf, system, retry, original, baseline);
            if (second.badness(original) < first.badness(original)
                    || (second.badness(original) == first.badness(original) && second.clean(original))) {
                best = second;
            }
        }
        if (best.fix() == null || best.repeats()) {
            if (best.repeats()) throw new IllegalArgumentException("The suggested fix repeats surrounding code. Review the finding manually.");
            return null;
        }
        return new Result(best.fix(), best.proposed(), best.dropped(), best.errors());
    }

    private static Attempt attempt(Project project, VirtualFile vf, String system, String user, String original,
                                   int baseline) throws Exception {
        String raw = CommitMessageGeneratorService.resolveProvider().generate(AiRequest.fix(system, user));
        ReviewPrompt.Fix fix = ReviewPrompt.parseFix(AiOutputSanitizer.cleanStructured(raw, "replacement"), original);
        // An answer that names the key but does not parse was most likely cut off mid-JSON.
        boolean truncated = fix == null && raw != null && raw.contains("\"replacement\"");
        if (fix == null) return new Attempt(null, truncated, false, original, List.of(), List.of());
        fix = ReviewPrompt.normalize(original, fix);
        if (ReviewPrompt.repeatsContext(original, fix)) return new Attempt(fix, false, true, original, List.of(), List.of());
        String proposed = ReviewFixFormatter.format(project, vf, original, ReviewPrompt.apply(original, fix));
        List<String> dropped = ReviewPrompt.droppedLines(original, fix);
        List<String> errors = newErrors(syntaxErrors(project, vf, proposed), baseline);
        return new Attempt(fix, false, false, proposed, dropped, errors);
    }

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
        // Other findings in this file still point at the old line numbers.
        ReviewStateStore.getInstance(project).remapLines(finding.path, original, proposed, finding.id);
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
