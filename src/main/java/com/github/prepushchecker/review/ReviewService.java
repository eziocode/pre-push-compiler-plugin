package com.github.prepushchecker.review;

import com.github.prepushchecker.commitgen.AiOutputSanitizer;
import com.github.prepushchecker.commitgen.AiRequest;
import com.github.prepushchecker.commitgen.CommitMessageGeneratorService;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs AI code reviews against each repository's rule file and decides whether a push may go.
 *
 * <p>Files outside the rule file's scope are never sent to the AI. Reviews are recorded per
 * content id in {@link ReviewStateStore}, so the same content is never reviewed twice.</p>
 */
@Service(Service.Level.PROJECT)
public final class ReviewService {

    private static final Logger LOG = Logger.getInstance(ReviewService.class);
    static final String NOTIFICATION_GROUP = "Pre-Push Compilation Checker";
    public static final String TOOL_WINDOW_ID = "Compilation Checker";
    public static final String TAB_NAME = "Code Review";
    private static final long MAX_NEW_FILE_BYTES = 1_000_000;

    /** One file to review: its content id and the numbered diff the AI sees. */
    public record Target(@NotNull Path root, @NotNull String relPath, @NotNull String contentId,
                         @NotNull String numberedDiff) {
        public @NotNull String absolutePath() {
            return key(ReviewGit.canonical(root).resolve(relPath));
        }
    }

    /** What a review run did. */
    public record RunResult(int reviewed, int outOfScope, int findings, boolean noRules,
                            @NotNull List<String> errors) {
        static RunResult empty(boolean noRules) {
            return new RunResult(0, 0, 0, noRules, List.of());
        }
    }

    private final Project project;
    private final AtomicBoolean running = new AtomicBoolean();

