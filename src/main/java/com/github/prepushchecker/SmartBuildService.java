package com.github.prepushchecker;

import com.intellij.compiler.server.BuildManagerListener;
import com.intellij.ide.PowerSaveMode;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.compiler.CompilationStatusListener;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.compiler.CompilerTopics;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerManagerListener;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps a compile verdict ready for the next push without compiling on a timer.
 *
 * <p>Source edits only bump a counter. Work happens when the user has been idle long
 * enough (or paused briefly after a long editing stretch): the working-tree
 * {@link BuildFingerprint} is looked up in {@link SmartBuildSnapshotStore} first, and only
 * a miss starts an incremental {@code make}. Every project-wide IDE build — the user's own,
 * automake, push checks — is recorded too, so the plugin never repeats work the IDE already did.
 * Nothing runs while debugging, indexing, in power-save mode, or during another build.
 */
@Service(Service.Level.PROJECT)
public final class SmartBuildService implements Disposable {
    private static final Logger LOG = Logger.getInstance(SmartBuildService.class);

    private static final long REPO_CHANGE_DEBOUNCE_MS = 1_500L;
    private static final long POST_BUILD_RECHECK_MS = 2_000L;
    private static final Set<String> BUSY_GIT_STATES = Set.of(
        "MERGING", "REBASING", "GRAFTING", "REVERTING");

    enum Phase { DISABLED, IDLE, UP_TO_DATE, STALE, WAITING, BUILDING }

    record Status(@NotNull Phase phase, @NotNull String branch, @NotNull String detail) {
    }

    private final Project project;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean evaluating = new AtomicBoolean();
    private final AtomicBoolean building = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong(1);
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Object scheduleLock = new Object();

    private volatile long verifiedGeneration;
    private volatile long staleSince;
    private volatile long lastActivityAt = System.currentTimeMillis();
    private volatile long lastSmartBuildAt;
    private volatile long generationAtBuildStart = -1L;
    private volatile boolean clearErrorsOnMiss;
    private volatile boolean pruneRequested;
    private volatile boolean lookupRequested;
    private volatile boolean disposed;
    private volatile Status status = new Status(Phase.IDLE, "", "");

    /** Fingerprint cache: avoids re-running git while nothing has changed. */
    private volatile long fingerprintGeneration = -1L;
    private volatile @Nullable BuildFingerprint.Result fingerprint;

    private @Nullable ScheduledFuture<?> scheduled;
    private long scheduledAt = Long.MAX_VALUE;
    private @Nullable AWTEventListener activityListener;

    public SmartBuildService(@NotNull Project project) {
        this.project = project;
    }

    static @NotNull SmartBuildService getInstance(@NotNull Project project) {
        return project.getService(SmartBuildService.class);
    }

