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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                            @NotNull List<String> errors, int unchanged) {
        public RunResult(int reviewed, int outOfScope, int findings, boolean noRules, @NotNull List<String> errors) {
            this(reviewed, outOfScope, findings, noRules, errors, 0);
        }

        static RunResult empty(boolean noRules) {
            return new RunResult(0, 0, 0, noRules, List.of());
        }
    }

    private final Project project;
    private final AtomicBoolean running = new AtomicBoolean();
    /** Replaced in tests. */
    volatile LargePushPrompt.Asker prompt = LargePushPrompt.dialog();

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
        Map<String, String> ids = ReviewGit.hashObjects(root, rels, true);
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
        List<String> merged = ReviewSettings.isIgnoreMergeChanges(project)
            ? ReviewGit.mergedRemoteParents(root, rev) : List.of();
        for (String rel : rels) {
            String blob = blobs.get(rel);
            if (blob == null) continue;
            String raw = from == null ? "" : ReviewGit.diffRange(root, from, rev, rel);
            boolean mergedIn = false;
            // A merged-in remote parent as base drops the hunks that merge brought in; the
            // smallest diff is the developer's own change (plus any conflict resolution).
            for (String parent : merged) {
                String own = ReviewGit.diffRange(root, parent, rev, rel);
                if (own.isBlank()) {
                    mergedIn = ReviewGit.blobsAt(root, parent, List.of(rel)).containsKey(rel);
                    if (mergedIn) break;
                    continue;
                }
                if (raw.isBlank() || own.length() < raw.length()) raw = own;
            }
            if (mergedIn) continue;
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
        int unchanged = 0;
        for (Target t : targets) {
            if (alreadyReviewed(t)) unchanged++;
            else byRoot.computeIfAbsent(t.root(), r -> new ArrayList<>()).add(t);
        }

        int reviewed = 0;
        int findings = 0;
        List<String> errors = new ArrayList<>();
        List<String> flagged = new ArrayList<>();
        for (Map.Entry<Path, List<Target>> e : byRoot.entrySet()) {
            ReviewRules rules = rules(e.getKey());
            if (rules == null) continue;
            Map<String, Target> byDisplay = new LinkedHashMap<>();
            Map<String, String> blobs = new LinkedHashMap<>();
            for (Target t : e.getValue()) blobs.put(t.relPath(), t.contentId());
            Map<String, String> fixedVersions = fixedVersions(e.getKey(), blobs.keySet(), blobs);
            Set<String> awaitingCommit = fixesAwaitingCommit(e.getKey(), fixedVersions);
            Set<String> trivial = approveTrivialChanges(e.getKey(), blobs.keySet(), blobs);
            unchanged += trivial.size();
            for (Target t : e.getValue()) {
                if (awaitingCommit.contains(t.relPath())) errors.add(commitFixMessage(e.getKey(), t.relPath()));
                else if (!trivial.contains(t.relPath())) byDisplay.put(t.relPath(), sinceApproved(t));
            }
            List<ReviewPrompt.FileDiff> diffs = new ArrayList<>();
            for (Target t : byDisplay.values()) {
                diffs.add(new ReviewPrompt.FileDiff(t.relPath(), t.numberedDiff(), store.resolvedNotes(t.absolutePath())));
            }

            List<List<ReviewPrompt.FileDiff>> batches = ReviewPrompt.batches(diffs);
            List<Object> answers = askAll(batches, rules, deadline, indicator);
            for (int i = 0; i < batches.size(); i++) {
                List<ReviewPrompt.FileDiff> batch = batches.get(i);
                Object got = answers.get(i);
                if (got instanceof String error) {
                    errors.add(error);
                    continue;
                }
                Map<?, ?> answer = (Map<?, ?>) got;
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
                    synchronized (store) {
                        // A concurrent review may have finished and the user may already have resolved it.
                        if (alreadyReviewed(t) || !Objects.equals(fixedVersions.get(t.relPath()),
                                store.fixedVersionOf(t.absolutePath(), t.contentId()))) {
                            unchanged++;
                            continue;
                        }
                        store.record(t.absolutePath(), t.contentId(), mine);
                    }
                    reviewed++;
                    findings += mine.size();
                    if (!mine.isEmpty()) flagged.add(t.absolutePath());
                }
            }
        }
        // Prepare fixes while the developer reads the findings; capped and cancelled on any edit.
        if (!flagged.isEmpty()) FixPrefetchService.getInstance(project).prefetch(flagged);
        return new RunResult(reviewed, 0, findings, false, errors, unchanged);
    }

    /** AI review calls in flight at once for one review run; batches beyond this wait. */
    static final int PARALLEL_BATCHES = 2;

    /**
     * Sends every batch to the AI, {@link #PARALLEL_BATCHES} at a time, all within the deadline.
     * Per batch: the parsed answer ({@code Map}, or {@code null} when unreadable) or an error
     * message ({@code String}), in batch order.
     */
    private @NotNull List<Object> askAll(@NotNull List<List<ReviewPrompt.FileDiff>> batches, @NotNull ReviewRules rules,
                                         long deadline, @Nullable ProgressIndicator indicator) {
        Object[] out = new Object[batches.size()];
        if (batches.isEmpty()) return List.of();
        if (indicator != null) {
            indicator.checkCanceled();
            indicator.setText("AI review: " + (batches.size() > 1 ? batches.size() + " parts"
                : batches.get(0).size() + " file(s)"));
        }
        java.util.concurrent.ExecutorService pool = batches.size() == 1 ? null
            : com.intellij.util.concurrency.AppExecutorUtil.createBoundedApplicationPoolExecutor(
                "PrePushChecker-AiReview", PARALLEL_BATCHES);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        // The caller's lane (e.g. a background pre-review) must carry over to the worker threads.
        boolean background = com.github.prepushchecker.commitgen.cli.AiCallScheduler.isBackgroundThread();
        try {
            for (int i = 0; i < batches.size(); i++) {
                int index = i;
                Runnable ask = () -> {
                    if (!background) {
                        out[index] = askOne(batches.get(index), rules, deadline);
                        return;
                    }
                    try {
                        out[index] = com.github.prepushchecker.commitgen.cli.AiCallScheduler.inBackground(
                            () -> askOne(batches.get(index), rules, deadline));
                    } catch (ProcessCanceledException pce) {
                        throw pce;
                    } catch (Exception ex) {
                        out[index] = String.valueOf(ex.getMessage());
                    }
                };
                if (pool == null) {
                    ask.run();
                    continue;
                }
                futures.add(pool.submit(() -> {
                    if (indicator == null) ask.run();
                    else ProgressManager.getInstance().executeProcessUnderProgress(ask, indicator);
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                while (true) {
                    if (indicator != null) indicator.checkCanceled();
                    try {
                        futures.get(i).get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                        break;
                    } catch (java.util.concurrent.TimeoutException ignored) {
                        // poll for cancel
                    } catch (java.util.concurrent.ExecutionException ex) {
                        if (ex.getCause() instanceof ProcessCanceledException pce) throw pce;
                        out[i] = String.valueOf(ex.getCause());
                        break;
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new ProcessCanceledException();
                    }
                }
            }
        } finally {
            if (pool != null) {
                if (indicator != null && indicator.isCanceled()) futures.forEach(f -> f.cancel(true));
                pool.shutdown();
            }
        }
        return java.util.Arrays.asList(out);
    }

    private @Nullable Object askOne(@NotNull List<ReviewPrompt.FileDiff> batch, @NotNull ReviewRules rules, long deadline) {
        AiRequest request = AiRequest.review(ReviewPrompt.reviewSystemPrompt(rules.body()),
            ReviewPrompt.reviewUserPrompt(batch));
        long left = deadline - System.nanoTime();
        if (left <= Duration.ofSeconds(10).toNanos()) return "Not enough time left to review " + batch.size() + " file(s).";
        if (left < request.timeout().toNanos()) request = request.withTimeout(Duration.ofNanos(left));
        try {
            String raw = CommitMessageGeneratorService.resolveProvider().generate(request);
            if (LOG.isDebugEnabled()) LOG.debug("AI review answer: " + AiOutputSanitizer.truncate(raw, 4000));
            return AiOutputSanitizer.cleanStructured(raw, "findings");
        } catch (ProcessCanceledException pce) {
            throw pce;
        } catch (Exception ex) {
            LOG.info("AI review failed", ex);
            return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        }
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
                report(new RunResult(r.reviewed(), outOfScope, r.findings(), false, errors, r.unchanged()), copy.size());
            }

            @Override
            public void onFinished() {
                running.set(false);
                ReviewStateStore.getInstance(project).refresh();
                if (onDone != null) ApplicationManager.getApplication().invokeLater(onDone);
            }
        });
    }

    /** Reviews the committed (pushed) content of {@code files}, as the push saw it. */
    /** Delay after a commit before the pre-review starts; a quick follow-up commit restarts it. */
    static final long PRE_REVIEW_DELAY_MILLIS = 3_000;
    private final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> preReviewTimer =
        new java.util.concurrent.atomic.AtomicReference<>();
    private volatile java.util.concurrent.CompletableFuture<Void> preReview =
        java.util.concurrent.CompletableFuture.completedFuture(null);

    /**
     * On-push mode: reviews just-committed files in the background, so the push that follows finds
     * the results recorded (by content id) instead of waiting for the AI. Skipped for pushes large
     * enough to ask the user first, and when a review is already running.
     */
    public void preReviewAfterCommit(@NotNull Collection<Path> committed) {
        if (!ReviewSettings.isEnabled(project) || ReviewSettings.mode(project) != ReviewSettings.Mode.ON_PUSH
                || committed.isEmpty()) return;
        List<Path> files = List.copyOf(committed);
        java.util.concurrent.Future<?> next = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
            .schedule(() -> startPreReview(files), PRE_REVIEW_DELAY_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        java.util.concurrent.Future<?> previous = preReviewTimer.getAndSet(next);
        if (previous != null) previous.cancel(false);
    }

    private void startPreReview(@NotNull List<Path> files) {
        if (project.isDisposed() || !running.compareAndSet(false, true)) return;
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        preReview = done;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                running.set(false);
                done.complete(null);
                return;
            }
            ProgressManager.getInstance().run(new Task.Backgroundable(project, "AI pre-review of committed changes", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    int threshold = ReviewSettings.confirmFileThreshold(project);
                    List<Target> targets = new ArrayList<>();
                    for (Map.Entry<Path, List<String>> e : groupByRoot(files).entrySet()) {
                        ReviewRules rules = rules(e.getKey());
                        if (rules == null) continue;
                        List<String> rels = e.getValue().stream()
                            .filter(r -> !com.github.prepushchecker.commitgen.SensitivePaths.isExcluded(r))
                            .filter(rules::inScope).toList();
                        if (rels.isEmpty()) continue;
                        targets.addAll(pushTargets(e.getKey(), rels, null));
                    }
                    targets.removeIf(ReviewService.this::alreadyReviewed);
                    if (targets.isEmpty() || (threshold > 0 && targets.size() > threshold)) return;
                    RunResult r;
                    try {
                        r = com.github.prepushchecker.commitgen.cli.AiCallScheduler.inBackground(
                            () -> review(targets, indicator, null));
                    } catch (ProcessCanceledException pce) {
                        throw pce;
                    } catch (Exception ex) {
                        LOG.info("AI pre-review did not finish", ex);
                        return;
                    }
                    if (r.findings() > 0) {
                        ReviewService.this.notify("AI pre-review found " + r.findings() + " issue(s) in your last commit. Fix them now to "
                            + "keep the push fast.", NotificationType.WARNING, "Show Findings");
                    }
                }

                @Override
                public void onFinished() {
                    running.set(false);
                    done.complete(null);
                    ReviewStateStore.getInstance(project).refresh();
                }
            });
        }, com.intellij.openapi.application.ModalityState.nonModal());
    }

    /** Waits (cancellably, up to {@code max}) for a pre-review in flight, so a push does not ask the AI twice. */
    private void awaitPreReview(@Nullable ProgressIndicator indicator, @NotNull Duration max) {
        java.util.concurrent.CompletableFuture<Void> f = preReview;
        long until = System.nanoTime() + max.toNanos();
        while (!f.isDone() && System.nanoTime() < until) {
            if (indicator != null) indicator.checkCanceled();
            try {
                f.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
                // poll
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                return;
            }
        }
    }

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
                ReviewStateStore.getInstance(project).refresh();
            }
        });
    }

    /** {@code true} when this exact content already has a recorded review (clean, approved or with findings). */
    private boolean alreadyReviewed(@NotNull Target t) {
        ReviewStateStore store = ReviewStateStore.getInstance(project);
        if (store.isApproved(t.absolutePath(), t.contentId())) return true;
        return store.hasOpenReview(t.absolutePath(), t.contentId());
    }

    private void report(@NotNull RunResult r, int requested) {
        int unchanged = r.unchanged();
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
        if (r.reviewed() == 0 && unchanged > 0 && r.errors().isEmpty()) {
            notify(unchanged + " file(s) unchanged since their last review; results kept, nothing new to review. "
                + "Use Clear Reviews to review them again.", NotificationType.INFORMATION, "Show Findings");
            return;
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
        if (unchanged > 0) text += " " + unchanged + " unchanged file(s) kept their earlier results.";
        notify(text, r.findings() == 0 ? NotificationType.INFORMATION : NotificationType.WARNING,
            r.findings() == 0 ? null : "Show Findings");
    }

    // ── Push gate ────────────────────────────────────────────────────────────

    public record PushReview(List<String> lines, @Nullable String firstBlockingFindingId) {
        public static final PushReview CLEAR = new PushReview(List.of(), null);
    }

    /**
     * Push check for one repository. Returns blocking error lines (empty: allow).
     *
     * @param pushed     absolute paths the hook reported
     * @param rangeBase  base of the pushed range, when the hook sent it
     * @param pushedHead the pushed commit, when the hook sent it; may differ from HEAD
     * @param budget     time the push may spend on an on-push review
     */
    public @NotNull PushReview checkPush(@NotNull Path root, @NotNull Collection<Path> pushed,
                                           @Nullable String rangeBase, @Nullable String pushedHead,
                                           @Nullable ProgressIndicator indicator, @NotNull Duration budget) {
        if (!ReviewSettings.isEnabled(project)) return PushReview.CLEAR;
        ReviewRules rules = rules(root);
        if (rules == null) return PushReview.CLEAR;
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
        if (rels.isEmpty()) return PushReview.CLEAR;
        // Gate what is pushed, not the checkout: "git push origin other-branch" pushes another commit.
        String rev = pushedHead == null ? null : ReviewGit.resolveCommit(root, pushedHead);
        if (rev == null) rev = "HEAD";
        String base = rangeBase == null ? null : ReviewGit.resolveCommit(root, rangeBase);
        Map<String, String> blobs = ReviewGit.blobsAt(root, rev, rels);
        rels.removeIf(r -> !blobs.containsKey(r));
        Path repo = root;
        boolean history = rels.size() <= MAX_REMOTE_LOOKUPS;
        rels.removeIf(r -> alreadyOnRemote(repo, r, blobs.get(r), base, history));
        boolean merge = ReviewGit.hasUnpushedMerge(root, rev);
        boolean mergeNarrowed = false;
        if (!rels.isEmpty() && ReviewSettings.isIgnoreMergeChanges(project)) {
            rels.removeAll(mergedInOnly(root, rev, rels, blobs));
            if (merge) {
                // Files only a merge brought in (any parent, pushed or not) are not the developer's code.
                Set<String> own = ReviewGit.ownChangedFiles(root, rev);
                if (own != null) {
                    rels.retainAll(own);
                    mergeNarrowed = true;
                }
            }
        }
        if (base != null) {
            String from = base;
            String to = rev;
            rels.removeIf(r -> isWhitespaceInsensitive(r) && ReviewGit.whitespaceOnly(repo, from, to, r));
        }
        if (rels.isEmpty()) return PushReview.CLEAR;

        // A pushed version whose findings were all resolved by a later (uncommitted) fix is not new
        // code: never review it again, whatever the working tree holds now.
        Map<String, String> fixed = fixedVersions(repo, rels, blobs);
        if (mandatory && !fixed.isEmpty()) {
            Set<String> onDisk = fixesAwaitingCommit(repo, fixed);
            return new PushReview(fixed.keySet().stream()
                .map(rel -> onDisk.contains(rel) ? commitFixMessage(repo, rel) : commitLaterFixMessage(repo, rel))
                .toList(), null);
        }
        rels.removeAll(fixed.keySet());
        rels.removeAll(approveTrivialChanges(repo, rels, blobs));
        if (rels.isEmpty()) return PushReview.CLEAR;

        Classified c = classify(root, rels, blobs);
        if (!c.unreviewed.isEmpty() && !preReview.isDone()) {
            // The commit's pre-review is still running: its results are what this push needs.
            awaitPreReview(indicator, budget.dividedBy(2));
            c = classify(root, rels, blobs);
        }
        ReviewGate.Decision decision = ReviewGate.decide(mode, mandatory, c.unreviewed, c.open);
        if (decision.action() == ReviewGate.Action.REVIEW_NOW) {
            int threshold = ReviewSettings.confirmFileThreshold(project);
            // Merge question only when the user chose to ignore merges but the merged-in code could not be told apart.
            boolean mergeUnsure = merge && ReviewSettings.isIgnoreMergeChanges(project) && !mergeNarrowed;
            if ((threshold > 0 && decision.toReview().size() > threshold) || mergeUnsure) {
                LargePushPrompt.Answer answer = prompt.ask(project, decision.toReview(), mergeUnsure,
                    ReviewSettings.confirmTimeoutSeconds(project));
                if (answer == LargePushPrompt.Answer.TIMEOUT) {
                    answer = switch (ReviewSettings.confirmTimeoutAction(project)) {
                        case SKIP -> LargePushPrompt.Answer.SKIP;
                        case REVIEW -> LargePushPrompt.Answer.REVIEW;
                        case ABORT -> LargePushPrompt.Answer.TIMEOUT;
                    };
                }
                if (answer == LargePushPrompt.Answer.SKIP) {
                    notifySkipped(root, decision.toReview(), merge);
                    return PushReview.CLEAR;
                }
                if (answer != LargePushPrompt.Answer.REVIEW) {
                    return new PushReview(List.of("Push aborted: the AI review of " + decision.toReview().size()
                        + " file(s) was not confirmed. Push again and choose Review Now or Skip AI Review in IntelliJ "
                        + "(Settings > Tools > Pre-Push Checker > Code Review sets the question and its timeout)."), null);
                }
            }
            RunResult r = review(pushTargets(root, decision.toReview(), base, rev), indicator, budget);
            c = classify(root, rels, blobs);
            decision = ReviewGate.afterPushReview(mandatory, c.unreviewed, c.open);
            if (decision.action() == ReviewGate.Action.ALLOW && r.findings() > 0) {
                notify("AI review found " + r.findings() + " issue(s) in the pushed files. Review is not "
                    + "mandatory, so the push went ahead.", NotificationType.WARNING, "Show Findings");
            }
            if (!r.errors().isEmpty() && decision.action() == ReviewGate.Action.BLOCK) {
                return blockedReview(root, decision, r.errors());
            }
            if (!r.errors().isEmpty()) {
                // Not mandatory, so the push goes on, but it must not look like a passed review.
                notify("AI review could not check every pushed file, so the push went ahead unreviewed: "
                    + AiOutputSanitizer.truncate(String.join("\n", r.errors()), 600), NotificationType.WARNING, null);
            }
        }
        if (decision.action() != ReviewGate.Action.BLOCK) return PushReview.CLEAR;
        notifyBlocked(root, decision);
        return blockedReview(root, decision, List.of());
    }

    static final int MAX_REMOTE_LOOKUPS = 200;

    /**
     * Files whose pushed content equals their content in a remote commit merged into the push:
     * a {@code git merge main} brought them in unchanged, so there is no code of the developer's
     * own to review.
     */
    static @NotNull Set<String> mergedInOnly(@NotNull Path root, @NotNull String rev, @NotNull List<String> rels,
                                             @NotNull Map<String, String> blobs) {
        Set<String> out = new HashSet<>();
        for (String parent : ReviewGit.mergedRemoteParents(root, rev)) {
            Map<String, String> there = ReviewGit.blobsAt(root, parent, rels);
            for (String r : rels) {
                String b = blobs.get(r);
                if (b != null && b.equals(there.get(r))) out.add(r);
            }
        }
        return out;
    }

    /** Languages where a whitespace-only change cannot change behaviour (not YAML, Python, Makefiles…). */
    private static final Set<String> WHITESPACE_INSENSITIVE = Set.of(
        "java", "kt", "kts", "groovy", "gradle", "scala", "js", "jsx", "ts", "tsx", "mjs", "cjs",
        "css", "scss", "less", "json", "c", "h", "cc", "cpp", "hpp", "cs", "go", "rs", "swift", "php", "sql");

    static boolean isWhitespaceInsensitive(@NotNull String rel) {
        int dot = rel.lastIndexOf('.');
        return dot > rel.lastIndexOf('/') && WHITESPACE_INSENSITIVE.contains(rel.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

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
            + rules.body().hashCode() + "/" + ReviewSettings.isIgnoreMergeChanges(project) + "/" + ReviewSettings.confirmFileThreshold(project) + "/" + ReviewStateStore.getInstance(project).modificationCount();
    }

    private Map<String, String> fixedVersions(Path root, Collection<String> rels, Map<String, String> blobs) {
        ReviewStateStore store = ReviewStateStore.getInstance(project);
        Map<String, String> fixed = new LinkedHashMap<>();
        Path canonicalRoot = ReviewGit.canonical(root);
        for (String rel : rels) {
            String id = store.fixedVersionOf(key(canonicalRoot.resolve(rel)), blobs.get(rel));
            if (id != null) fixed.put(rel, id);
        }
        return fixed;
    }

    /** Only suggest committing a fix when the approved version is still on disk. */
    private static Set<String> fixesAwaitingCommit(Path root, Map<String, String> fixed) {
        Map<String, String> working = ReviewGit.hashObjects(root, new ArrayList<>(fixed.keySet()));
        Set<String> pending = new LinkedHashSet<>();
        fixed.forEach((rel, id) -> {
            if (id.equals(working.get(rel))) pending.add(rel);
        });
        return pending;
    }

    private String commitLaterFixMessage(Path root, String rel) {
        return "[" + display(root, rel) + "] Commit the resolved fix before pushing. "
            + "The commit being pushed still contains the version the AI review flagged.";
    }

    /** Languages where an import-only change cannot add a rule violation worth an AI call. */
    private static final Set<String> IMPORT_ONLY_TRIVIAL = Set.of("java", "kt", "kts", "groovy", "scala");
    private static final java.util.regex.Pattern IMPORT_LINE =
        java.util.regex.Pattern.compile("^import\\s+[\\w.*$`\\s]+;?$");

    /**
     * Approves files whose new version differs from their last approved version only by
     * whitespace (where layout is not significant) or imports: e.g. a fix that was reformatted or
     * had imports optimized on commit. Saves an AI call and stops a fixed file being re-reviewed.
     *
     * @return the files approved this way
     */
    @NotNull Set<String> approveTrivialChanges(@NotNull Path root, @NotNull Collection<String> rels,
                                               @NotNull Map<String, String> blobs) {
        ReviewStateStore store = ReviewStateStore.getInstance(project);
        Path canonicalRoot = ReviewGit.canonical(root);
        Set<String> out = new LinkedHashSet<>();
        for (String rel : rels) {
            String blob = blobs.get(rel);
            if (blob == null) continue;
            String abs = key(canonicalRoot.resolve(rel));
            if (store.isApproved(abs, blob) || store.hasOpenReview(abs, blob)) continue;
            ReviewStateStore.FileReview current = store.review(abs);
            // Never drop open findings of another version: only build on a clean review.
            if (current == null || current.hasOpenFindings()) continue;
            String approved = store.lastApprovedId(abs);
            if (approved == null || approved.equals(blob)) continue;
            if (!isTrivialChange(root, rel, approved, blob)) continue;
            store.record(abs, blob, List.of());
            LOG.info("AI review: " + rel + " differs from its approved version only by whitespace or imports");
            out.add(rel);
        }
        return out;
    }

    private static boolean isTrivialChange(Path root, String rel, String from, String to) {
        boolean layoutFree = isWhitespaceInsensitive(rel);
        String ext = rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        boolean importsFree = IMPORT_ONLY_TRIVIAL.contains(ext);
        if (!layoutFree && !importsFree) return false;
        if (layoutFree) {
            String a = ReviewGit.catBlob(root, from);
            String b = ReviewGit.catBlob(root, to);
            if (a == null || b == null) return false; // a blob is not in the object database: cannot tell
            return normalizedCode(a, importsFree).equals(normalizedCode(b, importsFree));
        }
        String diff = ReviewGit.diffBlobs(root, from, to, false);
        return diff != null && onlyImportChanges(diff);
    }

    /**
     * Code with layout removed: whitespace next to punctuation dropped and other runs collapsed,
     * so a reformat (re-wrapped lines, re-indented blocks) compares equal while a change of words
     * does not. With {@code dropImports} import lines are left out too.
     */
    static @NotNull String normalizedCode(@NotNull String code, boolean dropImports) {
        StringBuilder kept = new StringBuilder();
        for (String line : code.replace("\r\n", "\n").split("\n")) {
            if (dropImports && IMPORT_LINE.matcher(line.strip()).matches()) continue;
            kept.append(line).append('\n');
        }
        return kept.toString().replaceAll("\\s+", " ").replaceAll(" ?([^\\w\\s]) ?", "$1").strip();
    }

    static boolean onlyImportChanges(@NotNull String unifiedDiff) {
        boolean inHunk = false;
        boolean any = false;
        for (String line : unifiedDiff.replace("\r\n", "\n").split("\n")) {
            if (line.startsWith("@@")) {
                inHunk = true;
                continue;
            }
            if (!inHunk || line.isEmpty() || line.startsWith("\\")) continue;
            char c = line.charAt(0);
            if (c != '+' && c != '-') continue;
            String body = line.substring(1).strip();
            if (body.isEmpty()) continue;
            if (!IMPORT_LINE.matcher(body).matches()) return false;
            any = true;
        }
        return any;
    }

    /**
     * {@code t} with its diff taken from the file's last approved version when that is smaller:
     * the approved code was already reviewed, so only what changed since needs the AI.
     */
    private @NotNull Target sinceApproved(@NotNull Target t) {
        String approved = ReviewStateStore.getInstance(project).lastApprovedId(t.absolutePath());
        if (approved == null || approved.equals(t.contentId())) return t;
        String raw = ReviewGit.diffBlobs(t.root(), approved, t.contentId(), false);
        if (raw == null || raw.isBlank()) return t;
        String numbered = ReviewPrompt.numberDiff(raw);
        if (numbered.isBlank() || numbered.length() >= t.numberedDiff().length()) return t;
        return new Target(t.root(), t.relPath(), t.contentId(), numbered);
    }

    private String commitFixMessage(Path root, String rel) {
        return "[" + display(root, rel) + "] Commit the resolved fix before pushing. "
            + "The commit being pushed still contains the earlier version.";
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
            if (store.hasOpenReview(abs, blob)) open.add(rel);
            else unreviewed.add(rel);
        }
        return new Classified(unreviewed, open);
    }

    private @NotNull PushReview blockedReview(@NotNull Path root, @NotNull ReviewGate.Decision d, List<String> errors) {
        List<String> out = new ArrayList<>();
        ReviewStateStore store = ReviewStateStore.getInstance(project);
        for (String rel : d.unreviewed()) {
            out.add("[" + display(root, rel) + "] AI review required before push: run Review in the Commit "
                + "tool window (or Code Review tab), then push again.");
        }
        String firstId = null;
        for (String rel : d.open()) {
            ReviewStateStore.FileReview review = store.review(key(ReviewGit.canonical(root).resolve(rel)));
            if (review == null) continue;
            for (ReviewFinding f : review.findings) {
                if (!f.isOpen()) continue;
                if (firstId == null) firstId = f.id;
                String where = f.line > 0 ? display(root, rel) + " (" + f.line + ", 1)" : display(root, rel);
                out.add("[" + where + "] AI review: " + f.message);
            }
        }
        return new PushReview(withErrors(out, errors), firstId);
    }

    private static List<String> withErrors(List<String> lines, List<String> errors) {
        List<String> out = new ArrayList<>(lines);
        for (String e : errors) out.add("AI review could not complete: " + AiOutputSanitizer.truncate(e, 300));
        return out;
    }

    private void notifySkipped(@NotNull Path root, @NotNull List<String> rels, boolean merge) {
        Notification n = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("AI review skipped",
                "AI review was skipped for " + rels.size() + " file(s) because the push is "
                    + (merge ? "a merge push that could not be told apart from your own changes"
                              : "larger than the confirmation limit")
                    + ". The push went ahead.", NotificationType.WARNING);
        n.addAction(NotificationAction.createSimpleExpiring("Review Now", () -> {
            reviewCommittedInBackground(root, rels);
            showTab();
        }));
        n.notify(project);
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

    // ── Stale results ────────────────────────────────────────────────────────

    /**
     * Removes review results for files of {@code root} that no longer have anything unpushed or
     * uncommitted: pushed (with or without the gate), reverted, or reset. The Code Review tab
     * then only lists what can still matter. Skipped while a review or push check runs.
     */
    public boolean pruneStale(@NotNull Path root) {
        ReviewStateStore store = ReviewStateStore.getInstance(project);
        if (!store.hasOpenFindings()) return true;
        if (running.get()) return false;
        long generation = store.modificationCount();
        Path canonical = ReviewGit.canonical(root);
        Set<String> pending = ReviewGit.pendingFiles(canonical);
        if (pending == null) return true; // Keep findings until Git can confirm what is pending.
        String prefix = key(canonical) + "/";
        for (ReviewStateStore.FileReview file : store.reviews()) {
            if (file.hasOpenFindings() && file.path.startsWith(prefix)
                    && !canonical.equals(ReviewGit.repoRoot(Path.of(file.path)))) {
                pending.add(file.path.substring(prefix.length()));
            }
        }
        int removed;
        synchronized (store) {
            if (running.get() || store.modificationCount() != generation) return false;
            removed = store.retainOnly(prefix, pending);
        }
        if (removed > 0) LOG.info("Cleared " + removed + " review result(s) with nothing left to push in " + canonical);
        return true;
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
        showTab(null);
    }

    public void showTab(@Nullable String findingId) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            if (tw == null) return;
            tw.activate(() -> {
                Content content = tw.getContentManager().findContent(TAB_NAME);
                if (content == null) return;
                tw.getContentManager().setSelectedContent(content);
                if (findingId != null && content.getComponent() instanceof ReviewPanel panel) panel.select(findingId);
            });
        });
    }
}