    public ReviewService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull ReviewService getInstance(@NotNull Project project) {
        return project.getService(ReviewService.class);
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Store key for a file: absolute, normalized, {@code /}-separated. */
    public static @NotNull String key(@NotNull Path file) {
        return file.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    // ── Targets ──────────────────────────────────────────────────────────────

    /**
     * Working-tree content of {@code files} (tracked: diff vs HEAD; untracked: whole file). Files
     * that could not be read reliably are not returned and are named in {@code errors}.
     */
    public @NotNull List<Target> workingTargets(@NotNull Collection<Path> files, @NotNull List<String> errors) {
        List<Target> out = new ArrayList<>();
        for (Map.Entry<Path, List<String>> e : groupByRoot(files).entrySet()) {
            Path root = e.getKey();
            ReviewRules rules = rules(root);
            if (rules == null) continue;
            List<String> rels = e.getValue().stream().filter(rules::inScope).toList();
            if (rels.isEmpty()) continue;
            out.addAll(workingTargets(root, rels, errors));
        }
        return out;
    }

    /**
     * Review targets for in-scope working-tree files of one repository. The content id is taken
     * before and after the diff is read; when they differ the file changed in between and the
     * diff may not be the content the id names, so the file is reported instead of reviewed.
     */
    static @NotNull List<Target> workingTargets(@NotNull Path root, @NotNull List<String> rels,
                                                @NotNull List<String> errors) {
        List<Target> out = new ArrayList<>();
        // No commit yet: everything is new, and "git diff HEAD" would fail for every file.
        boolean hasHead = ReviewGit.headSha(root) != null;
        Set<String> tracked = hasHead ? ReviewGit.tracked(root, rels) : Set.of();
        Map<String, String> ids = ReviewGit.hashObjects(root, rels);
        for (String rel : rels) {
            String id = ids.get(rel);
            if (id == null) {
                // Deleted: nothing to review. Present but not hashable: say so, do not skip silently.
                if (Files.exists(root.resolve(rel))) errors.add(rel + ": could not read the file with git.");
                continue;
            }
            String diff;
            if (tracked.contains(rel)) {
                String raw = ReviewGit.diffAgainstHead(root, rel);
                if (raw == null) {
                    errors.add(rel + ": git diff failed, the file was not reviewed.");
                    continue;
                }
                if (raw.isBlank()) continue; // same as HEAD: nothing to review
                diff = ReviewPrompt.numberDiff(raw);
            } else {
                String content = readSmallText(root.resolve(rel));
                if (content == null) continue; // binary or too large
                diff = ReviewPrompt.numberNewFile(content);
            }
            out.add(new Target(root, rel, id, diff));
        }
        if (out.isEmpty()) return out;
        Map<String, String> after = ReviewGit.hashObjects(root, out.stream().map(Target::relPath).toList());
        out.removeIf(t -> {
            if (t.contentId().equals(after.get(t.relPath()))) return false;
            errors.add(t.relPath() + ": changed while the review was starting, so it was not reviewed. "
                + "Review it again.");
            return true;
        });
        return out;
    }

    /** Committed content at HEAD of {@code rels}, diffed from {@code base} when known. */
    @NotNull List<Target> pushTargets(@NotNull Path root, @NotNull List<String> rels, @Nullable String base) {
        return pushTargets(root, rels, base, "HEAD");
    }

    /** Review targets for {@code rels} as committed in {@code rev}, diffed from {@code base}. */
    @NotNull List<Target> pushTargets(@NotNull Path root, @NotNull List<String> rels, @Nullable String base,
                                      @NotNull String rev) {
        List<Target> out = new ArrayList<>();
        Map<String, String> blobs = ReviewGit.blobsAt(root, rev, rels);
        String from = base != null ? base : ReviewGit.upstreamBase(root);
        for (String rel : rels) {
            String blob = blobs.get(rel);
            if (blob == null) continue;
            String raw = from == null ? "" : ReviewGit.diffRange(root, from, rev, rel);
            String diff;
            if (!raw.isBlank()) {
                diff = ReviewPrompt.numberDiff(raw);
            } else {
                String content = ReviewGit.showAt(root, rev, rel);
                if (content == null) continue;
                diff = ReviewPrompt.numberNewFile(content);
            }
            out.add(new Target(root, rel, blob, diff));
        }
        return out;
    }

    // ── Review run ───────────────────────────────────────────────────────────

    /**
     * Reviews {@code targets} (already in scope) and records the results. Call off the EDT.
     *
     * @param budget time left for all AI calls, or {@code null} for the default per call
     */
    public @NotNull RunResult review(@NotNull List<Target> targets, @Nullable ProgressIndicator indicator,
                                     @Nullable Duration budget) {
        if (targets.isEmpty()) return RunResult.empty(false);
        long deadline = budget == null ? Long.MAX_VALUE : System.nanoTime() + budget.toNanos();
        ReviewStateStore store = ReviewStateStore.getInstance(project);
        Map<Path, List<Target>> byRoot = new LinkedHashMap<>();
        for (Target t : targets) byRoot.computeIfAbsent(t.root(), r -> new ArrayList<>()).add(t);

        int reviewed = 0;
        int findings = 0;
        List<String> errors = new ArrayList<>();
        for (Map.Entry<Path, List<Target>> e : byRoot.entrySet()) {
            ReviewRules rules = rules(e.getKey());
            if (rules == null) continue;
            Map<String, Target> byDisplay = new LinkedHashMap<>();
            for (Target t : e.getValue()) byDisplay.put(t.relPath(), t);
            List<ReviewPrompt.FileDiff> diffs = new ArrayList<>();
            for (Target t : e.getValue()) diffs.add(new ReviewPrompt.FileDiff(t.relPath(), t.numberedDiff()));

            List<List<ReviewPrompt.FileDiff>> batches = ReviewPrompt.batches(diffs);
            for (int i = 0; i < batches.size(); i++) {
                List<ReviewPrompt.FileDiff> batch = batches.get(i);
                if (indicator != null) {
                    indicator.checkCanceled();
                    indicator.setText("AI review: " + (batches.size() > 1 ? "part " + (i + 1) + " of " + batches.size()
                        : batch.size() + " file(s)"));
                }
                AiRequest request = AiRequest.review(ReviewPrompt.reviewSystemPrompt(rules.body()),
                    ReviewPrompt.reviewUserPrompt(batch));
                long left = deadline - System.nanoTime();
                if (left <= Duration.ofSeconds(10).toNanos()) {
                    errors.add("Not enough time left to review " + batch.size() + " file(s).");
                    break;
                }
                if (left < request.timeout().toNanos()) request = request.withTimeout(Duration.ofNanos(left));

                Map<?, ?> answer;
                try {
                    String raw = CommitMessageGeneratorService.resolveProvider().generate(request);
                    if (LOG.isDebugEnabled()) LOG.debug("AI review answer: " + AiOutputSanitizer.truncate(raw, 4000));
                    answer = AiOutputSanitizer.cleanStructured(raw, "findings");
                } catch (ProcessCanceledException pce) {
                    throw pce;
                } catch (Exception ex) {
                    LOG.info("AI review failed", ex);
                    errors.add(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    continue;
                }
                Map<String, String> displayToAbs = new HashMap<>();
                for (ReviewPrompt.FileDiff f : batch) displayToAbs.put(f.displayPath(), byDisplay.get(f.displayPath()).absolutePath());
                // Never record "clean" for an answer that could not be read in full: that is a silent pass.
                ReviewPrompt.Verdict verdict = ReviewPrompt.judge(answer, displayToAbs);
                if (verdict.error() != null) {
                    LOG.info("AI review answer could not be used in full: " + (answer == null ? "<none>" : answer));
                    errors.add(verdict.error());
                }
                for (ReviewPrompt.FileDiff f : batch) {
                    Target t = byDisplay.get(f.displayPath());
                    List<ReviewFinding> mine = verdict.toRecord().get(t.absolutePath());
                    if (mine == null) continue;
                    store.record(t.absolutePath(), t.contentId(), mine);
                    reviewed++;
                    findings += mine.size();
                }
            }
        }
        return new RunResult(reviewed, 0, findings, false, errors);
    }

    /** Reviews working-tree {@code files} in a cancellable background task and reports the result. */
    public void reviewInBackground(@NotNull Collection<Path> files, @Nullable Runnable onDone) {
        reviewInBackground(files, false, onDone);
    }

    /**
     * Reviews every local change: {@code files} (from the IDE's change list) plus whatever git
     * reports as changed on disk in their repositories and the project's. The change list can be
     * behind a save that just happened; git is not, so a file edited a moment ago is not missed.
     */
    public void reviewLocalChangesInBackground(@NotNull Collection<Path> files) {
        reviewInBackground(files, true, null);
    }

    private void reviewInBackground(@NotNull Collection<Path> files, boolean addGitChanges, @Nullable Runnable onDone) {
        if (!running.compareAndSet(false, true)) {
            notify("A review is already running.", NotificationType.INFORMATION, null);
            return;
        }
        List<Path> given = List.copyOf(files);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "AI code review", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Collecting changes…");
                List<Path> copy = addGitChanges ? withGitChanges(given) : given;
                boolean anyRules = hasAnyRules(copy);
                if (!anyRules) {
                    report(RunResult.empty(true), copy.size());
                    return;
                }
                List<String> collectErrors = new ArrayList<>();
                List<Target> targets = workingTargets(copy, collectErrors);
                RunResult r = review(targets, indicator, null);
                List<String> errors = new ArrayList<>(collectErrors);
                errors.addAll(r.errors());
                int outOfScope = Math.max(0, copy.size() - targets.size() - collectErrors.size());
                report(new RunResult(r.reviewed(), outOfScope, r.findings(), false, errors), copy.size());
            }

            @Override
            public void onFinished() {
                running.set(false);
                if (onDone != null) ApplicationManager.getApplication().invokeLater(onDone);
            }
        });
    }