    public static void runStartup(@NotNull Project project) {
        SmartBuildService service = project.getService(SmartBuildService.class);
        if (service != null) service.start();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    void start() {
        if (!started.compareAndSet(false, true) || project.isDisposed()) return;

        var bus = project.getMessageBus().connect(this);
        bus.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    if (isCompileRelevant(event.getPath())) {
                        sourcesChanged();
                        return;
                    }
                }
            }
        });
        bus.subscribe(CompilerTopics.COMPILATION_STATUS, new CompilationStatusListener() {
            @Override
            public void compilationFinished(boolean aborted, int errors, int warnings,
                                            @NotNull CompileContext context) {
                onBuildFinished(aborted, errors, context, false);
            }

            @Override
            public void automakeCompilationFinished(int errors, int warnings,
                                                    @NotNull CompileContext context) {
                onBuildFinished(false, errors, context, true);
            }
        });
        bus.subscribe(XDebuggerManager.TOPIC, new XDebuggerManagerListener() {
            @Override
            public void processStopped(@NotNull XDebugProcess debugProcess) {
                if (needsCheck()) schedule(idleMillis());
            }
        });
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(
            BuildManagerListener.TOPIC,
            new BuildManagerListener() {
                @Override
                public void buildStarted(@NotNull Project buildProject, @NotNull UUID sessionId,
                                         boolean isAutomake) {
                    if (buildProject == project) generationAtBuildStart = generation.get();
                }
            });

        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                VirtualFile file = FileDocumentManager.getInstance().getFile(event.getDocument());
                if (file != null && isCompileRelevant(file.getPath())) sourcesChanged();
            }
        }, this);

        // Only stores a timestamp: the cheapest possible "is the user busy" signal.
        AWTEventListener listener = event -> lastActivityAt = System.currentTimeMillis();
        activityListener = listener;
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,
            AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK);

        ProjectLevelVcsManager.getInstance(project).runAfterInitialization(() -> {
            pruneRequested = true;
            lookupRequested = true;
            schedule(REPO_CHANGE_DEBOUNCE_MS);
        });
        publish(new Status(isEnabled() ? Phase.IDLE : Phase.DISABLED, "", ""));
    }

    @Override
    public void dispose() {
        disposed = true;
        AWTEventListener listener = activityListener;
        if (listener != null) Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        synchronized (scheduleLock) {
            if (scheduled != null) scheduled.cancel(false);
            scheduled = null;
        }
        listeners.clear();
    }

    // ── Signals ──────────────────────────────────────────────────────────────

    /** Cheap: called on every keystroke in a source file and on VFS events. */
    private void sourcesChanged() {
        generation.incrementAndGet();
        if (staleSince == 0L) staleSince = System.currentTimeMillis();
        if (status.phase() == Phase.UP_TO_DATE) {
            publish(new Status(Phase.STALE, status.branch(), "sources changed"));
        }
        if (isEnabled()) schedule(idleMillis());
    }

    /** Called from {@link RepositoryStateMonitor} on any Git repository change. */
    void repositoryChanged() {
        if (!started.get()) start();
        generation.incrementAndGet();
        clearErrorsOnMiss = true;
        pruneRequested = true;
        lookupRequested = true;
        schedule(REPO_CHANGE_DEBOUNCE_MS);
    }

    /** Setting toggled in the tool window. */
    void settingChanged() {
        if (isEnabled()) {
            if (needsCheck()) schedule(idleMillis());
            publish(new Status(needsCheck() ? Phase.STALE : Phase.UP_TO_DATE, status.branch(), ""));
        } else {
            synchronized (scheduleLock) {
                if (scheduled != null) scheduled.cancel(false);
                scheduled = null;
                scheduledAt = Long.MAX_VALUE;
            }
            publish(new Status(Phase.DISABLED, status.branch(), ""));
        }
    }

    // ── Scheduling ───────────────────────────────────────────────────────────

    /** Arms one timer. An earlier pending timer wins; it re-plans when it fires. */
    private void schedule(long delayMs) {
        if (disposed || project.isDisposed()) return;
        long at = System.currentTimeMillis() + Math.max(0L, delayMs);
        synchronized (scheduleLock) {
            if (scheduled != null && !scheduled.isDone() && scheduledAt <= at) return;
            if (scheduled != null) scheduled.cancel(false);
            scheduledAt = at;
            scheduled = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                () -> ApplicationManager.getApplication().executeOnPooledThread(this::evaluate),
                Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        }
    }

    private void evaluate() {
        if (disposed || project.isDisposed()) return;
        if (!evaluating.compareAndSet(false, true)) {
            schedule(POST_BUILD_RECHECK_MS);
            return;
        }
        try {
            evaluateLocked();
        } catch (Throwable failure) {
            LOG.warn("Smart build evaluation failed", failure);
        } finally {
            evaluating.set(false);
        }
    }

    private void evaluateLocked() {
        if (pruneRequested) {
            pruneRequested = false;
            pruneDeletedBranches();
        }
        if (building.get()) return; // the build's completion re-evaluates
        long gen = generation.get();
        if (gen == verifiedGeneration) {
            publish(new Status(isEnabled() ? Phase.UP_TO_DATE : Phase.DISABLED, status.branch(), ""));
            return;
        }

        boolean enabled = isEnabled();
        long now = System.currentTimeMillis();
        boolean lookupNow = lookupRequested;
        lookupRequested = false;
        // While the user is still busy, don't even run git: re-plan from the policy alone.
        // Repository changes (branch switch, commit) always look up, to restore verdicts at once.
        if (enabled && !lookupNow && planLater(now, status.branch())) return;

        // When the fingerprint for this generation already missed, skip git entirely.
        if (fingerprintGeneration != gen) {
            BuildFingerprint.Result fp = BuildFingerprint.compute(project);
            fingerprint = fp;
            fingerprintGeneration = gen;
            if (fp != null) {
                SmartBuildSnapshotStore.Entry hit =
                    SmartBuildSnapshotStore.getInstance(project).find(fp.fingerprint(), now);
                if (hit != null && generation.get() == gen) {
                    markVerified(gen, fp, hit.errorList(), true);
                    return;
                }
            }
            if (clearErrorsOnMiss) {
                clearErrorsOnMiss = false;
                CompilationErrorService.getInstance(project).clearErrors();
            }
        }
        String branch = fingerprint == null ? status.branch() : fingerprint.branchLabel();

        if (!enabled) {
            publish(new Status(Phase.DISABLED, branch, ""));
            return;
        }

        if (!planLater(now, branch)) {
            startBuild(branch, SmartBuildPolicy.decide(inputs(now, true)).reason());
        }
    }

    /** {@code true} when no build may start now (status published, timer re-armed). */
    private boolean planLater(long now, @NotNull String branch) {
        SmartBuildPolicy.Verdict verdict = SmartBuildPolicy.decide(inputs(now, true));
        switch (verdict.decision()) {
            case RUN -> {
                return false;
            }
            case WAIT, BLOCKED -> {
                publish(new Status(Phase.WAITING, branch, verdict.reason()));
                schedule(Math.max(1_000L, verdict.retryAt() - now));
            }
            case DISABLED -> publish(new Status(Phase.DISABLED, branch, ""));
            case NOT_NEEDED -> publish(new Status(Phase.UP_TO_DATE, branch, ""));
        }
        return true;
    }

    private @NotNull SmartBuildPolicy.Inputs inputs(long now, boolean stale) {
        return new SmartBuildPolicy.Inputs(
            isEnabled(),
            stale,
            DumbService.isDumb(project),
            PowerSaveMode.isEnabled(),
            XDebuggerManager.getInstance(project).getDebugSessions().length > 0,
            CompilerManager.getInstance(project).isCompilationActive(),
            PrePushValidationCoordinator.getInstance(project).hasPendingWork(),
            isGitBusy(),
            now,
            lastActivityAt,
            staleSince,
            lastSmartBuildAt,
            idleMillis(),
            TimeUnit.SECONDS.toMillis(Registry.intValue("prepushchecker.smartBuild.shortIdleSeconds", 20)),
            TimeUnit.MINUTES.toMillis(Registry.intValue("prepushchecker.smartBuild.maxStaleMinutes", 15)),
            TimeUnit.MINUTES.toMillis(Registry.intValue("prepushchecker.smartBuild.minIntervalMinutes", 5)));
    }

    // ── Building ─────────────────────────────────────────────────────────────

    private void startBuild(@NotNull String branch, @NotNull String reason) {
        if (!building.compareAndSet(false, true)) return;
        lastSmartBuildAt = System.currentTimeMillis();
        publish(new Status(Phase.BUILDING, branch, reason));
        LOG.info("Smart build starting (" + reason + ") on " + branch);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                building.set(false);
                return;
            }
            new Task.Backgroundable(project, "Smart build: compiling changes", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setText("Compiling changed sources in the background");
                    CompilerManager compiler = CompilerManager.getInstance(project);
                    IdeCompilationRunner.runOnce(project, indicator, notification ->
                        compiler.make(compiler.createProjectCompileScope(project), notification));
                }

                @Override
                public void onFinished() {
                    building.set(false);
                    // The COMPILATION_STATUS listener records the verdict; re-plan afterwards.
                    schedule(POST_BUILD_RECHECK_MS);
                }
            }.queue();
        }, ModalityState.nonModal());
    }

    private void onBuildFinished(boolean aborted, int errorCount, @Nullable CompileContext context,
                                 boolean automake) {
        long startGeneration = generationAtBuildStart;
        generationAtBuildStart = -1L;
        if (aborted || context == null || startGeneration < 0 || project.isDisposed()) return;
        if (!isProjectWide(context)) return;

        List<String> errors = errorCount > 0
            ? CompilationSupport.formatCompilerMessages(
                project, context.getMessages(CompilerMessageCategory.ERROR))
            : Collections.emptyList();
        SmartBuildSnapshotStore.Source source = building.get()
            ? SmartBuildSnapshotStore.Source.SMART
            : automake ? SmartBuildSnapshotStore.Source.AUTOMAKE : SmartBuildSnapshotStore.Source.IDE_BUILD;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (project.isDisposed() || generation.get() != startGeneration) return;
            BuildFingerprint.Result fp = BuildFingerprint.compute(project);
            // Sources edited while the build ran: the verdict belongs to no exact state.
            if (fp == null || generation.get() != startGeneration) return;
            SmartBuildSnapshotStore.getInstance(project).record(
                fp.branchKey(), fp.fingerprint(), errors, source, System.currentTimeMillis());
            fingerprint = fp;
            fingerprintGeneration = startGeneration;
            markVerified(startGeneration, fp, errors, false);
        });
    }

    private boolean isProjectWide(@NotNull CompileContext context) {
        if (context.isRebuild()) return true;
        CompileScope scope = context.getCompileScope();
        if (scope == null) return false;
        return ReadAction.compute(() -> {
            int all = ModuleManager.getInstance(project).getModules().length;
            return all > 0 && scope.getAffectedModules().length >= all;
        });
    }

    private void markVerified(long gen, @NotNull BuildFingerprint.Result fp,
                              @NotNull List<String> errors, boolean restore) {
        verifiedGeneration = gen;
        staleSince = 0L;
        clearErrorsOnMiss = false;
        if (restore) CompilationErrorService.getInstance(project).setErrors(errors);
        long blocking = DiagnosticPathMatcher.filterIgnored(project, errors).size();
        publish(new Status(isEnabled() ? Phase.UP_TO_DATE : Phase.DISABLED, fp.branchLabel(),
            blocking == 0 ? "" : blocking + (blocking == 1 ? " error" : " errors")));
    }

    // ── Push integration ─────────────────────────────────────────────────────

    /**
     * Verdict for a push when the exact working-tree content was already compiled cleanly:
     * an empty list (push may proceed without compiling) or {@code null} (compile).
     * Error verdicts are never reused, so a stale failure can never block a push.
     */
    @Nullable List<String> cachedCleanVerdict() {
        BuildFingerprint.Result fp = BuildFingerprint.compute(project);
        if (fp == null) return null;
        if (!fp.clean() && PrePushCheckerSettings.isStrictSnapshotGuardEnabled(project)) return null;
        long now = System.currentTimeMillis();
        SmartBuildSnapshotStore.Entry entry =
            SmartBuildSnapshotStore.getInstance(project).find(fp.fingerprint(), now);
        if (entry == null) return null;
        long maxAge = TimeUnit.HOURS.toMillis(
            Registry.intValue("prepushchecker.smartBuild.snapshotMaxAgeHours", 24));
        if (now - entry.finishedAt > maxAge) return null;
        if (!entry.isClean()) {
            if (entry.errorCount > SmartBuildSnapshotStore.MAX_STORED_ERRORS) return null;
            if (!DiagnosticPathMatcher.filterIgnored(project, entry.errorList()).isEmpty()) return null;
        }
        LOG.info("Pre-push check reused a clean build snapshot for " + fp.branchLabel()
            + " (" + entry.source + ", " + (now - entry.finishedAt) / 1000 + "s old)");
        CompilationErrorService.getInstance(project).setErrors(entry.errorList());
        return Collections.emptyList();
    }

    // ── Branch retention ─────────────────────────────────────────────────────

    /** Drops snapshots of local branches that no longer exist; keeps everything else. */
    void pruneDeletedBranches() {
        Map<String, Set<String>> branchesByRoot = new HashMap<>();
        for (GitRepository repository : GitRepositoryManager.getInstance(project).getRepositories()) {
            Set<String> names = new HashSet<>();
            repository.getBranches().getLocalBranches().forEach(branch -> names.add(branch.getName()));
            String current = repository.getCurrentBranchName();
            if (current != null) names.add(current);
            branchesByRoot.put(repository.getRoot().getPath(), names);
        }
        int removed = SmartBuildSnapshotStore.getInstance(project).pruneDeletedBranches(ref -> {
            Set<String> names = branchesByRoot.get(ref.root());
            return names == null || names.contains(ref.branch());
        });
        if (removed > 0) LOG.info("Dropped " + removed + " build snapshot(s) of deleted branches");
    }

    // ── Status ───────────────────────────────────────────────────────────────

    @NotNull Status getStatus() {
        return status;
    }

    void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    void removeListener(@NotNull Runnable listener) {
        listeners.remove(listener);
    }

    private void publish(@NotNull Status next) {
        if (next.equals(status)) return;
        status = next;
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (Exception e) {
                    LOG.warn("Smart build status listener threw", e);
                }
            }
        }, ModalityState.any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean needsCheck() {
        return generation.get() != verifiedGeneration;
    }

    private boolean isEnabled() {
        return !project.isDisposed() && PrePushCheckerSettings.isSmartBuildEnabled(project);
    }

    private static long idleMillis() {
        return TimeUnit.SECONDS.toMillis(Registry.intValue("prepushchecker.smartBuild.idleSeconds", 90));
    }

    private boolean isGitBusy() {
        for (GitRepository repository : GitRepositoryManager.getInstance(project).getRepositories()) {
            if (BUSY_GIT_STATES.contains(repository.getState().name())) return true;
        }
        return false;
    }

    /** String-only filter: no VFS or index access, safe on any thread and for huge event batches. */
    static boolean isCompileRelevant(@Nullable String path) {
        if (path == null || path.isEmpty()) return false;
        String p = path.replace('\\', '/');
        if (p.contains("/.git/") || p.contains("/.idea/") || p.contains("/node_modules/")
                || p.contains("/target/") || p.contains("/build/") || p.contains("/out/")
                || p.contains("/.gradle/")) {
            return false;
        }
        return PushValidationPaths.isRelevantPath(p);
    }
}