    /** Reviews the committed (pushed) content of {@code files}, as the push saw it. */
    public void reviewCommittedInBackground(@NotNull Path root, @NotNull List<String> rels) {
        if (!running.compareAndSet(false, true)) return;
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "AI code review", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                RunResult r = review(pushTargets(root, rels, null), indicator, null);
                report(r, rels.size());
            }

            @Override
            public void onFinished() {
                running.set(false);
            }
        });
    }

    private void report(@NotNull RunResult r, int requested) {
        if (r.noRules()) {
            notify("No review rule file found (" + ReviewRules.DEFAULT_FILE_NAME + "). Create one in "
                + "Settings → Tools → Pre-Push Checker → Code Review.", NotificationType.WARNING, null);
            return;
        }
        if (!r.errors().isEmpty()) {
            notify("AI review failed: " + AiOutputSanitizer.truncate(String.join("\n", r.errors()), 600),
                NotificationType.ERROR, null);
            if (r.reviewed() == 0) return;
        }
        if (r.reviewed() == 0) {
            notify(requested == 0 ? "No files selected for review."
                    : "None of the selected files match the review rules, nothing to review.",
                NotificationType.INFORMATION, null);
            return;
        }
        String text = r.findings() > 0
            ? "Review found " + r.findings() + " issue(s) in " + r.reviewed() + " file(s)."
            : r.errors().isEmpty()
                ? "Review passed: " + r.reviewed() + " file(s), no issues."
                // Some files failed: a "passed" here would read as a pass for those too.
                : "No issues in " + r.reviewed() + " reviewed file(s); the rest could not be reviewed.";
        if (r.outOfScope() > 0) text += " " + r.outOfScope() + " file(s) not in review scope.";
        notify(text, r.findings() == 0 ? NotificationType.INFORMATION : NotificationType.WARNING,
            r.findings() == 0 ? null : "Show Findings");
    }

    // ── Push gate ────────────────────────────────────────────────────────────

    /**
     * Push check for one repository. Returns blocking error lines (empty: allow).
     *
     * @param pushed     absolute paths the hook reported
     * @param rangeBase  base of the pushed range, when the hook sent it
     * @param pushedHead the pushed commit, when the hook sent it; may differ from HEAD
     * @param budget     time the push may spend on an on-push review
     */
    public @NotNull List<String> checkPush(@NotNull Path root, @NotNull Collection<Path> pushed,
                                           @Nullable String rangeBase, @Nullable String pushedHead,
                                           @Nullable ProgressIndicator indicator, @NotNull Duration budget) {
        if (!ReviewSettings.isEnabled(project)) return List.of();
        ReviewRules rules = rules(root);
        if (rules == null) return List.of();
        boolean mandatory = ReviewSettings.isMandatory(project, rules);
        ReviewSettings.Mode mode = ReviewSettings.mode(project);

        Path given = root.toAbsolutePath().normalize();
        root = ReviewGit.canonical(given);
        List<String> rels = new ArrayList<>();
        for (Path p : pushed) {
            Path abs = p.toAbsolutePath().normalize();
            String rel;
            if (abs.startsWith(given)) rel = ReviewGit.relative(given, abs);
            else if (abs.startsWith(root)) rel = ReviewGit.relative(root, abs);
            else continue;
            if (com.github.prepushchecker.commitgen.SensitivePaths.isExcluded(rel)) continue;
            if (rules.inScope(rel)) rels.add(rel);
        }
        if (rels.isEmpty()) return List.of();
        // Gate what is pushed, not the checkout: "git push origin other-branch" pushes another commit.
        String rev = pushedHead == null ? null : ReviewGit.resolveCommit(root, pushedHead);
        if (rev == null) rev = "HEAD";
        String base = rangeBase == null ? null : ReviewGit.resolveCommit(root, rangeBase);
        Map<String, String> blobs = ReviewGit.blobsAt(root, rev, rels);
        rels.removeIf(r -> !blobs.containsKey(r));
        Path repo = root;
        boolean history = rels.size() <= MAX_REMOTE_LOOKUPS;
        rels.removeIf(r -> alreadyOnRemote(repo, r, blobs.get(r), base, history));
        if (rels.isEmpty()) return List.of();

        Classified c = classify(root, rels, blobs);
        ReviewGate.Decision decision = ReviewGate.decide(mode, mandatory, c.unreviewed, c.open);
        if (decision.action() == ReviewGate.Action.REVIEW_NOW) {
            RunResult r = review(pushTargets(root, decision.toReview(), base, rev), indicator, budget);
            c = classify(root, rels, blobs);
            decision = ReviewGate.afterPushReview(mandatory, c.unreviewed, c.open);
            if (decision.action() == ReviewGate.Action.ALLOW && r.findings() > 0) {
                notify("AI review found " + r.findings() + " issue(s) in the pushed files. Review is not "
                    + "mandatory, so the push went ahead.", NotificationType.WARNING, "Show Findings");
            }
            if (!r.errors().isEmpty() && decision.action() == ReviewGate.Action.BLOCK) {
                return withErrors(blockLines(root, decision), r.errors());
            }
            if (!r.errors().isEmpty()) {
                // Not mandatory, so the push goes on, but it must not look like a passed review.
                notify("AI review could not check every pushed file, so the push went ahead unreviewed: "
                    + AiOutputSanitizer.truncate(String.join("\n", r.errors()), 600), NotificationType.WARNING, null);
            }
        }
        if (decision.action() != ReviewGate.Action.BLOCK) return List.of();
        notifyBlocked(root, decision);
        return blockLines(root, decision);
    }

    static final int MAX_REMOTE_LOOKUPS = 200;

    /**
     * {@code true} when the pushed content of {@code rel} is already on the remote: unchanged from
     * the range base, or equal to a version in remote history (a revert of pushed code, or a merge
     * that brings in the remote's own changes). Pushing it adds no new code to review.
     */
    private static boolean alreadyOnRemote(@NotNull Path root, @NotNull String rel, @Nullable String blob,
                                           @Nullable String base, boolean searchHistory) {
        if (blob == null) return false;
        if (base != null && blob.equals(ReviewGit.blobsAt(root, base, List.of(rel)).get(rel))) return true;
        // One git log per file; skipped for very large pushes to keep the push fast.
        return searchHistory && ReviewGit.remoteBlobs(root, rel).contains(blob);
    }

    /** Settings that change the gate's verdict, for the push request key. */
    public @NotNull String gateStamp(@NotNull Path root) {
        ReviewRules rules = rules(root);
        if (!ReviewSettings.isEnabled(project) || rules == null) return "off";
        return ReviewSettings.mode(project) + "/" + ReviewSettings.isMandatory(project, rules) + "/"
            + rules.body().hashCode() + "/" + ReviewStateStore.getInstance(project).modificationCount();
    }

    private record Classified(List<String> unreviewed, List<String> open) {}

    private Classified classify(Path root, List<String> rels, Map<String, String> blobs) {
        ReviewStateStore store = ReviewStateStore.getInstance(project);
        List<String> unreviewed = new ArrayList<>();
        List<String> open = new ArrayList<>();
        for (String rel : rels) {
            String abs = key(ReviewGit.canonical(root).resolve(rel));
            String blob = blobs.get(rel);
            if (store.isApproved(abs, blob)) continue;
            ReviewStateStore.FileReview review = store.review(abs);
            if (review != null && review.contentId.equals(blob)) open.add(rel);
            else unreviewed.add(rel);
        }
        return new Classified(unreviewed, open);
    }

    private @NotNull List<String> blockLines(@NotNull Path root, @NotNull ReviewGate.Decision d) {
        List<String> out = new ArrayList<>();
        ReviewStateStore store = ReviewStateStore.getInstance(project);
        for (String rel : d.unreviewed()) {
            out.add("[" + display(root, rel) + "] AI review required before push: run Review in the Commit "
                + "tool window (or Code Review tab), then push again.");
        }
        for (String rel : d.open()) {
            ReviewStateStore.FileReview review = store.review(key(ReviewGit.canonical(root).resolve(rel)));
            if (review == null) continue;
            for (ReviewFinding f : review.findings) {
                if (!f.isOpen()) continue;
                String where = f.line > 0 ? display(root, rel) + " (" + f.line + ", 1)" : display(root, rel);
                out.add("[" + where + "] AI review: " + f.message);
            }
        }
        return out;
    }

    private static List<String> withErrors(List<String> lines, List<String> errors) {
        List<String> out = new ArrayList<>(lines);
        for (String e : errors) out.add("AI review could not complete: " + AiOutputSanitizer.truncate(e, 300));
        return out;
    }

    private void notifyBlocked(@NotNull Path root, @NotNull ReviewGate.Decision d) {
        if (d.unreviewed().isEmpty()) {
            notify("Push blocked: " + d.open().size() + " file(s) have unresolved AI review findings.",
                NotificationType.WARNING, "Show Findings");
            return;
        }
        Notification n = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("AI review required",
                "Push blocked: " + d.unreviewed().size() + " file(s) were not reviewed. Review is mandatory "
                    + "for this repository.", NotificationType.WARNING);
        List<String> rels = d.unreviewed();
        n.addAction(NotificationAction.createSimpleExpiring("Review Now", () -> {
            reviewCommittedInBackground(root, rels);
            showTab();
        }));
        n.notify(project);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    public @Nullable ReviewRules rules(@NotNull Path root) {
        return ReviewRules.load(root, ReviewSettings.ruleFilePath(project));
    }

    /** {@code files} plus the files git reports as changed in their repositories and the project's. */
    private @NotNull List<Path> withGitChanges(@NotNull List<Path> files) {
        Set<Path> roots = new LinkedHashSet<>(groupByRoot(files).keySet());
        String base = project.getBasePath();
        if (base != null) {
            Path baseRoot = ReviewGit.repoRoot(Path.of(base).toAbsolutePath().normalize());
            if (baseRoot != null) roots.add(baseRoot);
        }
        Set<Path> out = new LinkedHashSet<>();
        for (Path f : files) out.add(f.toAbsolutePath().normalize());
        for (Path root : roots) {
            for (String rel : ReviewGit.changedFiles(root)) out.add(root.resolve(rel).toAbsolutePath().normalize());
        }
        return new ArrayList<>(out);
    }

    private boolean hasAnyRules(Collection<Path> files) {
        for (Path root : groupByRoot(files).keySet()) if (rules(root) != null) return true;
        return false;
    }

    private static Map<Path, List<String>> groupByRoot(Collection<Path> files) {
        Map<Path, List<String>> out = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path f : files) {
            Path abs = f.toAbsolutePath().normalize();
            if (!seen.add(abs.toString())) continue;
            if (com.github.prepushchecker.commitgen.SensitivePaths.isExcluded(abs.toString())) continue;
            Path root = ReviewGit.repoRoot(abs);
            if (root == null) continue;
            out.computeIfAbsent(root, r -> new ArrayList<>()).add(ReviewGit.relative(root, abs));
        }
        return out;
    }

    private @NotNull String display(@NotNull Path root, @NotNull String rel) {
        String base = project.getBasePath();
        Path abs = root.resolve(rel).normalize();
        if (base != null) {
            Path b = Path.of(base).toAbsolutePath().normalize();
            if (abs.startsWith(b)) return b.relativize(abs).toString().replace('\\', '/');
        }
        return key(abs);
    }

    private static @Nullable String readSmallText(@NotNull Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_NEW_FILE_BYTES) return null;
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i < Math.min(bytes.length, 8000); i++) if (bytes[i] == 0) return null; // binary
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    void notify(@NotNull String text, @NotNull NotificationType type, @Nullable String showAction) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            Notification n = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("AI code review", text, type);
            if (showAction != null) n.addAction(NotificationAction.createSimpleExpiring(showAction, this::showTab));
            n.notify(project);
        });
    }

    /** Opens the Code Review tab of the tool window. */
    public void showTab() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            if (tw == null) return;
            tw.activate(() -> {
                Content content = tw.getContentManager().findContent(TAB_NAME);
                if (content != null) tw.getContentManager().setSelectedContent(content);
            });
        });
    }
}
